package com.motion.browser.browser

import android.content.Context
import android.app.DownloadManager
import android.graphics.Bitmap
import android.os.Environment
import com.motion.browser.browser.tabs.TabManager
import com.motion.browser.data.entity.MemoryEntity
import com.motion.browser.security.PromptInjectionDefense
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference

/**
 * High-level browser facade used by the agent tools AND the Compose UI
 * (ARCHITECTURE.md §3.2 — exact method surface; tools compile against it).
 *
 * All WebView access is marshalled to the main thread by the engine layer;
 * network/persistence work here runs on IO. Failures degrade to `false`/empty
 * results — the browser never crashes because a tool call failed (spec §40).
 *
 * Page content returned by extraction methods is UNTRUSTED (spec §24): callers
 * must run it through PromptInjectionDefense.sanitizePageContent before it
 * reaches any LLM — observePage() already applies that sanitization.
 */
class BrowserController(private val tabs: TabManager) {

    /** tabId emitted when a human touches a tab while the agent may be driving it (§28). */
    private val _userInteracted = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val userInteracted: SharedFlow<String> = _userInteracted.asSharedFlow()

    private val touchJob = kotlinx.coroutines.CoroutineScope(Dispatchers.Main.immediate).launch {
        tabs.touchOnTab.collect { tabId -> _userInteracted.tryEmit(tabId) }
    }

    // ------------------------------------------------------------------ navigation

    suspend fun openUrl(url: String, newTab: Boolean = false, waitForLoad: Boolean = true): Boolean {
        val target = normalizeUrl(url) ?: return false
        return withContext(Dispatchers.Main) {
            val tabId = if (newTab) {
                val created = tabs.createTab(NEW_TAB_URL)
                tabs.switchTab(created)
                created
            } else {
                tabs.activeTabId.value ?: tabs.createTab(NEW_TAB_URL)
            }
            tabs.engineFor(tabId)?.load(target)
            tabs.applyProjection(tabId) { copy(url = target) }
            if (waitForLoad) waitForLoadFinished(30_000) else true
        }
    }

    fun goBack() = tabs.engineForActive()?.goBack()
    fun goForward() = tabs.engineForActive()?.goForward()
    fun reload() = tabs.engineForActive()?.reload()
    fun stopLoading() = tabs.engineForActive()?.stopLoading()

    suspend fun currentUrl(): String = withContext(Dispatchers.Main) {
        tabs.activeTabId.value?.let { tab ->
            tabs.tabs.value.firstOrNull { it.id == tab }?.url?.takeIf { it != NEW_TAB_URL }
        } ?: tabs.engineForActive()?.currentUrl().orEmpty().ifBlank { NEW_TAB_URL }
    }

    suspend fun currentTitle(): String = withContext(Dispatchers.Main) {
        tabs.engineForActive()?.currentTitle().orEmpty()
    }

    suspend fun waitForLoadFinished(timeoutMs: Long = 30_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val engine = tabs.engineForActive() ?: return false
            val snap = engine.state.value
            val settled = !snap.isLoading && snap.progress >= 100 &&
                (snap.url.startsWith("http") || snap.url == "about:blank")
            if (settled) return engine.lastError() == null
            delay(200)
        }
        return tabs.engineForActive()?.lastError() == null // honest: timed out counts as not-loaded
    }

    suspend fun waitForElement(selector: String, timeoutMs: Long = 10_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val found = evaluateJs(ScriptsBridge.elementExists(selector))
            if (found == "true") return true
            delay(300)
        }
        return false
    }

    // ------------------------------------------------------------------ JS bridge

    suspend fun evaluateJs(script: String): String {
        val engine = tabs.engineForActive() ?: return "null"
        return engine.evaluateJs(script)
    }

    // ------------------------------------------------------------------ interaction

    suspend fun clickElement(selector: String? = null, text: String? = null): Boolean {
        val script = when {
            text != null -> ScriptsBridge.clickByText(text)
            selector != null -> ScriptsBridge.clickBySelector(selector)
            else -> return false
        }
        val result = evaluateJs(script).parseOk()
        if (!result && selector != null && text == null) {
            // Coordinate fallback only when a bounds hint exists via inspection (§21).
            val el = observePageRaw().elements.firstOrNull {
                it.bounds.width > 0 && it.text.equals(selector, ignoreCase = true)
            } ?: return false
            return clickCoordinates(el.bounds.x + el.bounds.width / 2, el.bounds.y + el.bounds.height / 2)
        }
        return result
    }

    /** Genuine input-pipeline tap via dispatched MotionEvents (not JS .click()). */
    suspend fun clickCoordinates(x: Int, y: Int): Boolean = withContext(Dispatchers.Main) {
        val engine = tabs.engineForActive() ?: return@withContext false
        engine.dispatchTap(x, y)
        true
    }

    suspend fun typeText(selector: String, text: String): Boolean =
        evaluateJs(ScriptsBridge.setInputValue(selector, text)).parseOk()

    suspend fun clearInput(selector: String): Boolean =
        evaluateJs(ScriptsBridge.setInputValue(selector, "")).parseOk()

    suspend fun selectOption(selector: String, value: String): Boolean =
        evaluateJs(ScriptsBridge.selectOption(selector, value)).parseOk()

    suspend fun setCheckbox(selector: String, checked: Boolean): Boolean =
        evaluateJs(ScriptsBridge.setCheckbox(selector, checked)).parseOk()

    suspend fun scroll(dy: Int): Boolean {
        evaluateJs("window.scrollBy(0, $dy); true")
        return true
    }

    suspend fun scrollToText(text: String): Boolean =
        evaluateJs(ScriptsBridge.scrollToText(text)).parseOk()

    suspend fun findText(text: String): Boolean {
        val engine = tabs.engineForActive() ?: return false
        return engine.findInPage(text) > 0
    }

    // ------------------------------------------------------------------ extraction

    suspend fun extractText(): String {
        val raw = evaluateJs("(document.body ? document.body.innerText : '')")
        return runCatching { json.decodeFromString(String.serializer(), raw) }
            .getOrDefault("")
            .trim()
            .take(120_000)
    }

    suspend fun extractLinks(): List<LinkInfo> {
        val obs = observePageRaw()
        return obs.links
    }

    suspend fun extractImages(): List<String> {
        val raw = evaluateJs(ScriptsBridge.EXTRACT_IMAGES)
        return runCatching {
            json.parseToJsonElement(raw).jsonArray.map { it.jsonPrimitive.content }
        }.getOrDefault(emptyList())
    }

    suspend fun extractTables(): String {
        val raw = evaluateJs(ScriptsBridge.EXTRACT_TABLES)
        return raw
    }

    suspend fun inspectDom(): String {
        val raw = evaluateJs(ScriptsBridge.DOM_SUMMARY)
        return raw
    }

    // ------------------------------------------------------------------ observation

    suspend fun observePage(): PageObservation {
        val obs = observePageRaw()
        return obs.copy(visibleText = PromptInjectionDefense.sanitizePageContent(obs.visibleText))
    }

    private suspend fun observePageRaw(): PageObservation {
        val url = currentUrl()
        val title = currentTitle()
        if (url == NEW_TAB_URL || !url.startsWith("http")) {
            return PageObservation(url = url, title = title, visibleText = "", links = emptyList(),
                elements = emptyList(), metadata = emptyMap(), domSummary = "")
        }
        val raw = evaluateJs(ScriptsBridge.INSPECT)
        return runCatching { parseObservation(url, title, raw) }
            .getOrElse { PageObservation(url = url, title = title, visibleText = "", links = emptyList(),
                elements = emptyList(), metadata = emptyMap(), domSummary = "") }
    }

    private fun parseObservation(url: String, title: String, raw: String): PageObservation {
        if (raw == "null" || raw.isBlank()) {
            return PageObservation(url, title, "", emptyList(), emptyList(), emptyMap(), "")
        }
        val root = json.parseToJsonElement(raw).jsonObject
        root["error"]?.let { return PageObservation(url, title, "", emptyList(), emptyList(), emptyMap(), "") }
        val visibleText = root["visibleText"]?.jsonPrimitive?.content ?: ""
        val domSummary = root["domSummary"]?.jsonPrimitive?.content ?: ""
        val metadata = (root["metadata"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
        val links = (root["links"] as? JsonArray)?.map { l ->
            val o = l.jsonObject
            LinkInfo(
                text = o["text"]?.jsonPrimitive?.content ?: "",
                href = o["href"]?.jsonPrimitive?.content ?: ""
            )
        } ?: emptyList()
        val elements = (root["elements"] as? JsonArray)?.map { e ->
            val o = e.jsonObject
            val b = (o["bounds"] as? JsonObject)
            ElementInfo(
                id = o["id"]?.jsonPrimitive?.content ?: "",
                role = o["role"]?.jsonPrimitive?.content ?: "",
                text = o["text"]?.jsonPrimitive?.content ?: "",
                ariaLabel = o["ariaLabel"]?.jsonPrimitive?.takeIf { it !is JsonNull }?.content,
                visible = o["visible"]?.jsonPrimitive?.content == "true",
                enabled = o["enabled"]?.jsonPrimitive?.content != "false",
                bounds = Rect(
                    x = b?.get("x")?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                    y = b?.get("y")?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                    width = b?.get("width")?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                    height = b?.get("height")?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                ),
                confidence = o["confidence"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
            )
        } ?: emptyList()
        return PageObservation(url, title, visibleText, links, elements, metadata, domSummary)
    }

    private fun String.parseOk(): Boolean = runCatching {
        json.parseToJsonElement(this).jsonObject["ok"]?.jsonPrimitive?.content == "true"
    }.getOrDefault(false)

    // ------------------------------------------------------------------ media + data

    suspend fun takeScreenshot(): Bitmap? {
        return tabs.engineForActive()?.screenshot()
    }

    suspend fun downloadFile(url: String): Boolean = withContext(Dispatchers.IO) {
        val context = com.motion.browser.ServiceLocator.appContext
        runCatching {
            val uri = android.net.Uri.parse(url)
            val fileName = uri.lastPathSegment ?: "download_${System.currentTimeMillis()}"
            val request = DownloadManager.Request(uri).apply {
                setTitle(fileName)
                setDescription("Motion Browser download")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
            }
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            true
        }.getOrDefault(false)
    }

    suspend fun saveBookmark(title: String, url: String) = withContext(Dispatchers.IO) {
        val dao = com.motion.browser.ServiceLocator.database.memoryDao()
        val now = System.currentTimeMillis()
        dao.upsert(
            MemoryEntity(
                id = stableId("BOOKMARK:$url"), scope = "BOOKMARK", key = url, value = title,
                goalId = null, domain = null, updatedAt = now
            )
        )
    }

    suspend fun addHistory(url: String, title: String) = withContext(Dispatchers.IO) {
        val dao = com.motion.browser.ServiceLocator.database.memoryDao()
        val now = System.currentTimeMillis()
        dao.upsert(
            MemoryEntity(
                id = stableId("HISTORY:$url:$now"), scope = "HISTORY", key = now.toString(),
                value = "$url|$title", goalId = null, domain = null, updatedAt = now
            )
        )
    }

    // ------------------------------------------------------------------ helpers

    private fun normalizeUrl(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://") ||
            trimmed.startsWith("file://") || trimmed.startsWith("about:") ||
            trimmed.startsWith("data:")
        ) return trimmed
        val looksLikeDomain = !trimmed.contains(' ') && trimmed.contains('.') &&
            trimmed.substringAfterLast('.').length in 2..24
        return if (looksLikeDomain) {
            "https://$trimmed"
        } else {
            "https://duckduckgo.com/?q=" + java.net.URLEncoder.encode(trimmed, "UTF-8")
        }
    }

    private fun stableId(seed: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(40)
    }

    /**
     * Script accessor indirection so the controller can stay below the engine
     * package without a public dependency on Scripts internals.
     */
    private object ScriptsBridge {
        val INSPECT get() = com.motion.browser.browser.engine.Scripts.INSPECT
        val EXTRACT_IMAGES get() = com.motion.browser.browser.engine.Scripts.EXTRACT_IMAGES
        val EXTRACT_TABLES get() = com.motion.browser.browser.engine.Scripts.EXTRACT_TABLES
        val DOM_SUMMARY get() = com.motion.browser.browser.engine.Scripts.DOM_SUMMARY
        fun elementExists(selector: String) = com.motion.browser.browser.engine.Scripts.elementExists(selector)
        fun clickByText(text: String) = com.motion.browser.browser.engine.Scripts.clickByText(text)
        fun clickBySelector(selector: String) = com.motion.browser.browser.engine.Scripts.clickBySelector(selector)
        fun setInputValue(selector: String, value: String) = com.motion.browser.browser.engine.Scripts.setInputValue(selector, value)
        fun selectOption(selector: String, value: String) = com.motion.browser.browser.engine.Scripts.selectOption(selector, value)
        fun setCheckbox(selector: String, checked: Boolean) = com.motion.browser.browser.engine.Scripts.setCheckbox(selector, checked)
        fun scrollToText(text: String) = com.motion.browser.browser.engine.Scripts.scrollToText(text)
    }
}
