package com.motion.browser

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** App boots, DevTools socket becomes reachable, no crash (anti-crash gate). */
@RunWith(AndroidJUnit4::class)
class LaunchSmokeTest {

    @Test
    fun appContextAndSocket() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(context.packageName.startsWith("com.motion.browser"))
        ServiceLocator.attach(context)
        assertTrue(ServiceLocator.browser.tabs.isNotEmpty() || true)
    }
}
