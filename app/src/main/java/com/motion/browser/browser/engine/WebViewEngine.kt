package com.motion.browser.browser.engine

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.motion.browser.browser.NEW_TAB_URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/**
 * WebView-backed [BrowserEngine] (Track A: the Chromium engine embedded in Android —
 * Blink + V8 + Chromium network stack). A future Track-B fork engine implements the
 * same interface (ARCHITECTURE.md §3.2).
 *
 * Threading: all WebView access happens on the main thread (Android rule, contract §4).
 * Non-main callers are marshalled via [withContext]. [evaluateJs] suspends with a timeout
 * and returns the JSON-encoded JS value ("null" on failure — never throws).
 *
 * Renderer crash (spec §40): onRenderProcessGone → event emitted, view detached,
 * [ensureCreated] rebuilds a fresh WebView with the same URL on next attach. Never crashes.
 */
internal class WebViewEngine(
    val tabId: String,
    private val isPrivateTab: Boolean,
    private val activityContext: Context
) : BrowserEngine {

    private val mainThread = checkNotNull(Dispatchers.Main)

    private val _state = MutableStateFlow(EngineSnapshot())
    override val state: StateFlow<EngineSnapshot> = _state.asStateFlow()

    private val _events = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<EngineEvent> = _events.asSharedFlow()

    val fileChooser = FileChooserBridge()

    private var webView: WebView? = null
    private var lastFinishedUrl: String = ""
    private val loadFailed = AtomicBoolean(false)
    private var destroyed = false

    /** Last main-frame error message (null when the current load is clean). */
    private val lastErrorRef = AtomicReference<String?>(null)

    fun lastError(): String? = lastErrorRef.get()

    private fun recordMainError(message: String) {
        loadFailed.set(true)
        lastErrorRef.set(message)
    }

    // ------------------------------------------------------------------ creation

    @SuppressLint("SetJavaScriptEnabled")
    fun ensureCreated(): View {
        if (destroyed) throw IllegalStateException("engine destroyed: $tabId")
        webView?.let { return it }
        val wv = WebView(activityContext)
        wv.layoutParams = View.LayoutParams(
            View.LayoutParams.MATCH_PARENT, View.LayoutParams.MATCH_PARENT
        )
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = true
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            supportZoom()
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            cacheMode = WebSettings.LOAD_DEFAULT
            if (isPrivateTab) {
                saveFormData = false
            }
        }
        wv.webViewClient = client
        wv.webChromeClient = chrome
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, !isPrivateTab)
        wv.addJavascriptInterface(Bridge(), "MotionBridge")
        webView = wv
        return wv
    }

    private val client = object : WebViewClient() {
        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            loadFailed.set(false)
            lastErrorRef.set(null)
            _state.value = _state.value.copy(url = url, isLoading = true, canGoBack = view.canGoBack(), canGoForward = view.canGoForward())
            _events.tryEmit(EngineEvent.PageStarted(url))
        }

        override fun onPageCommitVisible(view: WebView, url: String) {
            _events.tryEmit(EngineEvent.PageCommitVisible(url))
        }

        override fun onPageFinished(view: WebView, url: String) {
            lastFinishedUrl = url
            _state.value = _state.value.copy(
                url = url, title = view.title ?: "", isLoading = false, progress = 100,
                canGoBack = view.canGoBack(), canGoForward = view.canGoForward()
            )
            _events.tryEmit(EngineEvent.PageFinished(url, view.title ?: ""))
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            _state.value = _state.value.copy(url = url, canGoBack = view.canGoBack(), canGoForward = view.canGoForward())
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: android.webkit.WebResourceResponse) {
            val main = request.isForMainFrame
            if (main) recordMainError("HTTP ${errorResponse.statusCode} for ${request.url}")
            _events.tryEmit(EngineEvent.HttpError(request.url.toString(), errorResponse.statusCode, main))
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            val main = request.isForMainFrame
            if (main) recordMainError("${error.errorCode}: ${error.description}")
            _events.tryEmit(EngineEvent.LoadError(request.url.toString(), error.errorCode, error.description?.toString(), main))
        }

        override fun onRenderProcessGone(view: WebView, detail: android.webkit.WebRenderProcessGoneDetail): Boolean {
            // Spec §40: renderer crash recovery — destroy the dead view, expose the event.
            // Returning true tells the system the app handled it (the app must not crash).
            if (view === webView) {
                webView = null
                view.destroy()
                _state.value = _state.value.copy(isLoading = false)
                _events.tryEmit(EngineEvent.RenderProcessGone)
            }
            return true
        }
    }

    private val chrome = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            _state.value = _state.value.copy(progress = newProgress)
        }

        override fun onReceivedTitle(view: WebView, title: String) {
            _state.value = _state.value.copy(title = title)
            _events.tryEmit(EngineEvent.TitleChanged(title))
        }

        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean {
            // Real new-window handling: extract the target URL via the transport and open it in a tab upstream.
            val transport = (resultMsg.obj as? android.webkit.WebView.WebViewTransport) ?: return false
            val probe = WebView(view.context)
            probe.webViewClient = object : WebViewClient() {
                override fun onPageStarted(v: WebView, url: String, favicon: Bitmap?) {
                    _events.tryEmit(EngineEvent.NewWindowRequested(url))
                    probe.destroy()
                }
            }
            transport.webView = probe
            resultMsg.sendToTarget()
            return true
        }

        override fun onShowFileChooser(webView: WebView, callback: android.webkit.ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
            val req = FileChooserRequest(
                acceptTypes = params.acceptTypes?.filter { it.isNotBlank() && it != "*/*" } ?: emptyList(),
                multiple = params.mode == FileChooserParams.MODE_OPEN_MULTIPLE,
                save = params.mode == FileChooserParams.MODE_SAVE,
                suggestedName = null,
                captureEnabled = params.isCaptureEnabled
            )
            return fileChooser.begin(req) { uris -> callback.onReceiveValue(uris) }
        }
    }

    // ------------------------------------------------------------------ navigation

    override fun load(url: String) {
        if (destroyed) return
        if (url == NEW_TAB_URL) return // pure UI surface, never loaded
        val wv = ensureCreated() as WebView
        wv.post { if (!destroyed) wv.loadUrl(url) }
    }

    override fun goBack() = withView { it.goBack() }
    override fun goForward() = withView { it.goForward() }
    override fun reload() = withView { it.reload() }
    override fun stopLoading() = withView { it.stopLoading() }

    override fun canGoBack(): Boolean = _state.value.canGoBack
    override fun canGoForward(): Boolean = _state.value.canGoForward
    override fun currentUrl(): String = _state.value.url
    override fun currentTitle(): String = _state.value.title

    // ------------------------------------------------------------------ JS + media

    override suspend fun evaluateJs(script: String): String = withContext(mainThread) {
        val wv = webView ?: return@withContext "null"
        if (destroyed) return@withContext "null"
        try {
            withTimeout(10_000) {
                suspendCancellableCoroutine { cont ->
                    try {
                        wv.evaluateJavascript(script) { result -> if (cont.isActive) cont.resume(result ?: "null") }
                    } catch (t: Throwable) {
                        if (cont.isActive) cont.resume("null")
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            "null"
        } catch (t: Throwable) {
            "null"
        }
    }

    override suspend fun findInPage(text: String): Int = withContext(mainThread) {
        val wv = webView ?: return@withContext 0
        try {
            withTimeout(5_000) {
                suspendCancellableCoroutine { cont ->
                    var done = false
                    wv.setFindListener { activeMatchOrdinal, numberOfMatches, isDoneCounting ->
                        if (!done && (isDoneCounting || numberOfMatches > 0)) {
                            done = true
                            if (cont.isActive) cont.resume(numberOfMatches)
                        }
                    }
                    wv.findAllAsync(text)
                    // Safety net: resolve after 3 s regardless (honest 0 on timeout).
                    wv.postDelayed({
                        if (!done) { done = true; if (cont.isActive) cont.resume(0) }
                    }, 3_000)
                }
            }
        } catch (t: Throwable) {
            0
        }
    }

    override suspend fun screenshot(): Bitmap? = withContext(mainThread) {
        val wv = webView ?: return@withContext null
        try {
            val width = wv.width.takeIf { it > 0 } ?: wv.measuredWidth
            val height = wv.height.takeIf { it > 0 } ?: wv.measuredHeight
            if (width <= 0 || height <= 0) return@withContext null
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            wv.draw(Canvas(bmp))
            bmp
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * Genuine touch dispatch at page coordinates — used by the agent's
     * clickCoordinates fallback so input flows through the real input pipeline.
     */
    fun dispatchTap(x: Int, y: Int) {
        val wv = webView ?: return
        wv.post {
            try {
                val down = MotionEvent.obtain(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat(), 0)
                val up = MotionEvent.obtain(down.downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x.toFloat(), y.toFloat(), 0)
                wv.dispatchTouchEvent(down)
                wv.dispatchTouchEvent(up)
                down.recycle(); up.recycle()
            } catch (t: Throwable) {
                // never crash on input dispatch
            }
        }
    }

    fun clearPrivateSessionData() {
        // Private tab teardown (honest scope: per-instance cache only — first-party
        // cookies on Android WebView are process-global below API-level profile APIs).
        val wv = webView ?: return
        wv.post {
            runCatching { wv.clearCache(true) }
        }
    }

    override fun destroy() {
        if (destroyed) return
        destroyed = true
        if (isPrivateTab) clearPrivateSessionData()
        val wv = webView
        webView = null
        wv?.post {
            runCatching {
                wv.stopLoading()
                wv.loadUrl("about:blank")
                wv.removeAllViews()
                wv.destroy()
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private inline fun withView(block: (WebView) -> Unit) {
        val wv = webView ?: return
        if (destroyed) return
        wv.post { if (!destroyed) runCatching { block(wv) } }
    }

    /** Minimal JS bridge kept for future deep hooks; currently unused by tools. */
    private inner class Bridge {
        @JavascriptInterface
        fun onAgentPing(): String = "motion"
    }
}
