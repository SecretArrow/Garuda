package com.motion.browser.ui.browser

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tab
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.motion.browser.ServiceLocator
import com.motion.browser.browser.NEW_TAB_URL
import com.motion.browser.browser.engine.CustomViewHost
import kotlinx.coroutines.launch

/**
 * Main browser screen v2 — Chrome-tabstrip layout:
 *   [1] horizontal tab strip   [2] omnibox row   [3] web content   [4] bottom bar
 *
 * Extras wired here: pull-to-refresh, find-in-page bar, HTML5 fullscreen video
 * overlay (CustomViewHost), main-frame error card, page info + site settings
 * dialogs and the full [BrowserMenu] sheet.
 *
 * The active tab's real WebView is hosted via AndroidView keyed by tab id so
 * the engine instance survives recomposition and page state survives switches.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    onOpenControl: () -> Unit,
    onOpenAi: () -> Unit,
    onOpenTabSwitcher: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenBookmarks: () -> Unit = {},
    onOpenHistory: () -> Unit = {},
    onOpenDownloads: () -> Unit = {},
) {
    val manager = ServiceLocator.tabs
    val controller = ServiceLocator.browser
    if (manager == null || controller == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Starting Motion Browser…")
        }
        return
    }

    var showSwitcher by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showFind by remember { mutableStateOf(false) }
    var showPageInfo by remember { mutableStateOf(false) }
    var showSiteSettings by remember { mutableStateOf(false) }

    val tabs by manager.tabs.collectAsState()
    val activeId by manager.activeTabId.collectAsState()
    val active = tabs.firstOrNull { it.id == activeId }
    val containerView = activeId?.let { manager.getContainerView(it) }
    val scope = rememberCoroutineScope()

    val fullscreenView by CustomViewHost.view.collectAsState()
    BackHandler(enabled = fullscreenView != null) { CustomViewHost.exit() }

    // Web back takes priority over leaving the app; other overlays are handled
    // by MotionRoot (its BackHandler is registered later, so it wins when enabled).
    BackHandler(enabled = (active?.canGoBack == true) && fullscreenView == null) {
        controller.goBack()
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // [1] tab strip
            TabStrip()
            // [2] omnibox row
            Omnibox(
                onOpenControl = onOpenControl,
                onOpenMenu = { showMenu = true },
            )

            // [3] content
            Box(Modifier.weight(1f)) {
                PullToRefreshBox(
                    isRefreshing = (active?.isLoading == true),
                    onRefresh = { controller.reload() },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (active == null || active.url == NEW_TAB_URL) {
                        NewTabPage(onOpenAi = onOpenAi, modifier = Modifier.fillMaxSize())
                    }
                    if (containerView != null && active != null && active.url != NEW_TAB_URL) {
                        key(active.id) {
                            AndroidView(
                                factory = { containerView },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
                // Main-frame error card (real load errors, dismissible via retry)
                if (active != null && !active.isLoading && active.url != NEW_TAB_URL) {
                    active.lastError?.let { error ->
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .padding(12.dp),
                        ) {
                            Row(
                                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "Page failed to load: $error",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = { controller.reload() }) { Text("Retry") }
                            }
                        }
                    }
                }
                if (active == null) {
                    Text(
                        text = "No tabs open",
                        modifier = Modifier.align(Alignment.Center),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            // Find-in-page sits above the bottom bar (Chrome parity).
            if (showFind) {
                FindInPageBar(onDismiss = { showFind = false })
            }

            // [4] bottom bar
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        enabled = active?.canGoBack == true,
                        onClick = { controller.goBack() },
                    ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                    IconButton(
                        enabled = active?.canGoForward == true,
                        onClick = { controller.goForward() },
                    ) { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Forward") }
                    IconButton(onClick = {
                        val home = ServiceLocator.settingsRepository.current.homepage
                        scope.launch {
                            runCatching {
                                controller.openUrl(
                                    home.ifBlank { NEW_TAB_URL },
                                    waitForLoad = home != NEW_TAB_URL && home.startsWith("http"),
                                )
                            }
                        }
                    }) { Icon(Icons.Filled.Home, contentDescription = "Home") }

                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        ModeChip()
                    }

                    if (active?.isPrivate == true) {
                        Icon(
                            Icons.Filled.VisibilityOff, contentDescription = "Incognito tab",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(end = 4.dp)
                                .size(18.dp),
                        )
                    }
                    IconButton(onClick = { showSwitcher = true }) {
                        Icon(Icons.Filled.Tab, contentDescription = "Tab switcher")
                        Text(" ${manager.openCount}")
                    }
                    IconButton(onClick = onOpenAi) {
                        Icon(
                            Icons.Filled.AutoAwesome, contentDescription = "Motion AI",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }

        // HTML5 fullscreen video overlay (custom view from WebChromeClient).
        fullscreenView?.let { customView ->
            Surface(Modifier.fillMaxSize(), color = androidx.compose.ui.graphics.Color.Black) {
                AndroidView(factory = { customView }, modifier = Modifier.fillMaxSize())
            }
        }
    }

    if (showSwitcher) {
        TabSwitcherSheet(onDismiss = { showSwitcher = false })
    }
    if (showMenu) {
        BrowserMenu(
            onDismiss = { showMenu = false },
            onOpenControl = onOpenControl,
            onOpenAi = onOpenAi,
            onOpenSettings = onOpenSettings,
            onOpenBookmarks = onOpenBookmarks,
            onOpenHistory = onOpenHistory,
            onOpenDownloads = onOpenDownloads,
            onShowFindBar = { showFind = true },
            onShowPageInfo = { showPageInfo = true },
            onShowSiteSettings = { showSiteSettings = true },
        )
    }
    if (showPageInfo) {
        PageInfoDialog(active?.url ?: "", active?.title ?: "", onDismiss = { showPageInfo = false })
    }
    if (showSiteSettings) {
        SitePermissionsDialog(onDismiss = { showSiteSettings = false })
    }

    // SAF file chooser bridge for real website uploads (spec §43).
    FileChooserHost()
}

@Composable
private fun PageInfoDialog(url: String, title: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Page information") },
        text = {
            Column {
                Text("Title", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(title.ifBlank { "—" }, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Address", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Text(url.ifBlank { "—" }, style = MaterialTheme.typography.bodySmall)
                Text(
                    "Connection",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Text(
                    if (url.startsWith("https://")) "Secure (HTTPS)" else "Not secure",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (url.startsWith("https://")) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun SitePermissionsDialog(onDismiss: () -> Unit) {
    val settingsRepo = ServiceLocator.settingsRepository
    val settings by settingsRepo.settings.collectAsState()
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Site settings") },
        text = {
            Column {
                Text(
                    "Defaults applied to websites requesting device capabilities:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PermissionToggle("Camera", settings.siteCamera) {
                    scope.launch { runCatching { settingsRepo.setSiteCamera(it) } }
                }
                PermissionToggle("Microphone", settings.siteMicrophone) {
                    scope.launch { runCatching { settingsRepo.setSiteMicrophone(it) } }
                }
                PermissionToggle("Location", settings.siteLocation) {
                    scope.launch { runCatching { settingsRepo.setSiteLocation(it) } }
                }
                PermissionToggle("Notifications", settings.siteNotifications) {
                    scope.launch { runCatching { settingsRepo.setSiteNotifications(it) } }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun PermissionToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        androidx.compose.material3.Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Manual / Copilot / Autonomous indicator (spec §54) from the live runtime status. */
@Composable
private fun ModeChip(modifier: Modifier = Modifier) {
    val status by ServiceLocator.agentRuntime.status.collectAsState()
    val label = when (status) {
        is com.motion.browser.agent.runtime.RuntimeStatus.Running -> "AI RUNNING"
        is com.motion.browser.agent.runtime.RuntimeStatus.WaitingApproval -> "AI NEEDS APPROVAL"
        is com.motion.browser.agent.runtime.RuntimeStatus.Paused -> "AI PAUSED"
        else -> "MANUAL"
    }
    Text(
        text = label,
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
