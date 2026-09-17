package com.motion.browser.ui.browser

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Tab
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.motion.browser.ServiceLocator
import com.motion.browser.browser.NEW_TAB_URL

/**
 * Main browser screen — Chrome-tabstrip layout (user decision):
 *   [1] horizontal tab strip   [2] omnibox row   [3] web content   [4] bottom bar
 *
 * The active tab's real WebView is hosted via AndroidView keyed by tab id so the
 * engine instance survives recomposition and page state survives tab switches.
 */
@Composable
fun BrowserScreen(
    onOpenControl: () -> Unit,
    onOpenAi: () -> Unit,
    onOpenTabSwitcher: () -> Unit
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

    Column(Modifier.fillMaxSize()) {
        // [1] tab strip
        TabStrip()
        // [2] omnibox row
        Omnibox(onOpenControl = onOpenControl)

        // [3] content
        val tabs by manager.tabs.collectAsState()
        val activeId by manager.activeTabId.collectAsState()
        val active = tabs.firstOrNull { it.id == activeId }
        val containerView = activeId?.let { manager.getContainerView(it) }

        Box(Modifier.weight(1f)) {
            if (active == null || active.url == NEW_TAB_URL) {
                NewTabPage(onOpenAi = onOpenAi, modifier = Modifier.fillMaxSize())
            }
            if (containerView != null && active != null && active.url != NEW_TAB_URL) {
                key(active.id) {
                    AndroidView(
                        factory = { containerView },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
            if (active == null) {
                Text(
                    text = "No tabs open",
                    modifier = Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        // [4] bottom bar
        Surface(color = MaterialTheme.colorScheme.surface) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onOpenTabSwitcher) {
                    Icon(Icons.Filled.Tab, contentDescription = null)
                    Text(" ${manager.openCount}")
                }
                Box(Modifier.weight(1f))
                ModeChip(modifier = Modifier.padding(end = 4.dp))
                TextButton(onClick = onOpenAi) {
                    Icon(Icons.Filled.AutoAwesome, contentDescription = null)
                    Text(" Motion AI")
                }
            }
        }
    }

    if (showSwitcher) {
        TabSwitcherSheet(onDismiss = { showSwitcher = false })
    }

    // SAF file chooser bridge for real website uploads (spec §43).
    FileChooserHost()
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
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
