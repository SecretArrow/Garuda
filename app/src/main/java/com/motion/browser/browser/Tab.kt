package com.motion.browser.browser

/**
 * URL of the built-in new-tab page. This is a pure UI surface (see
 * [com.motion.browser.ui.browser.NewTabPage]) and is never loaded into the
 * web engine; the engine treats it as a no-op navigation.
 */
const val NEW_TAB_URL = "about:newtab"

/**
 * A browser tab record. This is the UI/state projection of an engine tab;
 * the live web content lives behind [com.motion.browser.browser.tabs.TabManager].
 *
 * Field order and defaults are part of the ARCHITECTURE.md §3.2 contract —
 * do not rename or reorder.
 */
data class Tab(
    val id: String,
    val url: String,
    val title: String,
    val isPrivate: Boolean,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val isLoading: Boolean = false,
    val progress: Int = 0,
    /** Last main-frame load error (null when clean) — drives the in-app error card. */
    val lastError: String? = null
)
