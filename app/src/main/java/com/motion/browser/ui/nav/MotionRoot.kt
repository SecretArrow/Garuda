package com.motion.browser.ui.nav

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.motion.browser.ui.ai.AiPanel
import com.motion.browser.ui.browser.BrowserScreen
import com.motion.browser.ui.control.ControlCenterScreen
import com.motion.browser.ui.control.GoalEditorScreen
import com.motion.browser.ui.control.LogsScreen
import com.motion.browser.ui.control.ProvidersScreen
import com.motion.browser.ui.theme.MotionTheme

/** Simple screen stack (no navigation dependency) — coordinator-owned wiring surface. */
sealed class Screen {
    data object Browser : Screen()
    data object ControlCenter : Screen()
    data object GoalEditor : Screen()
    data object Providers : Screen()
    data object Logs : Screen()
}

/**
 * Root composable hosting the whole app (ARCHITECTURE.md §3.7).
 *
 * @param onDeepLinkUrl optional URL from an ACTION_VIEW intent, opened once on start.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MotionRoot(onDeepLinkUrl: String? = null) {
    MotionTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            var screen by remember { mutableStateOf<Screen>(Screen.Browser) }
            var showAiPanel by remember { mutableStateOf(false) }
            var showTabSwitcher by remember { mutableStateOf(false) }
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
                    onOpenAi = { showAiPanel = true },
                    onOpenTabSwitcher = { showTabSwitcher = true }
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
            }

            // AI panel overlays every screen (spec §52/§73: Ask Motion anywhere).
            if (showAiPanel) {
                Box(Modifier.fillMaxSize()) {
                    AiPanel(onDismiss = { showAiPanel = false })
                }
            }

            BackHandler(enabled = screen != Screen.Browser || showAiPanel) {
                when {
                    showAiPanel -> showAiPanel = false
                    screen != Screen.Browser -> screen = Screen.Browser
                }
            }
        }
    }
}
