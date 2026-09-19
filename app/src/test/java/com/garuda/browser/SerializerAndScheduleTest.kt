package com.garuda.browser

import com.garuda.browser.agent.perception.PageElement
import com.garuda.browser.agent.perception.PageState
import com.garuda.browser.agent.runtime.ScheduleSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Token-budget serializer + schedule parser (plan Prompt 3 §4, Prompt 6C). */
class SerializerAndScheduleTest {

    private fun state() = PageState(
        url = "https://example.com/login",
        title = "Sign in",
        viewportWidth = 360,
        viewportHeight = 740,
        elements = listOf(
            PageElement("e1", "input", "", "Username", "", "", false, "text", "", "", false, 10, 100, 340, 44),
            PageElement("e2", "input", "", "Password", "", "hunter2", false, "password", "", "", false, 10, 150, 340, 44),
            PageElement("e3", "button", "Log in", "", "", "", false, "", "", "", false, 10, 200, 340, 48),
            PageElement("e4", "button", "Disabled thing", "", "", "", true, "", "", "", false, 10, 260, 340, 48),
        ),
        formSummary = "text*,password*,submit",
        textDigest = "Acme portal sign-in page. Enter your credentials.",
    )

    @Test
    fun `serialization includes marks placeholders and disabled flags`() {
        val text = state().serialize()
        assertTrue(text.contains("URL: https://example.com/login"))
        assertTrue(text.contains("e1 INPUT type=text ph=\"Username\""))
        assertTrue(text.contains("val=\"hunter2\""))
        assertTrue(text.contains("e3 BUTTON \"Log in\""))
        assertTrue(text.contains("[disabled]"))
        assertTrue(text.contains("FORMS: text*,password*,submit"))
    }

    @Test
    fun `serialization respects maxChars budget`() {
        val text = state().serialize(maxChars = 120)
        assertTrue("Budgeted serialization must be short", text.length < 400)
    }

    @Test
    fun `daily schedule yields tomorrow at the requested hour`() {
        val from = System.currentTimeMillis()
        val next = ScheduleSpec.nextRunAt("daily 08:00", from)
        assertTrue(next > from)
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = next }
        assertEquals(8, cal.get(java.util.Calendar.HOUR_OF_DAY))
        assertEquals(0, cal.get(java.util.Calendar.MINUTE))
    }

    @Test
    fun `every 6h adds six hours`() {
        val from = System.currentTimeMillis()
        val next = ScheduleSpec.nextRunAt("every 6h", from)
        val diff = next - from
        assertTrue("6h ± 1min, got ${diff}ms", diff in (6 * 3_600_000L - 60_000)..(6 * 3_600_000L + 60_000))
    }
}
