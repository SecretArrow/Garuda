package com.garuda.browser

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.garuda.cdp.DevToolsClient
import com.garuda.browser.browser.BrowserEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Flagship integration test (plan Prompt 2 acceptance item d): dispatch a tap
 * THROUGH CDP Input.dispatchTouchEvent and verify the page received a
 * isTrusted:true event — the core advantage over naive JS injection.
 */
@RunWith(AndroidJUnit4::class)
class CdpTrustedInputTest {

    companion object {
        /** Shared discovery loop with CI-visible diagnostics on failure. */
        internal suspend fun attachSessionWithDiagnostics(
            engine: BrowserEngine,
            tab: com.garuda.browser.browser.GarudaTab,
        ): com.garuda.cdp.CdpTabSession? = withTimeout(90_000) {
            var lastProbe = ""
            var attempt = 0
            while (true) {
                attempt++
                val client = withContext(Dispatchers.IO) { DevToolsClient.autoDiscover() }
                if (client != null) {
                    val attached = runCatching { engine.cdpSessionFor(tab) }
                    if (attached.isSuccess) return@withTimeout attached.getOrNull()
                    lastProbe = "attach failed: ${attached.exceptionOrNull()?.message}"
                } else {
                    lastProbe = com.garuda.cdp.DevToolsLocator.diagnostics()
                }
                if (attempt % 15 == 1) {
                    println("GARUDA_DIAG attempt=$attempt $lastProbe")
                    android.util.Log.w("GARUDA_DIAG", "attempt=$attempt $lastProbe")
                }
                kotlinx.coroutines.delay(500)
            }
            @Suppress("UNREACHABLE_CODE") null
        }
    }

    @Test
    fun tapViaCdpProducesTrustedEvent() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = BrowserEngine(context)
        val tab = withContext(Dispatchers.Main) {
            engine.createTab("file:///android_asset/cdp_test.html")
        }

        // Wait for page load, then for the DevTools socket to expose the target.
        val session = attachSessionWithDiagnostics(engine, tab)
        assertNotNull("Could not attach a CDP session to the tab", session)
        session!!.waitForLoad(15_000)

        // Trust marker not set yet.
        val before = session.evaluate("window.__lastTrusted === true").optBoolean("value", false)
        assertTrue("Page must not be pre-trusted", !before)

        // Precise button rect (never guess coordinates — plan §6B).
        val rect = session.evaluate(
            "(()=>{const r=document.getElementById('btn').getBoundingClientRect();" +
                "return JSON.stringify([Math.round(r.x),Math.round(r.y),Math.round(r.width),Math.round(r.height)])})()"
        ).optString("value")
        val arr = org.json.JSONArray(rect)
        val x = arr.optInt(0) + arr.optInt(2) / 2
        val y = arr.optInt(1) + arr.optInt(3) / 2

        session.dispatchTap(x, y)
        kotlinx.coroutines.delay(400)

        val trusted = session.evaluate("window.__lastTrusted === true").optBoolean("value", false)
        assertEquals("Input.dispatchTouchEvent must produce isTrusted:true events", true, trusted)

        val clicks = session.evaluate("window.__clicks || 0").optInt("value", 0)
        assertEquals(1, clicks)

        session.close()
        Unit
    }
}
