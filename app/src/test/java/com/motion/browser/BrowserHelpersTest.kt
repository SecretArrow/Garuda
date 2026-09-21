package com.motion.browser

import com.motion.browser.browser.downloads.MotionDownloader
import com.motion.browser.data.BrowserData
import com.motion.browser.ui.normalizeUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM unit tests for pure browser helpers (URL normalization, file names, sizes). */
class BrowserHelpersTest {

    // --- normalizeUrl (omnibox resolution, Chrome-like) ---

    @Test
    fun bareDomainGetsHttps() {
        assertEquals("https://example.com", normalizeUrl("example.com"))
        assertEquals("https://example.com/page", normalizeUrl("example.com/page"))
    }

    @Test
    fun plainTextBecomesSearch() {
        val out = normalizeUrl("best android browser 2026")
        assertTrue(out.startsWith("https://duckduckgo.com/?q="))
        assertTrue(out.contains("best+android+browser"))
    }

    @Test
    fun absoluteUrlKept() {
        assertEquals("http://a.b/c", normalizeUrl("http://a.b/c"))
        assertEquals("https://x.org/", normalizeUrl("https://x.org/"))
    }

    @Test
    fun blankBecomesBlankPage() {
        assertEquals("about:blank", normalizeUrl(""))
        assertEquals("about:blank", normalizeUrl("   "))
    }

    // --- BrowserData pure helpers ---

    @Test
    fun dayStartIsMidnight() {
        // 2026-09-21 15:30:45.123 local
        val cal = java.util.Calendar.getInstance().apply {
            set(2026, 8, 21, 15, 30, 45); set(java.util.Calendar.MILLISECOND, 123)
        }
        val start = BrowserData.dayStartOf(cal.timeInMillis)
        java.util.Calendar.getInstance().apply { timeInMillis = start }.let {
            assertEquals(0, it.get(java.util.Calendar.HOUR_OF_DAY))
            assertEquals(0, it.get(java.util.Calendar.MINUTE))
            assertEquals(0, it.get(java.util.Calendar.SECOND))
            assertEquals(21, it.get(java.util.Calendar.DAY_OF_MONTH))
        }
    }

    @Test
    fun historyKeyStripsFragment() {
        assertEquals("https://x.org/a", BrowserData.normalizeKey("https://x.org/a#section"))
        assertEquals("https://x.org/a", BrowserData.normalizeKey("  https://x.org/a#  "))
    }

    @Test
    fun fileNameFromUrlPath() {
        assertEquals("report.pdf", BrowserData.guessFileName("https://site.com/files/report.pdf"))
        assertEquals("data", BrowserData.guessFileName("https://site.com/data"))
    }

    @Test
    fun sanitizeStripsIllegalChars() {
        assertEquals("a_b_c", BrowserData.sanitizeFileName("a/b\\c"))
        assertEquals("clean", BrowserData.sanitizeFileName("clean"))
    }

    // --- MotionDownloader.formatBytes ---

    @Test
    fun byteFormatting() {
        assertEquals("999 B", MotionDownloader.formatBytes(999))
        assertEquals("1.0 KB", MotionDownloader.formatBytes(1024))
        assertEquals("1.0 MB", MotionDownloader.formatBytes(1024 * 1024))
        assertEquals("1.0 GB", MotionDownloader.formatBytes(1L shl 30))
    }
}
