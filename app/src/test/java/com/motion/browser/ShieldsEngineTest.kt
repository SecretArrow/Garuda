package com.motion.browser

import com.motion.browser.shields.ShieldsEngine
import com.motion.browser.shields.ShieldsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the Shields anti-tracking engine: blocklist matching,
 * first-party exemption, scheme handling, toggles and session counters.
 */
class ShieldsEngineTest {

    @Test
    fun hostOf_normalizesCaseWwwAndPort() {
        assertEquals("example.com", ShieldsEngine.hostOf("https://WWW.Example.COM:8080/a?b=1"))
        assertEquals("sub.example.co", ShieldsEngine.hostOf("http://sub.example.co/x"))
        assertEquals("", ShieldsEngine.hostOf("not a url"))
    }

    @Test
    fun blocklist_matchesExactAndSubdomains() {
        assertTrue(ShieldsEngine.matchesBlocklist("doubleclick.net"))
        assertTrue(ShieldsEngine.matchesBlocklist("ad.doubleclick.net"))
        assertTrue(ShieldsEngine.matchesBlocklist("stats.g.doubleclick.net"))
        assertTrue(ShieldsEngine.matchesBlocklist("www.google-analytics.com"))
        // lookalikes must NOT match on prefix tricks
        assertFalse(ShieldsEngine.matchesBlocklist("notdoubleclick.net"))
        assertFalse(ShieldsEngine.matchesBlocklist("doubleclick.net.evil.com"))
    }

    @Test
    fun shouldBlock_blocksThirdPartyTrackers_only() {
        val page = "https://news.example.com/article"
        assertTrue(ShieldsEngine.shouldBlock("https://www.google-analytics.com/analytics.js", page))
        assertTrue(ShieldsEngine.shouldBlock("https://ads.rubiconproject.com/ad.js", page))
        assertTrue(ShieldsEngine.shouldBlock("https://connect.facebook.net/en_US/fbevents.js", page))
        // first-party request: same host as the page → never blocked
        assertFalse(ShieldsEngine.shouldBlock("https://news.example.com/api/feed", page))
        // unrelated non-tracker third party (e.g. a CDN) → allowed
        assertFalse(ShieldsEngine.shouldBlock("https://cdn.jsdelivr.net/npm/lib.js", page))
    }

    @Test
    fun shouldBlock_ignoresNonHttpSchemes_andRespectsToggle() {
        val page = "https://example.com/"
        assertFalse(ShieldsEngine.shouldBlock("file:///android_asset/x.html", page))
        assertFalse(ShieldsEngine.shouldBlock("data:text/plain,hi", page))
        assertFalse(ShieldsEngine.shouldBlock("about:blank", page))
        ShieldsState.enabled.value = false
        try {
            assertFalse(ShieldsEngine.shouldBlock("https://www.doubleclick.net/x.js", page))
        } finally {
            ShieldsState.enabled.value = true
        }
        assertTrue(ShieldsEngine.shouldBlock("https://www.doubleclick.net/x.js", page))
    }

    @Test
    fun recordBlock_countsTotalAndPerDomain() {
        ShieldsEngine.resetStats()
        ShieldsEngine.recordBlock("https://www.doubleclick.net/a.js")
        ShieldsEngine.recordBlock("https://ad.doubleclick.net/b.js")
        ShieldsEngine.recordBlock("https://www.hotjar.com/c.js")
        assertEquals(3, ShieldsEngine.blockedTotal.value)
        assertEquals(2, ShieldsEngine.blockedByDomain.value["doubleclick.net"])
        assertEquals(1, ShieldsEngine.blockedByDomain.value["hotjar.com"])
        ShieldsEngine.resetStats()
        assertEquals(0, ShieldsEngine.blockedTotal.value)
        assertTrue(ShieldsEngine.blockedByDomain.value.isEmpty())
    }

    @Test
    fun pageUrlBlank_stillBlocksKnownTrackers() {
        // Observation may not know the page URL yet (cold start) — third-party trackers still blocked.
        assertTrue(ShieldsEngine.shouldBlock("https://www.googlesyndication.com/pagead/x.js", ""))
    }
}
