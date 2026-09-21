package com.motion.browser.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import com.motion.browser.ChatIntake
import com.motion.browser.ServiceLocator
import com.motion.browser.agent.runtime.ChatBus
import com.motion.browser.agent.runtime.MotionAgentService
import com.motion.browser.ui.theme.MotionTheme

/** Root navigation (plan Prompt 7): Browser · Dashboard · Settings + ChatDrawer. */
sealed class MotionScreen(val route: String) {
    data object Browser : MotionScreen("browser")
    data object Dashboard : MotionScreen("dashboard")
    data object Settings : MotionScreen("settings")
    data object Bookmarks : MotionScreen("bookmarks")
    data object History : MotionScreen("history")
    data object Downloads : MotionScreen("downloads")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MotionBrowserApp() {
    val context = LocalContext.current
    MotionTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            var screen by remember { mutableStateOf<MotionScreen>(MotionScreen.Browser) }
            var showChat by remember { mutableStateOf(false) }
            var chatSeed by remember { mutableStateOf<String?>(null) }

            // Pending share/selection text becomes the chat drawer's seed input.
            androidx.compose.runtime.LaunchedEffect(Unit) {
                ChatIntake.consume()?.let { chatSeed = it; showChat = true }
            }

            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("Motion Browser", style = MaterialTheme.typography.titleLarge) },
                        actions = {
                            IconButton(onClick = { showChat = true }) {
                                BadgedBox(badge = {
                                    val running = runningTaskCount()
                                    if (running > 0) Badge { Text(running.toString()) }
                                }) {
                                    Icon(Icons.Filled.SmartToy, contentDescription = "Agent chat")
                                }
                            }
                        },
                    )
                },
                bottomBar = {
                    NavigationBar {
                        navItems().forEach { (screenItem, icon, label) ->
                            NavigationBarItem(
                                selected = screen == screenItem,
                                onClick = { screen = screenItem },
                                icon = { Icon(icon, contentDescription = label) },
                                label = { Text(label) },
                            )
                        }
                    }
                },
            ) { padding ->
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                ) {
                    when (screen) {
                        MotionScreen.Browser ->
                            BrowserScreen(Modifier.fillMaxSize(), onNavigate = { screen = it })
                        MotionScreen.Dashboard -> DashboardScreen(Modifier.fillMaxSize())
                        MotionScreen.Settings -> SettingsScreen(Modifier.fillMaxSize())
                        MotionScreen.Bookmarks -> BookmarksScreen(onOpenUrl = { url ->
                            openUrl(url); screen = MotionScreen.Browser
                        })
                        MotionScreen.History -> HistoryScreen(onOpenUrl = { url ->
                            openUrl(url); screen = MotionScreen.Browser
                        })
                        MotionScreen.Downloads -> DownloadsScreen(Modifier.fillMaxSize())
                    }
                }
            }

            if (showChat) {
                ChatDrawer(
                    seedText = chatSeed,
                    onDismiss = { showChat = false; chatSeed = null },
                )
            }

            ApprovalDialogs()
        }
    }
}

private fun navItems(): List<Triple<MotionScreen, ImageVector, String>> = listOf(
    Triple(MotionScreen.Browser, Icons.Filled.Home, "Browser"),
    Triple(MotionScreen.Dashboard, Icons.Filled.Extension, "Tasks"),
    Triple(MotionScreen.Settings, Icons.Filled.Settings, "Settings"),
)

/** Opens [url] in the active browser tab (or a fresh one). */
private fun openUrl(url: String) {
    val engine = ServiceLocator.browser
    val tab = engine.activeTab
    if (tab == null) engine.createTab(url)
    else tab.webView.loadUrl(com.motion.browser.ui.normalizeUrl(url))
}

@Composable
private fun runningTaskCount(): Int {
    val tasks by ServiceLocator.database.taskDao().all().collectAsState(initial = emptyList())
    return tasks.count { it.status == "RUNNING" || it.status == "QUEUED" || it.status == "PLANNING" }
}
