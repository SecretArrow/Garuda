package com.motion.browser.browser.engine

import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import com.motion.browser.browser.NEW_TAB_URL
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Immutable navigation/state snapshot of a web engine instance.
 * Kept as a [StateFlow] so UI and the agent loop can observe without polling.
 */
data class EngineSnapshot(
    val url: String = NEW_TAB_URL,
    val title: String = "",
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val isLoading: Boolean = false,
    val progress: Int = 0,
    /** Last main-frame error for the current page (null when the load is clean). */
    val lastError: String? = null
)

/**
 * Engine lifecycle/navigation events. Emitted from the main thread.
 * Buffered with DROP_OLDEST so slow collectors never block the engine.
 */
sealed class EngineEvent {
    data class PageStarted(val url: String) : EngineEvent()
    data class PageCommitVisible(val url: String) : EngineEvent()
    data class PageFinished(val url: String, val title: String) : EngineEvent()
    data class TitleChanged(val title: String) : EngineEvent()
    data class HttpError(val url: String, val statusCode: Int, val isMainFrame: Boolean) : EngineEvent()
    data class LoadError(val url: String?, val code: Int, val description: String?, val isMainFrame: Boolean) : EngineEvent()

    /** The renderer process crashed. The engine never crashes the app; it exposes the event. */
    data object RenderProcessGone : EngineEvent()

    /** A new-window request (target=_blank / window.open) resolved to a concrete URL. */
    data class NewWindowRequested(val url: String) : EngineEvent()

    /** Shields blocked a tracker/ad subresource (url = the blocked request). */
    data class RequestBlocked(val url: String) : EngineEvent()

    /** The page asked the browser to download a resource (DownloadListener). */
    data class DownloadRequested(
        val url: String,
        val contentDisposition: String?,
        val mimeType: String?
    ) : EngineEvent()

    /** Fullscreen HTML5 video entered/left (custom view host). */
    data class FullscreenChanged(val active: Boolean) : EngineEvent()
}

/**
 * Engine-agnostic description of a file-chooser request coming from the page
 * (mapped from the platform WebChromeClient.FileChooserParams by the
 * WebView-backed implementation, so future fork engines can reuse it).
 */
data class FileChooserRequest(
    val acceptTypes: List<String> = emptyList(),
    val multiple: Boolean = false,
    val save: Boolean = false,
    val suggestedName: String? = null,
    val captureEnabled: Boolean = false
)

/**
 * Bridge between an engine (which receives file-chooser requests) and the UI
 * layer (which owns SAF activity-result launchers). Guarantees the platform
 * ValueCallback semantics: resolved exactly once, `null` on cancel, on the
 * main thread. Lives in the browser layer so the engine never depends on UI.
 */
class FileChooserBridge {

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Set by the UI (see ui/browser/FileChooser.kt). Cleared on dispose. */
    @Volatile
    var onLaunch: ((FileChooserRequest) -> Unit)? = null

    private var pending: ((Array<Uri>?) -> Unit)? = null

    /**
     * Starts a chooser. Returns false when no UI handler is attached
     * (e.g. headless agent usage) — the callback is then cancelled so the
     * WebView is never left with a dangling ValueCallback.
     */
    fun begin(request: FileChooserRequest, callback: (Array<Uri>?) -> Unit): Boolean {
        pending?.let { stale -> runCatching { stale(null) } }
        pending = callback
        val launch = onLaunch ?: run {
            pending = null
            return false
        }
        return try {
            launch(request)
            true
        } catch (t: Throwable) {
            pending = null
            runCatching { callback(null) }
            false
        }
    }

    /** Resolves the pending request with the picked documents (empty list = cancel). */
    fun complete(uris: List<Uri>) = resolve(if (uris.isEmpty()) null else uris.toTypedArray())

    /** Cancels the pending request (callback invoked with null). */
    fun cancel() = resolve(null)

    private fun resolve(result: Array<Uri>?) {
        val callback = pending
        pending = null
        if (callback == null) return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runCatching { callback(result) }
        } else {
            mainHandler.post { runCatching { callback(result) } }
        }
    }
}

/**
 * Abstraction over the web engine so a future Chromium-fork engine (Track B,
 * motion-chromium/) can implement the same surface. The interface is kept
 * WebView-free: only Android View/Bitmap types plus the shared event/snapshot
 * model appear here.
 *
 * Threading: [load], [goBack], [goForward], [reload], [stopLoading],
 * [ensureCreated] and [destroy] are safe to call from any thread.
 * [ensureCreated] must run on the main thread for the WebView implementation
 * (callers that need the view synchronously are main-thread by construction).
 */
interface BrowserEngine {
    val state: StateFlow<EngineSnapshot>
    val events: SharedFlow<EngineEvent>

    /** Navigates to [url]. "about:newtab" is intentionally a no-op (UI surface). */
    fun load(url: String)

    fun goBack()
    fun goForward()
    fun reload()
    fun stopLoading()

    fun canGoBack(): Boolean
    fun canGoForward(): Boolean
    fun currentUrl(): String
    fun currentTitle(): String

    /**
     * Evaluates [script] in the current page context. Returns the JSON-encoded
     * result (the engine serializes the JS value), or the literal "null" when
     * evaluation failed/timed out.
     */
    suspend fun evaluateJs(script: String): String

    /** Native find-in-page; returns the number of matches (0 on failure). */
    suspend fun findInPage(text: String): Int

    /** Renders the current viewport into a bitmap; null when nothing is attached. */
    suspend fun screenshot(): Bitmap?

    /** The root view that renders web content; created lazily on the main thread. */
    fun ensureCreated(): View

    /** Permanently releases engine resources (also detaches the view). */
    fun destroy()
}
