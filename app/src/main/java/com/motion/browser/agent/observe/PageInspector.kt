package com.motion.browser.agent.observe

import android.graphics.Bitmap
import android.util.Base64
import com.motion.browser.browser.BrowserController
import com.motion.browser.browser.PageObservation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Observation facade over the browser engine (ARCHITECTURE.md §3.6).
 *
 * WebView access is kept on Dispatchers.Main (contract §4). Failures (e.g. tab closed, engine
 * detached, activity destroyed) degrade to an EMPTY observation instead of crashing the run —
 * the runtime loop treats repeated empty observations as "browser lost".
 *
 * @param tabId reserved for per-tab observation; the underlying controller observes the active tab.
 */
class PageInspector(private val browser: BrowserController) {

    suspend fun observe(tabId: String? = null): PageObservation {
        return withContext(Dispatchers.Main) {
            runCatching { browser.observePage() }.getOrElse {
                PageObservation(
                    url = "",
                    title = "",
                    visibleText = "",
                    links = emptyList(),
                    elements = emptyList(),
                    metadata = emptyMap(),
                    domSummary = ""
                )
            }
        }
    }

    /** Bitmap → JPEG quality 70 → Base64 (NO_WRAP). Null when the engine cannot screenshot. */
    suspend fun screenshotBase64(tabId: String? = null): String? {
        val bitmap = withContext(Dispatchers.Main) {
            runCatching { browser.takeScreenshot() }.getOrNull()
        } ?: return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val sink = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 70, sink)
                Base64.encodeToString(sink.toByteArray(), Base64.NO_WRAP)
            }.getOrNull()
        }
    }
}
