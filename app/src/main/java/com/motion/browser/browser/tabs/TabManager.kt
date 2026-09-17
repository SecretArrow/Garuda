package com.motion.browser.browser.tabs

import android.content.Context
import android.graphics.Color
import android.view.MotionEvent
import android.view.View
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.motion.browser.browser.NEW_TAB_URL
import com.motion.browser.browser.Tab
import com.motion.browser.browser.engine.EngineEvent
import com.motion.browser.browser.engine.WebViewEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns the open-tab set and one [WebViewEngine] per tab (ARCHITECTURE.md §3.2 —
 * exact contract: StateFlows + createTab/closeTab/switchTab/duplicateTab/reopenClosed/
 * getWebView/openCount).
 *
 * Each WebView is hosted inside a [TouchDetectFrameLayout] so the runtime can pause
 * the agent when a human touches an agent-controlled tab (spec §28) — touches are
 * reported to [touchOnTab] before children consume them.
 *
 * Session persistence (spec §41 "persistent sessions"): open-tab URLs + active tab are
 * stored in DataStore and restored on process recreation. Private tabs are never
 * persisted (honest scope — see WebViewEngine).
 *
 * The manager accepts an application context (headless agent runs) or an Activity
 * context (interactive UI); both work — WebView creation with app context is supported.
 */
class TabManager(private val activity: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val context: Context = activity

    private class Holder(val engine: WebViewEngine, val container: TouchDetectFrameLayout)

    private val holders = ConcurrentHashMap<String, Holder>()

    private val _tabs = MutableStateFlow<List<Tab>>(emptyList())
    val tabs: StateFlow<List<Tab>> = _tabs.asStateFlow()

    private val _activeTabId = MutableStateFlow<String?>(null)
    val activeTabId: StateFlow<String?> = _activeTabId.asStateFlow()

    /** Aggregated page-finished stream (tabId, url, title) — drives real history. */
    private val _pageFinished = MutableSharedFlow<Triple<String, String, String>>(extraBufferCapacity = 32)
    val pageFinished: SharedFlow<Triple<String, String, String>> = _pageFinished.asSharedFlow()

    /** Human touched the tab content (tabId) — the runtime pauses on this (§28). */
    private val _touchOnTab = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val touchOnTab: SharedFlow<String> = _touchOnTab.asSharedFlow()

    private val closedStack = ArrayDeque<String>()

    /** URLs queued to load when a freshly created tab becomes active. */
    private val pendingLoad = ConcurrentHashMap<String, String>()

    /** UI-side SAF launcher for engine file-chooser requests (see ui/browser/FileChooser.kt). */
    var fileChooserLauncher: ((com.motion.browser.browser.engine.FileChooserRequest) -> Unit)? = null

    /** The active tab's file-chooser bridge (null when no tab is active). */
    val activeFileChooser: com.motion.browser.browser.engine.FileChooserBridge?
        get() = engineForActive()?.fileChooser

    init {
        scope.launch { restoreSession() }
        scope.launch {
            pageFinished.collect { (tabId, url, title) ->
                val private = _tabs.value.firstOrNull { it.id == tabId }?.isPrivate ?: false
                if (!private && url.startsWith("http")) {
                    runCatching { storeHistory(url, title) }
                }
            }
        }
    }

    // ------------------------------------------------------------------ creation

    fun createTab(url: String = NEW_TAB_URL, isPrivate: Boolean = false): String {
        val id = "tab_" + UUID.randomUUID().toString().take(12)
        val engine = WebViewEngine(id, isPrivate, activity)
        engine.fileChooser.onLaunch = { request -> fileChooserLauncher?.invoke(request) }
        val container = TouchDetectFrameLayout(activity) { _touchOnTab.tryEmit(id) }
        container.addView(engine.ensureCreated())
        holders[id] = Holder(engine, container)
        _tabs.value = _tabs.value + Tab(id = id, url = url, title = "", isPrivate = isPrivate)
        if (_activeTabId.value == null) _activeTabId.value = id

        collectEngineEvents(id, engine)

        when {
            url != NEW_TAB_URL && id == _activeTabId.value -> engine.load(url)
            url != NEW_TAB_URL -> pendingLoad[id] = url
        }
        scope.launch { persistSession() }
        return id
    }

    private fun collectEngineEvents(tabId: String, engine: WebViewEngine) {
        scope.launch {
            engine.events.collect { event ->
                when (event) {
                    is EngineEvent.PageStarted -> updateProjection(tabId) { copy(url = event.url, isLoading = true) }
                    is EngineEvent.PageFinished -> {
                        updateProjection(tabId) { copy(url = event.url, title = event.title, isLoading = false, progress = 100) }
                        _pageFinished.tryEmit(Triple(tabId, event.url, event.title))
                    }
                    is EngineEvent.TitleChanged -> updateProjection(tabId) { copy(title = event.title) }
                    is EngineEvent.PageCommitVisible -> Unit
                    is EngineEvent.HttpError, is EngineEvent.LoadError -> updateProjection(tabId) { copy(isLoading = false) }
                    EngineEvent.RenderProcessGone -> {
                        // Spec §40: renderer died. Rebuild lazily; UI re-attaches via getContainerView.
                        holders[tabId]?.let { h ->
                            runCatching { h.container.removeAllViews() }
                        }
                    }
                    is EngineEvent.NewWindowRequested -> {
                        // target=_blank / window.open → real new tab (never hijack into same tab).
                        val newId = createTab(NEW_TAB_URL, isPrivate = isPrivate(tabId))
                        pendingLoad[newId] = event.url
                    }
                }
            }
        }
    }

    fun closeTab(id: String) {
        val holder = holders.remove(id) ?: return
        val tab = _tabs.value.firstOrNull { it.id == id } ?: return
        if (tab.url != NEW_TAB_URL && !tab.isPrivate) {
            closedStack.addLast(tab.url)
            while (closedStack.size > 20) closedStack.removeFirst()
        }
        runCatching { holder.engine.destroy() }
        _tabs.value = _tabs.value.filterNot { it.id == id }
        if (_activeTabId.value == id) {
            _activeTabId.value = _tabs.value.lastOrNull()?.id
        }
        scope.launch { persistSession() }
    }

    fun switchTab(id: String) {
        if (holders[id] == null) return
        _activeTabId.value = id
        val tab = _tabs.value.firstOrNull { it.id == id } ?: return
        val holder = holders[id] ?: return
        val pending = pendingLoad.remove(id)
        if (pending != null) {
            holder.engine.load(pending)
        } else if (tab.url != NEW_TAB_URL &&
            (holder.engine.currentUrl() == NEW_TAB_URL || holder.engine.currentUrl().isBlank())
        ) {
            holder.engine.load(tab.url)
        }
        scope.launch { persistSession() }
    }

    fun duplicateTab(id: String) {
        val source = _tabs.value.firstOrNull { it.id == id } ?: return
        if (source.url == NEW_TAB_URL) {
            createTab()
        } else {
            val newId = createTab(NEW_TAB_URL, isPrivate = source.isPrivate)
            pendingLoad[newId] = source.url
            switchTab(newId)
        }
    }

    fun reopenClosed(): Boolean {
        val url = closedStack.removeLastOrNull() ?: return false
        val id = createTab(NEW_TAB_URL)
        pendingLoad[id] = url
        switchTab(id)
        return true
    }

    fun getWebView(id: String): WebView? = holders[id]?.engine?.let { it.ensureCreated() as WebView }

    /** The hostable container for the tab (touch-detecting wrapper). */
    fun getContainerView(id: String): View? = holders[id]?.container

    fun engineFor(id: String): WebViewEngine? = holders[id]?.engine

    fun engineForActive(): WebViewEngine? = _activeTabId.value?.let { holders[it]?.engine }

    val openCount: Int get() = _tabs.value.size

    fun isPrivate(id: String): Boolean = _tabs.value.firstOrNull { it.id == id }?.isPrivate ?: false

    // ------------------------------------------------------------------ internals

    private fun updateProjection(tabId: String, transform: Tab.() -> Tab) {
        _tabs.value = _tabs.value.map { if (it.id == tabId) it.transform() else it }
    }

    /** Coordinator/BrowserController hook to reflect URL changes into the tab projection. */
    internal fun applyProjection(tabId: String, transform: Tab.() -> Tab) = updateProjection(tabId, transform)

    private fun storeHistory(url: String, title: String) {
        val dao = com.motion.browser.ServiceLocator.database.memoryDao()
        val now = System.currentTimeMillis()
        scope.launch {
            dao.upsert(
                com.motion.browser.data.entity.MemoryEntity(
                    id = stableId("HISTORY:$url:$now"),
                    scope = "HISTORY", key = now.toString(), value = "$url|$title",
                    goalId = null, domain = null, updatedAt = now
                )
            )
        }
    }

    private fun stableId(seed: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(seed.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(40)
    }

    // ------------------------------------------------------------------ session persistence

    @Serializable
    private data class SessionTab(val url: String)

    @Serializable
    private data class Session(val tabs: List<SessionTab> = emptyList(), val activeIndex: Int = 0)

    private val Context.sessionDataStore by preferencesDataStore(name = "motion_session")
    private val SESSION_KEY = stringPreferencesKey("session_json_v1")
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private suspend fun persistSession() {
        val open = _tabs.value.filter { !it.isPrivate && it.url != NEW_TAB_URL }
        val session = Session(
            tabs = open.map { SessionTab(it.url) },
            activeIndex = open.indexOfFirst { it.id == _activeTabId.value }.coerceAtLeast(0)
        )
        runCatching {
            context.sessionDataStore.edit { prefs ->
                prefs[SESSION_KEY] = json.encodeToString(Session.serializer(), session)
            }
        }
    }

    private suspend fun restoreSession() {
        val raw = runCatching { context.sessionDataStore.data.first()[SESSION_KEY] }.getOrNull() ?: return
        val session = runCatching { json.decodeFromString(Session.serializer(), raw) }.getOrNull() ?: return
        if (_tabs.value.isNotEmpty()) return // deep link or explicit open beat restoration
        if (session.tabs.isEmpty()) {
            createTab()
            return
        }
        session.tabs.forEachIndexed { index, st ->
            val id = createTab(NEW_TAB_URL)
            pendingLoad[id] = st.url
            if (index == session.activeIndex) switchTab(id)
        }
    }

    // ------------------------------------------------------------------ touch detector

    /** FrameLayout that reports human touches before children consume them (spec §28). */
    private class TouchDetectFrameLayout(
        context: Context,
        private val onTouchDetected: () -> Unit
    ) : FrameLayout(context) {
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            onTouchDetected()
            return super.dispatchTouchEvent(event)
        }
    }
}
