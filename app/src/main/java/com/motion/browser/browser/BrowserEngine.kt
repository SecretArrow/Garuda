package com.motion.browser.browser

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebView
import com.motion.cdp.CdpTabSession
import com.motion.cdp.DevToolsClient
import com.motion.cdp.DevToolsLocator
import com.motion.browser.agent.action.ActionExecutor
import com.motion.browser.agent.action.CdpPageControl
import com.motion.browser.agent.action.PageControl
import com.motion.browser.agent.runtime.AgentSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * WebView-based tab host (agent-layer prototype phase; plan "Catatan Penting").
 * WebView debugging is enabled so the in-process DevTools socket
 * (@webview_devtools_remote_<pid>) is live — the SAME CDP surface the Brave
 * fork will expose as @motion-devtools. The agent layer is engine-agnostic.
 */
class MotionTab(
    val id: String,
    @SuppressLint("SetJavaScriptEnabled") val webView: WebView,
) {
    var title: String = "New tab"
    var cdpSession: CdpTabSession? = null
    /** Page load progress 0..100 (drives the progress bar). */
    var progress: Int = 100
    /** Last main-frame error, null when the page is fine (drives the retry card). */
    var lastError: String? = null
    /** Incognito: no history records for this tab. */
    var incognito: Boolean = false
    /** Desktop site: desktop UA + wide viewport + reload. */
    var desktopMode: Boolean = false
}

class BrowserEngine(private val context: Context) {

    val tabs = mutableListOf<MotionTab>()
    var activeIndex = 0
    private val resolveMutex = Mutex()
    private val ioScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + Dispatchers.IO
    )

    init {
        WebView.setWebContentsDebuggingEnabled(true)
    }

    val activeTab: MotionTab? get() = tabs.getOrNull(activeIndex)

    @Synchronized
    fun createTab(url: String = "about:blank", incognito: Boolean = false): MotionTab {
        val webView = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.loadsImagesAutomatically = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.setSupportMultipleWindows(false)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        val tab = MotionTab(id = "tab_${System.currentTimeMillis()}_${tabs.size}", webView = webView)
        tab.incognito = incognito
        wireTab(tab)
        tabs.add(tab)
        activeIndex = tabs.size - 1
        if (url != "about:blank") webView.loadUrl(url)
        return tab
    }

    /** Client/chrome/download wiring: history, progress, errors, downloads. */
    private fun wireTab(tab: MotionTab) = with(tab.webView) {
        webViewClient = object : android.webkit.WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                tab.title = view?.title.orEmpty().ifBlank { tab.title }
                tab.lastError = null
                val target = url.orEmpty()
                if (!tab.incognito && target.startsWith("http") &&
                    com.motion.browser.ServiceLocator.isAttached
                ) {
                    ioScope.launch {
                        runCatching {
                            com.motion.browser.data.BrowserData.recordVisit(
                                target, tab.title, com.motion.browser.ServiceLocator.database
                            )
                        }
                    }
                }
            }

            override fun onReceivedError(
                view: WebView?, request: android.webkit.WebResourceRequest?, error: android.webkit.WebResourceError?,
            ) {
                if (request?.isForMainFrame == true) {
                    tab.lastError = error?.description?.toString() ?: "Failed to load page"
                }
            }
        }
        webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                tab.progress = newProgress
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                tab.title = title.orEmpty().ifBlank { tab.title }
            }
        }
        setDownloadListener { url, userAgent, contentDisposition, mime, _ ->
            com.motion.browser.browser.downloads.MotionDownloader.enqueue(
                url = url, contentDisposition = contentDisposition, mime = mime,
            )
        }
    }

    /** Toggles desktop UA for [tab] and reloads (Chrome parity). */
    fun setDesktopMode(tab: MotionTab, enabled: Boolean) {
        tab.desktopMode = enabled
        tab.webView.settings.userAgentString =
            if (enabled) DESKTOP_UA else android.webkit.WebSettings.getDefaultUserAgent(context)
        tab.webView.reload()
    }

    /** Toggles incognito (history recording) for [tab]; applies immediately. */
    fun setIncognito(tab: MotionTab, enabled: Boolean) {
        tab.incognito = enabled
        tab.webView.settings.saveFormData = !enabled
    }

    @Synchronized
    fun closeTab(index: Int): Boolean {
        if (index < 0 || index >= tabs.size) return false
        val tab = tabs.removeAt(index)
        tab.cdpSession?.close()
        // WebView.destroy must run on the thread that owns the WebView (main).
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            runCatching { tab.webView.destroy() }
        }
        if (activeIndex >= tabs.size) activeIndex = (tabs.size - 1).coerceAtLeast(0)
        return true
    }

    @Synchronized
    fun switchTo(index: Int): Boolean {
        if (index < 0 || index >= tabs.size) return false
        activeIndex = index
        return true
    }

    companion object {
        const val HOME_URL = "https://duckduckgo.com/"
        const val DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Safari/537.36"
    }

    /**
     * Resolves the CDP target for a tab and connects a persistent session.
     * Targets are matched by URL (WebView's /json/list does not expose a direct
     * WebView↔target link); the fork patch will expose per-tab sockets directly.
     * The URL read is a WebView method call — must run on the main thread.
     */
    suspend fun cdpSessionFor(tab: MotionTab): CdpTabSession = resolveMutex.withLock {
        tab.cdpSession?.let { return it }
        val client = withContext(Dispatchers.IO) {
            DevToolsClient.autoDiscover() ?: error("DevTools socket not live")
        }
        val tabUrl = withContext(Dispatchers.Main) { tab.webView.url.orEmpty() }
        val targets = withContext(Dispatchers.IO) { client.listTargets() }
        val target = targets.firstOrNull { it.url == tabUrl }
            ?: targets.firstOrNull { it.url.isNotBlank() && tabUrl.isNotBlank() && it.url == tabUrl.substringBefore('#') }
            ?: targets.firstOrNull()
            ?: error("No CDP page target available")
        val session = client.connectTo(target.targetId)
        tab.cdpSession = session
        session
    }

    /** [AgentSession] for the orchestrator, bound to the active (or given) tab. */
    fun agentSessionFor(tabKey: String?): AgentSession = object : AgentSession {
        private fun tab(): MotionTab =
            tabs.firstOrNull { it.id == tabKey } ?: activeTab ?: createTab()

        override suspend fun page(): PageControl =
            CdpPageControl(cdpSessionFor(tab()))

        override val tabOps: ActionExecutor.TabOps = object : ActionExecutor.TabOps {
            override suspend fun openTab(url: String): Int {
                withContext(Dispatchers.Main) { createTab(url) }
                return tabs.size - 1
            }
            override suspend fun switchTo(index: Int): Boolean = this@BrowserEngine.switchTo(index)
            override suspend fun closeTab(index: Int?): Boolean =
                this@BrowserEngine.closeTab(index ?: activeIndex)
            override suspend fun currentTabIndex(): Int = activeIndex
        }
    }
}
