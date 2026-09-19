package com.garuda.browser.ui

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
import com.garuda.browser.ChatIntake
import com.garuda.browser.ServiceLocator
import com.garuda.browser.agent.runtime.ChatBus
import com.garuda.browser.agent.runtime.GarudaAgentService
import com.garuda.browser.ui.theme.GarudaTheme

/** Root navigation (plan Prompt 7): Browser · Dashboard · Settings + ChatDrawer. */
sealed class GarudaScreen(val route: String) {
    data object Browser : GarudaScreen("browser")
    data object Dashboard : GarudaScreen("dashboard")
    data object Settings : GarudaScreen("settings")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GarudaApp() {
    val context = LocalContext.current
    GarudaTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            var screen by remember { mutableStateOf<GarudaScreen>(GarudaScreen.Browser) }
            var showChat by remember { mutableStateOf(false) }
            var chatSeed by remember { mutableStateOf<String?>(null) }

            // Pending share/selection text becomes the chat drawer's seed input.
            androidx.compose.runtime.LaunchedEffect(Unit) {
                ChatIntake.consume()?.let { chatSeed = it; showChat = true }
            }

            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("Garuda", style = MaterialTheme.typography.titleLarge) },
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
                        GarudaScreen.Browser -> BrowserScreen(Modifier.fillMaxSize())
                        GarudaScreen.Dashboard -> DashboardScreen(Modifier.fillMaxSize())
                        GarudaScreen.Settings -> SettingsScreen(Modifier.fillMaxSize())
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

private fun navItems(): List<Triple<GarudaScreen, ImageVector, String>> = listOf(
    Triple(GarudaScreen.Browser, Icons.Filled.Home, "Browser"),
    Triple(GarudaScreen.Dashboard, Icons.Filled.Extension, "Tasks"),
    Triple(GarudaScreen.Settings, Icons.Filled.Settings, "Settings"),
)

@Composable
private fun runningTaskCount(): Int {
    val tasks by ServiceLocator.database.taskDao().all().collectAsState(initial = emptyList())
    return tasks.count { it.status == "RUNNING" || it.status == "QUEUED" || it.status == "PLANNING" }
}
