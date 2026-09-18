package com.motion.browser.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

private val Context.settingsDataStore by preferencesDataStore(name = "motion_settings")

/** App-wide theme selection (spec: Light / Dark / System). */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Default search engine used by the omnibox and the New Tab page. */
enum class SearchEngine(val label: String, val queryUrl: String) {
    GOOGLE("Google", "https://www.google.com/search?q="),
    BING("Bing", "https://www.bing.com/search?q="),
    DUCKDUCKGO("DuckDuckGo", "https://duckduckgo.com/?q="),
    BRAVE("Brave", "https://search.brave.com/search?q="),
    STARTPAGE("Startpage", "https://www.startpage.com/sp/search?query="),
    CUSTOM("Custom", "");

    companion object {
        fun fromName(name: String?): SearchEngine =
            entries.firstOrNull { it.name == name } ?: GOOGLE
    }
}

/** What to show when the app starts. */
enum class StartupMode { RESTORE_SESSION, NEW_TAB, HOMEPAGE }

/**
 * Immutable snapshot of all user-facing browser settings. Emitted as a
 * [StateFlow] so the engine, theme and UI can react without suspend calls.
 */
data class BrowserSettings(
    // Appearance
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val fontScalePercent: Int = 100,
    val forceDarkInWebView: Boolean = false,
    // General
    val homepage: String = "about:newtab",
    val searchEngine: SearchEngine = SearchEngine.GOOGLE,
    val customSearchUrl: String = "",
    val startupMode: StartupMode = StartupMode.RESTORE_SESSION,
    // Privacy & security
    val javascriptEnabled: Boolean = true,
    val cookiesEnabled: Boolean = true,
    val blockThirdPartyCookies: Boolean = true,
    val doNotTrack: Boolean = false,
    val safeBrowsing: Boolean = true,
    val blockPopups: Boolean = true,
    val autoplayEnabled: Boolean = true,
    val shieldsEnabled: Boolean = true,
    val clearOnExit: Boolean = false,
    // Site defaults (per-site overrides live in sitePermissionJson)
    val siteCamera: Boolean = false,
    val siteMicrophone: Boolean = false,
    val siteLocation: Boolean = false,
    val siteNotifications: Boolean = false,
    val siteClipboard: Boolean = false,
    // Downloads
    val askWhereToSave: Boolean = false,
    val downloadNotifications: Boolean = true,
    val downloadWifiOnly: Boolean = false,
    // Advanced
    val desktopSiteDefault: Boolean = false,
    val hardwareAcceleration: Boolean = true,
    val textZoom: Int = 100,
    // UI discoverability
    val onboardingDone: Boolean = false,
)

/**
 * Single source of truth for browser preferences (DataStore Preferences).
 * Engine/web-compat wiring reads the synchronous [current] snapshot;
 * Settings UI observes [settings] reactively.
 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val FONT_SCALE = intPreferencesKey("font_scale_percent")
        val FORCE_DARK = booleanPreferencesKey("force_dark_webview")
        val HOMEPAGE = stringPreferencesKey("homepage")
        val SEARCH_ENGINE = stringPreferencesKey("search_engine")
        val CUSTOM_SEARCH_URL = stringPreferencesKey("custom_search_url")
        val STARTUP_MODE = stringPreferencesKey("startup_mode")
        val JAVASCRIPT = booleanPreferencesKey("javascript_enabled")
        val COOKIES = booleanPreferencesKey("cookies_enabled")
        val BLOCK_3P_COOKIES = booleanPreferencesKey("block_third_party_cookies")
        val DO_NOT_TRACK = booleanPreferencesKey("do_not_track")
        val SAFE_BROWSING = booleanPreferencesKey("safe_browsing")
        val BLOCK_POPUPS = booleanPreferencesKey("block_popups")
        val AUTOPLAY = booleanPreferencesKey("autoplay_enabled")
        val SHIELDS = booleanPreferencesKey("shields_enabled")
        val CLEAR_ON_EXIT = booleanPreferencesKey("clear_on_exit")
        val SITE_CAMERA = booleanPreferencesKey("site_camera")
        val SITE_MIC = booleanPreferencesKey("site_microphone")
        val SITE_LOCATION = booleanPreferencesKey("site_location")
        val SITE_NOTIFICATIONS = booleanPreferencesKey("site_notifications")
        val SITE_CLIPBOARD = booleanPreferencesKey("site_clipboard")
        val ASK_WHERE_TO_SAVE = booleanPreferencesKey("ask_where_to_save")
        val DOWNLOAD_NOTIFICATIONS = booleanPreferencesKey("download_notifications")
        val DOWNLOAD_WIFI_ONLY = booleanPreferencesKey("download_wifi_only")
        val DESKTOP_DEFAULT = booleanPreferencesKey("desktop_site_default")
        val HARDWARE_ACCEL = booleanPreferencesKey("hardware_acceleration")
        val TEXT_ZOOM = intPreferencesKey("text_zoom")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
    }

    private val _settings = MutableStateFlow(BrowserSettings())

    /** Synchronous latest snapshot — safe from any thread (engine callbacks). */
    val current: BrowserSettings get() = _settings.value

    val settings: StateFlow<BrowserSettings> = _settings.asStateFlow()

    init {
        // Seed the synchronous snapshot from disk once at construction; later
        // changes are always written through this repository so it stays hot.
        runCatching {
            val persisted = runBlocking { read().first() }
            _settings.value = persisted
            // Reflect privacy toggles into the existing Shields live state.
            ShieldsBridge.apply(this@SettingsRepository)
        }
    }

    private fun read(): Flow<BrowserSettings> = context.settingsDataStore.data.map { prefs ->
        BrowserSettings(
            themeMode = runCatching { ThemeMode.valueOf(prefs[Keys.THEME_MODE] ?: "SYSTEM") }
                .getOrDefault(ThemeMode.SYSTEM),
            dynamicColor = prefs[Keys.DYNAMIC_COLOR] ?: true,
            fontScalePercent = prefs[Keys.FONT_SCALE] ?: 100,
            forceDarkInWebView = prefs[Keys.FORCE_DARK] ?: false,
            homepage = prefs[Keys.HOMEPAGE] ?: "about:newtab",
            searchEngine = SearchEngine.fromName(prefs[Keys.SEARCH_ENGINE]),
            customSearchUrl = prefs[Keys.CUSTOM_SEARCH_URL] ?: "",
            startupMode = runCatching { StartupMode.valueOf(prefs[Keys.STARTUP_MODE] ?: "RESTORE_SESSION") }
                .getOrDefault(StartupMode.RESTORE_SESSION),
            javascriptEnabled = prefs[Keys.JAVASCRIPT] ?: true,
            cookiesEnabled = prefs[Keys.COOKIES] ?: true,
            blockThirdPartyCookies = prefs[Keys.BLOCK_3P_COOKIES] ?: true,
            doNotTrack = prefs[Keys.DO_NOT_TRACK] ?: false,
            safeBrowsing = prefs[Keys.SAFE_BROWSING] ?: true,
            blockPopups = prefs[Keys.BLOCK_POPUPS] ?: true,
            autoplayEnabled = prefs[Keys.AUTOPLAY] ?: true,
            shieldsEnabled = prefs[Keys.SHIELDS] ?: true,
            clearOnExit = prefs[Keys.CLEAR_ON_EXIT] ?: false,
            siteCamera = prefs[Keys.SITE_CAMERA] ?: false,
            siteMicrophone = prefs[Keys.SITE_MIC] ?: false,
            siteLocation = prefs[Keys.SITE_LOCATION] ?: false,
            siteNotifications = prefs[Keys.SITE_NOTIFICATIONS] ?: false,
            siteClipboard = prefs[Keys.SITE_CLIPBOARD] ?: false,
            askWhereToSave = prefs[Keys.ASK_WHERE_TO_SAVE] ?: false,
            downloadNotifications = prefs[Keys.DOWNLOAD_NOTIFICATIONS] ?: true,
            downloadWifiOnly = prefs[Keys.DOWNLOAD_WIFI_ONLY] ?: false,
            desktopSiteDefault = prefs[Keys.DESKTOP_DEFAULT] ?: false,
            hardwareAcceleration = prefs[Keys.HARDWARE_ACCEL] ?: true,
            textZoom = prefs[Keys.TEXT_ZOOM] ?: 100,
            onboardingDone = prefs[Keys.ONBOARDING_DONE] ?: false,
        )
    }

    // ------------------------------------------------------------------ mutators

    suspend fun setThemeMode(mode: ThemeMode) = write(Keys.THEME_MODE, mode.name)
    suspend fun setDynamicColor(enabled: Boolean) = write(Keys.DYNAMIC_COLOR, enabled)
    suspend fun setFontScale(percent: Int) = write(Keys.FONT_SCALE, percent.coerceIn(50, 200))
    suspend fun setForceDark(enabled: Boolean) = write(Keys.FORCE_DARK, enabled)
    suspend fun setHomepage(url: String) = write(Keys.HOMEPAGE, url)
    suspend fun setSearchEngine(engine: SearchEngine) = write(Keys.SEARCH_ENGINE, engine.name)
    suspend fun setCustomSearchUrl(url: String) = write(Keys.CUSTOM_SEARCH_URL, url)
    suspend fun setStartupMode(mode: StartupMode) = write(Keys.STARTUP_MODE, mode.name)
    suspend fun setJavascriptEnabled(enabled: Boolean) = write(Keys.JAVASCRIPT, enabled)
    suspend fun setCookiesEnabled(enabled: Boolean) = write(Keys.COOKIES, enabled)
    suspend fun setBlockThirdPartyCookies(block: Boolean) = write(Keys.BLOCK_3P_COOKIES, block)
    suspend fun setDoNotTrack(enabled: Boolean) = write(Keys.DO_NOT_TRACK, enabled)
    suspend fun setSafeBrowsing(enabled: Boolean) = write(Keys.SAFE_BROWSING, enabled)
    suspend fun setBlockPopups(block: Boolean) = write(Keys.BLOCK_POPUPS, block)
    suspend fun setAutoplayEnabled(enabled: Boolean) = write(Keys.AUTOPLAY, enabled)
    suspend fun setShieldsEnabled(enabled: Boolean) = write(Keys.SHIELDS, enabled)
    suspend fun setClearOnExit(enabled: Boolean) = write(Keys.CLEAR_ON_EXIT, enabled)
    suspend fun setSiteCamera(enabled: Boolean) = write(Keys.SITE_CAMERA, enabled)
    suspend fun setSiteMicrophone(enabled: Boolean) = write(Keys.SITE_MIC, enabled)
    suspend fun setSiteLocation(enabled: Boolean) = write(Keys.SITE_LOCATION, enabled)
    suspend fun setSiteNotifications(enabled: Boolean) = write(Keys.SITE_NOTIFICATIONS, enabled)
    suspend fun setSiteClipboard(enabled: Boolean) = write(Keys.SITE_CLIPBOARD, enabled)
    suspend fun setAskWhereToSave(enabled: Boolean) = write(Keys.ASK_WHERE_TO_SAVE, enabled)
    suspend fun setDownloadNotifications(enabled: Boolean) = write(Keys.DOWNLOAD_NOTIFICATIONS, enabled)
    suspend fun setDownloadWifiOnly(only: Boolean) = write(Keys.DOWNLOAD_WIFI_ONLY, only)
    suspend fun setDesktopSiteDefault(enabled: Boolean) = write(Keys.DESKTOP_DEFAULT, enabled)
    suspend fun setHardwareAcceleration(enabled: Boolean) = write(Keys.HARDWARE_ACCEL, enabled)
    suspend fun setTextZoom(zoom: Int) = write(Keys.TEXT_ZOOM, zoom.coerceIn(50, 200))
    suspend fun setOnboardingDone(done: Boolean) = write(Keys.ONBOARDING_DONE, done)

    private suspend fun <T> write(key: Preferences.Key<T>, value: T) {
        context.settingsDataStore.edit { it[key] = value }
        _settings.value = read().first()
        ShieldsBridge.apply(this@SettingsRepository)
    }

    /** Resolve the effective search URL for a query (respects the custom engine). */
    fun searchUrlFor(query: String): String {
        val engine = current.searchEngine
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        return when {
            engine == SearchEngine.CUSTOM && current.customSearchUrl.isNotBlank() ->
                current.customSearchUrl + encoded
            engine == SearchEngine.CUSTOM -> SearchEngine.GOOGLE.queryUrl + encoded
            else -> engine.queryUrl + encoded
        }
    }

    /**
     * Lightweight indirection so SettingsRepository never depends on the
     * shields module directly (keeps the data layer free of browser code).
     */
    internal object ShieldsBridge {
        fun apply(repo: SettingsRepository) {
            runCatching {
                val s = repo.current
                com.motion.browser.shields.ShieldsState.enabled.value = s.shieldsEnabled
                com.motion.browser.shields.ShieldsState.blockThirdPartyCookies.value = s.blockThirdPartyCookies
            }
        }
    }
}
