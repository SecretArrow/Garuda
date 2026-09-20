package com.garuda.browser.browser

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebView
import com.garuda.cdp.CdpTabSession
import com.garuda.cdp.DevToolsClient
import com.garuda.cdp.DevToolsLocator
import com.garuda.browser.agent.action.ActionExecutor
import com.garuda.browser.agent.action.CdpPageControl
import com.garuda.browser.agent.action.PageControl
import com.garuda.browser.agent.runtime.AgentSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * WebView-based tab host (agent-layer prototype phase; plan "Catatan Penting").
 * WebView debugging is enabled so the in-process DevTools socket
 * (@webview_devtools_remote_<pid>) is live — the SAME CDP surface the Brave
 * fork will expose as @garuda-devtools. The agent layer is engine-agnostic.
 */
class GarudaTab(
    val id: String,
    @SuppressLint("SetJavaScriptEnabled") val webView: WebView,
) {
    var title: String = "New tab"
    var cdpSession: CdpTabSession? = null
}

class BrowserEngine(private val context: Context) {

    val tabs = mutableListOf<GarudaTab>()
    var activeIndex = 0
    private val resolveMutex = Mutex()

    init {
        WebView.setWebContentsDebuggingEnabled(true)
    }

    val activeTab: GarudaTab? get() = tabs.getOrNull(activeIndex)

    @Synchronized
    fun createTab(url: String = "about:blank"): GarudaTab {
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
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        val tab = GarudaTab(id = "tab_${System.currentTimeMillis()}_${tabs.size}", webView = webView)
        tabs.add(tab)
        activeIndex = tabs.size - 1
        if (url != "about:blank") webView.loadUrl(url)
        return tab
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

    /**
     * Resolves the CDP target for a tab and connects a persistent session.
     * Targets are matched by URL (WebView's /json/list does not expose a direct
     * WebView↔target link); the fork patch will expose per-tab sockets directly.
     * The URL read is a WebView method call — must run on the main thread.
     */
    suspend fun cdpSessionFor(tab: GarudaTab): CdpTabSession = resolveMutex.withLock {
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
        private fun tab(): GarudaTab =
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
