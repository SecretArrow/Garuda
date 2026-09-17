package com.motion.browser

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.motion.browser.browser.NEW_TAB_URL
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-emulator smoke tests (spec §68): install, launch, real WebView navigation to a
 * deterministic local test page (spec §64), DOM observation and a real click.
 * The CI pipeline additionally scans logcat for FATAL EXCEPTION after these run.
 *
 * Threading note: browser calls use withContext(Dispatchers.Main) internally, so they
 * MUST be driven from the instrumentation thread (runBlocking at test level) — never
 * from inside onActivity (main thread would deadlock on itself).
 */
@RunWith(AndroidJUnit4::class)
class EmulatorSmokeTest {

    private fun launchApp() {
        ApplicationProvider.getApplicationContext<MotionApp>().onCreate()
        ActivityScenario.launch(MainActivity::class.java)
        // Let the first composition + ServiceLocator.attachBrowser settle.
        Thread.sleep(1_500)
    }

    @Test
    fun appLaunches_andBrowserCoreIsReady() = runBlocking {
        launchApp()
        val browser = ServiceLocator.requireBrowser()
        assertEquals(NEW_TAB_URL, browser.currentUrl())
    }

    @Test
    fun webviewLoadsLocalTestPage_andAgentCanObserveAndClick(): Unit = runBlocking {
        launchApp()
        val browser = ServiceLocator.requireBrowser()

        val ok = browser.openUrl("file:///android_asset/motion_test.html")
        assertTrue("test page must load without main-frame errors", ok)
        assertTrue(browser.currentUrl().contains("motion_test.html"))

        val observation = browser.observePage()
        assertTrue("page title observed", observation.title.contains("Motion Test"))
        assertTrue(
            "elements must be inspected (buttons/inputs)",
            observation.elements.isNotEmpty()
        )
        assertTrue(
            "visible text must be extracted",
            observation.visibleText.contains("Motion Test Page")
        )

        // Real interaction: click the button labelled "Set status" then verify effect.
        val clicked = browser.clickElement(text = "Set status")
        assertTrue("click via text must succeed", clicked)
        Thread.sleep(500)
        val after = browser.extractText()
        assertTrue("status div must have updated", after.contains("status: OK"))
    }

    @Test
    fun tabManager_createsSwitchesAndClosesTabs(): Unit = runBlocking {
        launchApp()
        val manager = ServiceLocator.tabs
        val first = manager.activeTabId.value
        val second = manager.createTab()
        assertTrue(manager.openCount >= 2)
        manager.switchTab(second)
        assertEquals(second, manager.activeTabId.value)
        manager.closeTab(second)
        assertEquals(first, manager.activeTabId.value)
        manager.duplicateTab(manager.activeTabId.value!!)
        assertTrue(manager.openCount >= 2)
    }
}
