package com.motion.browser.shields

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Shields — the browser's anti-tracking / anti-ad layer (Track A scope).
 *
 * Pure domain logic + session state, fully unit-testable without Android:
 *  - [shouldBlock] decides per subresource request (host suffix match against a curated
 *    tracker/ad blocklist; first-party requests are always allowed; main-frame decisions
 *    are made by the engine layer, which never blocks the page the user navigated to).
 *  - [ShieldsState] holds the session toggles (on/off, third-party cookies).
 *  - [blockedTotal]/[blockedByDomain] are per-session counters surfaced in the omnibox badge.
 *
 * Honest scope: blocklist is embedded (no auto-updates yet) and counters reset with the
 * process — documented as such so the UI never overstates protection.
 */
object ShieldsEngine {

    /** Curated blocklist — well-known ad/tracker/pixel/attribution hosts, matched by suffix. */
    val TRACKER_DOMAINS: Set<String> = setOf(
        // Google ads & analytics
        "doubleclick.net", "google-analytics.com", "googlesyndication.com",
        "googleadservices.com", "adservice.google.com", "adsystem.com",
        // Social pixels
        "facebook.net", "fbcdn.net", "ads-twitter.com", "analytics.twitter.com",
        "ads.linkedin.com", "px.ads.linkedin.com", "snap.kochava.com", "sc-static.net",
        // Ad exchanges / RTB
        "adnxs.com", "criteo.com", "criteo.net", "pubmatic.com", "rubiconproject.com",
        "openx.net", "casalemedia.com", "indexexchange.com", "smartadserver.com",
        "adform.net", "adroll.com", "bidswitch.net", "sharethrough.com", "spotxchange.com",
        "teads.tv", "yieldmo.com", "33across.com", "bidr.io", "everesttech.net",
        // Measurement / audience
        "scorecardresearch.com", "quantserve.com", "quantcount.com", "moatads.com",
        "moatpixel.com", "comscore.com", "nielsen.com", "chartbeat.com", "parsely.com",
        // Analytics / session replay / heatmaps
        "mixpanel.com", "segment.io", "segment.com", "amplitude.com", "fullstory.com",
        "hotjar.com", "mouseflow.com", "crazyegg.com", "smartlook.com", "clarity.ms",
        "inspectlet.com", "luckyorange.com", "matomo.cloud", "stats.wp.com",
        // Attribution / mobile measurement
        "appsflyer.com", "adjust.com", "kochava.com", "branch.io", "singular.net",
        "tune.com", "attributionapp.com",
        // Content recommendation / native ads
        "taboola.com", "outbrain.com", "revcontent.com", "mgid.com", "zedo.com",
        "amazon-adsystem.com", "ads.yahoo.com", "adcolony.com", "unityads.unity3d.com"
    )

    private val _blockedTotal = MutableStateFlow(0)
    private val totalCounter = AtomicLong()

    /** Total blocked subresources this session (badge in the omnibox). */
    val blockedTotal: StateFlow<Int> = _blockedTotal.asStateFlow()

    private val _blockedByDomain = MutableStateFlow<Map<String, Int>>(emptyMap())

    /** Blocked count per tracker domain (session stats, e.g. "doubleclick.net" → 12). */
    val blockedByDomain: StateFlow<Map<String, Int>> = _blockedByDomain.asStateFlow()

    private val domainCounts = ConcurrentHashMap<String, AtomicLong>()

    /** Host of [url] (lowercase, no port, no www.); "" when unparsable. */
    fun hostOf(url: String): String = runCatching {
        URI(url).host?.lowercase()?.removePrefix("www.") ?: ""
    }.getOrDefault("")

    /** True when [host] equals or is a subdomain of a blocklist entry. */
    fun matchesBlocklist(host: String): Boolean {
        if (host.isBlank()) return false
        return TRACKER_DOMAINS.any { d -> host == d || host.endsWith(".$d") }
    }

    /**
     * Decision for one subresource request.
     * @param url     the requested resource URL
     * @param pageUrl the page that triggered the request (first-party context)
     */
    fun shouldBlock(url: String, pageUrl: String, enabled: Boolean = ShieldsState.enabled.value): Boolean {
        if (!enabled) return false
        val lower = url.lowercase()
        // Only http(s) subresources are ever blocked — file://, data:, about:, javascript: pass through.
        if (!(lower.startsWith("http://") || lower.startsWith("https://"))) return false
        val host = hostOf(url)
        if (host.isBlank()) return false
        // First-party is never blocked by Shields (same host as the current page).
        if (!pageUrl.isNullOrBlank() && host == hostOf(pageUrl)) return false
        return matchesBlocklist(host)
    }

    /** Records a blocked request into the session counters (thread-safe; called from IO threads). */
    fun recordBlock(url: String) {
        _blockedTotal.value = totalCounter.incrementAndGet().toInt() // atomic single-writer publish
        val host = hostOf(url).ifBlank { "unknown" }
        // Roll subdomains up to the matched blocklist parent (ad.doubleclick.net → doubleclick.net)
        // so the per-domain stats read like the blocklist itself.
        val bucket = TRACKER_DOMAINS.firstOrNull { d -> host == d || host.endsWith(".$d") } ?: host
        val newCount = domainCounts.computeIfAbsent(bucket) { AtomicLong() }.incrementAndGet()
        _blockedByDomain.value = _blockedByDomain.value + (bucket to newCount.toInt())
    }

    /** Clears the session counters (used by tests and "reset stats"). */
    fun resetStats() {
        domainCounts.clear()
        totalCounter.set(0)
        _blockedTotal.value = 0
        _blockedByDomain.value = emptyMap()
    }
}

/**
 * Session-scoped Shields toggles. Kept in memory deliberately: they follow the
 * privacy posture "no traces" — a fresh process starts protected with defaults.
 */
object ShieldsState {
    /** Master switch — toggled from the omnibox shield icon. */
    val enabled = MutableStateFlow(true)

    /** Block third-party cookies in every (non-private) tab when on. */
    val blockThirdPartyCookies = MutableStateFlow(true)
}
