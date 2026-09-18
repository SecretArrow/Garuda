package com.motion.browser.ui.nav

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.motion.browser.ServiceLocator
import com.motion.browser.ui.ai.AiPanel
import com.motion.browser.ui.browser.BrowserScreen
import com.motion.browser.ui.control.ControlCenterScreen
import com.motion.browser.ui.control.GoalEditorScreen
import com.motion.browser.ui.control.LogsScreen
import com.motion.browser.ui.control.ProvidersScreen
import com.motion.browser.ui.data.BookmarksScreen
import com.motion.browser.ui.data.DownloadsScreen
import com.motion.browser.ui.data.HistoryScreen
import com.motion.browser.ui.chat.ChatIntake
import com.motion.browser.ui.chat.ChatScreen
import com.motion.browser.ui.settings.SettingsScreen
import com.motion.browser.ui.theme.MotionTheme
import com.motion.browser.ui.theme.SystemBarAppearanceEffect

/** Simple screen stack (no navigation dependency) — coordinator-owned wiring surface. */
sealed class Screen {
    data object Browser : Screen()
    data object ControlCenter : Screen()
    data object GoalEditor : Screen()
    data object Providers : Screen()
    data object Logs : Screen()
    data object Settings : Screen()
    data object Bookmarks : Screen()
    data object History : Screen()
    data object Downloads : Screen()
    data object AiChat : Screen()
}

/**
 * Root composable hosting the whole app (ARCHITECTURE.md §3.7).
 *
 * WindowInsets contract: the window draws edge-to-edge (transparent system
 * bars, see MainActivity.enableEdgeToEdge) and the content here is padded by
 * [Modifier.safeDrawingPadding] — status bar, navigation bar/gesture area,
 * cutouts and the IME are NEVER covered by app content. System bar icon
 * appearance follows the active Motion theme (light/dark/system).
 *
 * @param onDeepLinkUrl optional URL from an ACTION_VIEW intent, opened once on start.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun MotionRoot(onDeepLinkUrl: String? = null) {
    val settings = ServiceLocator.settingsRepository.settings.collectAsState()
    MotionTheme(
        themeMode = settings.value.themeMode,
        dynamicColor = settings.value.dynamicColor,
    ) {
        val darkTheme = MaterialTheme.colorScheme.background.luminanceSafe() < 0.5f
        SystemBarAppearanceEffect(darkTheme = darkTheme)

        Surface(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding(),
            color = MaterialTheme.colorScheme.background,
        ) {
            var screen by remember { mutableStateOf<Screen>(Screen.Browser) }
            var pendingDeepLink by remember { mutableStateOf(onDeepLinkUrl) }

            // Deep link: open once when the browser surface is ready.
            androidx.compose.runtime.LaunchedEffect(pendingDeepLink) {
                val url = pendingDeepLink
                if (url != null && screen == Screen.Browser) {
                    com.motion.browser.ServiceLocator.browser?.openUrl(url)
                    pendingDeepLink = null
                }
            }

            when (val current = screen) {
                Screen.Browser -> BrowserScreen(
                    onOpenControl = { screen = Screen.ControlCenter },
                    onOpenAi = { screen = Screen.AiChat },
                    onOpenTabSwitcher = { /* handled inside BrowserScreen now */ },
                    onOpenSettings = { screen = Screen.Settings },
                    onOpenBookmarks = { screen = Screen.Bookmarks },
                    onOpenHistory = { screen = Screen.History },
                    onOpenDownloads = { screen = Screen.Downloads },
                )
                Screen.ControlCenter -> ControlCenterScreen(
                    onBack = { screen = Screen.Browser }
                )
                Screen.GoalEditor -> GoalEditorScreen(
                    goalId = null,
                    onBack = { screen = Screen.ControlCenter }
                )
                Screen.Providers -> ProvidersScreen(onBack = { screen = Screen.ControlCenter })
                Screen.Logs -> LogsScreen(onBack = { screen = Screen.ControlCenter })
                Screen.Settings -> SettingsScreen(onBack = { screen = Screen.Browser })
                Screen.Bookmarks -> BookmarksScreen(
                    onBack = { screen = Screen.Browser },
                    onOpenUrl = { url ->
                        com.motion.browser.ServiceLocator.browser?.openUrl(url)
                        screen = Screen.Browser
                    }
                )
                Screen.History -> HistoryScreen(
                    onBack = { screen = Screen.Browser },
                    onOpenUrl = { url ->
                        com.motion.browser.ServiceLocator.browser?.openUrl(url)
                        screen = Screen.Browser
                    }
                )
                Screen.Downloads -> DownloadsScreen(onBack = { screen = Screen.Browser })
                Screen.AiChat -> ChatScreen(
                    onBack = { screen = Screen.Browser },
                    onOpenLink = { url ->
                        com.motion.browser.ServiceLocator.browser?.openUrl(url)
                        screen = Screen.Browser
                    },
                    initialText = ChatIntake.consume(),
                )
            }

            BackHandler(enabled = screen != Screen.Browser) {
                when {
                    showAiPanel -> showAiPanel = false
                    screen != Screen.Browser -> screen = Screen.Browser
                }
            }
        }
    }
}

/** Fast relative luminance on the resolved background color. */
private fun androidx.compose.ui.graphics.Color.luminanceSafe(): Float =
    0.2126f * red + 0.7152f * green + 0.0722f * blue
