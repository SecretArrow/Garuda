package com.motion.browser.ui.browser

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.motion.browser.ServiceLocator
import com.motion.browser.browser.NEW_TAB_URL
import com.motion.browser.shields.ShieldsEngine
import com.motion.browser.shields.ShieldsState
import kotlinx.coroutines.launch

/**
 * Omnibox row (Chrome-tabstrip layout): back / forward / reload-stop + editable
 * URL field + overflow menu. Committing text navigates; plain search terms go
 * through the default engine (handled by BrowserController.normalizeUrl).
 */
@Composable
fun Omnibox(
    onOpenControl: () -> Unit,
    modifier: Modifier = Modifier
) {
    val manager = ServiceLocator.tabs ?: return
    val controller = ServiceLocator.browser ?: return
    val tabs by manager.tabs.collectAsState()
    val activeId by manager.activeTabId.collectAsState()
    val active = tabs.firstOrNull { it.id == activeId }
    val engineState = manager.engineForActive()?.state?.collectAsState()?.value

    var editing by remember(activeId) { mutableStateOf(false) }
    var draft by remember(activeId) { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    androidx.compose.foundation.layout.Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                enabled = active?.canGoBack == true,
                onClick = { controller.goBack() }
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            IconButton(
                enabled = active?.canGoForward == true,
                onClick = { controller.goForward() }
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Forward")
            }
            if (engineState?.isLoading == true) {
                IconButton(onClick = { controller.stopLoading() }) {
                    Icon(Icons.Filled.Close, contentDescription = "Stop loading")
                }
            } else {
                IconButton(onClick = { controller.reload() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Reload")
                }
            }
            OutlinedTextField(
                value = if (editing) draft else (active?.url?.takeIf { it != NEW_TAB_URL } ?: ""),
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                placeholder = { Text("Search or enter address", style = MaterialTheme.typography.bodySmall) },
                keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = {
                    editing = false
                    val target = draft
                    if (target.isNotBlank()) {
                        scope.launch { controller.openUrl(target) }
                        draft = ""
                    }
                })
            )
            ShieldsBadge()
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "Menu")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("New tab") },
                    onClick = { menuOpen = false; manager.createTab() }
                )
                DropdownMenuItem(
                    text = { Text("New private tab") },
                    onClick = { menuOpen = false; manager.createTab(isPrivate = true) }
                )
                DropdownMenuItem(
                    text = { Text("Bookmark this page") },
                    onClick = {
                        menuOpen = false
                        val url = active?.url ?: return@DropdownMenuItem
                        val title = active.title.ifBlank { url }
                        if (url.startsWith("http")) {
                            scope.launch { controller.saveBookmark(title, url) }
                        }
                    }
                )
                DropdownMenuItem(
                    text = { Text("Motion Control Center") },
                    onClick = { menuOpen = false; onOpenControl() }
                )
                DropdownMenuItem(
                    text = { Text("Shields: " + if (ShieldsState.enabled.value) "ON" else "OFF") },
                    onClick = { ShieldsState.enabled.value = !ShieldsState.enabled.value }
                )
                DropdownMenuItem(
                    text = { Text("Block 3rd-party cookies: " + if (ShieldsState.blockThirdPartyCookies.value) "ON" else "OFF") },
                    onClick = { ShieldsState.blockThirdPartyCookies.value = !ShieldsState.blockThirdPartyCookies.value }
                )
            }
        }
        if (engineState?.isLoading == true) {
            LinearProgressIndicator(
                progress = { (engineState.progress / 100f).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * Shields indicator/toggle in the omnibox: shield icon shows the anti-tracking
 * state; a badge carries the session blocked-request count (0 → no badge).
 */
@Composable
private fun ShieldsBadge() {
    val shieldsOn by ShieldsState.enabled.collectAsState()
    val blocked by ShieldsEngine.blockedTotal.collectAsState()
    IconButton(onClick = { ShieldsState.enabled.value = !ShieldsState.enabled.value }) {
        BadgedBox(
            badge = {
                if (shieldsOn && blocked > 0) {
                    Badge { Text(blocked.toString()) }
                }
            }
        ) {
            Icon(
                imageVector = when {
                    shieldsOn -> Icons.Filled.Shield
                    else -> Icons.Outlined.Shield
                },
                contentDescription = if (shieldsOn) "Shields on — tap to disable" else "Shields off — tap to enable",
                tint = if (shieldsOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
