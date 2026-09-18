package com.motion.browser.browser.engine

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.Manifest
import android.content.pm.PackageManager
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import androidx.core.content.ContextCompat
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.motion.browser.ServiceLocator
import com.motion.browser.browser.NEW_TAB_URL
import com.motion.browser.shields.ShieldsEngine
import com.motion.browser.shields.ShieldsState
import java.io.ByteArrayInputStream
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
        _state.value = _state.value.copy(lastError = message)
    }

    // ------------------------------------------------------------------ creation

    @SuppressLint("SetJavaScriptEnabled")
    override fun ensureCreated(): View {
        if (destroyed) throw IllegalStateException("engine destroyed: $tabId")
        webView?.let { return it }
        val wv = WebView(activityContext)
        wv.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        wv.settings.apply {
            javaScriptEnabled = true // modern web baseline; user toggle respected below
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = true
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            supportZoom()
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            cacheMode = WebSettings.LOAD_DEFAULT
            setNeedInitialFocus(false)
            if (isPrivateTab) {
                saveFormData = false
            }
        }
        applyUserSettings(wv)
        wv.webViewClient = client
        wv.webChromeClient = chrome
        wv.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            _events.tryEmit(EngineEvent.DownloadRequested(url, contentDisposition, mimeType))
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(
            wv, !isPrivateTab && !ServiceLocator.settingsRepository.current.blockThirdPartyCookies
        )
        wv.addJavascriptInterface(Bridge(), "MotionBridge")
        webView = wv
        return wv
    }

    /**
     * Applies user preferences (Settings screen) onto a live WebView. Called at
     * creation and whenever settings change (TabManager observes the flow).
     */
    fun applyUserSettings(target: WebView? = null) {
        val wv = target ?: webView ?: return
        val s = ServiceLocator.settingsRepository.current
        runCatching {
            wv.settings.apply {
                javaScriptEnabled = s.javascriptEnabled
                mediaPlaybackRequiresUserGesture = !s.autoplayEnabled
                blockNetworkLoads = false
                safeBrowsingEnabled = s.safeBrowsing
                textZoom = s.textZoom
                userAgentString = if (s.desktopSiteDefault) DESKTOP_USER_AGENT else defaultUserAgent
            }
            @Suppress("DEPRECATION")
            wv.settings.forceDark = if (s.forceDarkInWebView) {
                WebSettings.FORCE_DARK_ON
            } else {
                WebSettings.FORCE_DARK_OFF
            }
            CookieManager.getInstance().setAcceptCookie(s.cookiesEnabled && !isPrivateTab)
            CookieManager.getInstance().setAcceptThirdPartyCookies(
                wv, s.cookiesEnabled && !isPrivateTab && !s.blockThirdPartyCookies
            )
        }
    }

    /** Desktop-site toggle for this tab (UA swap + reload happens upstream). */
    fun setDesktopUserAgent(enabled: Boolean) {
        val wv = webView ?: return
        runCatching {
            wv.settings.userAgentString = if (enabled) DESKTOP_USER_AGENT else defaultUserAgent
            wv.reload()
        }
    }

    private val defaultUserAgent: String
        get() = runCatching { WebSettings.getDefaultUserAgent(activityContext) }.getOrDefault("")

    private val client = object : WebViewClient() {
        /**
         * Shields (anti-tracking): called on an IO thread for every subresource.
         * Main-frame navigations are NEVER blocked (the user chose to go there);
         * known tracker/ad hosts on third-party requests get an empty 403 response.
         */
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            if (!ShieldsState.enabled.value || request.isForMainFrame) return null
            val url = request.url.toString()
            if (!ShieldsEngine.shouldBlock(url, _state.value.url)) return null
            ShieldsEngine.recordBlock(url)
            _events.tryEmit(EngineEvent.RequestBlocked(url))
            return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0))).apply {
                // WebResourceResponse exposes ONLY the combined setter (no setStatusCode alone).
                setStatusCodeAndReasonPhrase(403, "Blocked by Shields")
            }
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            loadFailed.set(false)
            lastErrorRef.set(null)
            _state.value = _state.value.copy(lastError = null)
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

        override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
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

        // ---- HTML5 fullscreen video (custom view host, rendered by the UI layer)
        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            CustomViewHost.enter(view, callback)
            _events.tryEmit(EngineEvent.FullscreenChanged(true))
        }

        override fun onHideCustomView() {
            CustomViewHost.exit()
            _events.tryEmit(EngineEvent.FullscreenChanged(false))
        }

        // ---- Camera / microphone consent (web APIs) gated by site settings + app grants
        override fun onPermissionRequest(request: PermissionRequest?) {
            val req = request ?: return
            req.request.origin?.let { origin ->
                val granted = req.resources.mapNotNull { resource ->
                    when (resource) {
                        PermissionRequest.RESOURCE_VIDEO_CAPTURE ->
                            resource.takeIf { siteAllows("camera") && hasAppPermission(Manifest.permission.CAMERA) }
                        PermissionRequest.RESOURCE_AUDIO_CAPTURE ->
                            resource.takeIf { siteAllows("microphone") && hasAppPermission(Manifest.permission.RECORD_AUDIO) }
                        else -> null
                    }
                }.toTypedArray()
                mainHandlerSafe.post {
                    runCatching { if (granted.isEmpty()) req.deny() else req.grant(granted) }
                }
            } ?: mainHandlerSafe.post { runCatching { req.deny() } }
        }

        // ---- Geolocation consent
        override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
            val allow = siteAllows("location") &&
                hasAppPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            callback?.invoke(origin, allow, false)
        }

        private fun siteAllows(kind: String): Boolean {
            val s = ServiceLocator.settingsRepository.current
            return when (kind) {
                "camera" -> s.siteCamera
                "microphone" -> s.siteMicrophone
                "location" -> s.siteLocation
                "notifications" -> s.siteNotifications
                else -> false
            }
        }

        private fun hasAppPermission(permission: String): Boolean =
            ContextCompat.checkSelfPermission(activityContext, permission) ==
                PackageManager.PERMISSION_GRANTED

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
        applyUserSettings(wv)
        val headers = mutableMapOf<String, String>()
        if (ServiceLocator.settingsRepository.current.doNotTrack) headers["DNT"] = "1"
        wv.post { if (!destroyed) runCatching { wv.loadUrl(url, headers) } }
    }

    override fun goBack() = withView { it.goBack() }
    override fun goForward() = withView { it.goForward() }
    override fun reload() = withView { it.reload() }
    override fun stopLoading() = withView { it.stopLoading() }

    /** Move between find-in-page matches (forward = true) — used by the find bar UI. */
    fun findNext(forward: Boolean) {
        val wv = webView ?: return
        mainHandlerSafe { if (!destroyed) wv.findNext(forward) }
    }

    /** Close find-in-page mode and clear highlights. */
    fun clearFind() {
        val wv = webView ?: return
        mainHandlerSafe { if (!destroyed) runCatching { wv.findAllAsync("") } }
    }

    private val mainHandlerSafe = android.os.Handler(android.os.Looper.getMainLooper())

    private fun mainHandlerSafe(block: () -> Unit) {
        mainHandlerSafe.post { runCatching { block() } }
    }

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

    private fun withView(block: (WebView) -> Unit) {
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

/** UA used by the "Desktop site" toggle (Chrome desktop Linux, current stable). */
private const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/124.0.0.0 Safari/537.36"

/**
 * Holds the active HTML5 fullscreen video view so the UI layer can render it
 * above everything (BrowserScreen observes [view]). Engine-agnostic and
 * process-wide; only one fullscreen surface can exist at a time.
 */
object CustomViewHost {
    private val _view = MutableStateFlow<View?>(null)
    val view: StateFlow<View?> = _view.asStateFlow()

    @Volatile
    private var callback: WebChromeClient.CustomViewCallback? = null

    fun enter(v: View, cb: WebChromeClient.CustomViewCallback) {
        callback?.let { runCatching { it.onCustomViewHidden() } }
        callback = cb
        _view.value = v
    }

    fun exit() {
        runCatching { callback?.onCustomViewHidden() }
        callback = null
        _view.value = null
    }
}
