package com.motion.browser.ui.browser

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.motion.browser.ServiceLocator
import com.motion.browser.browser.NEW_TAB_URL
import com.motion.browser.data.entity.HistoryEntity
import com.motion.browser.data.entity.BookmarkEntity
import com.motion.browser.shields.ShieldsEngine
import kotlinx.coroutines.launch

/**
 * Omnibox v2 (Chrome-mobile style): secure indicator + URL field with live
 * suggestions (bookmarks + history + search) + copy/share + overflow menu.
 * Committing text navigates; plain search terms use the default search engine
 * via [com.motion.browser.data.SettingsRepository.searchUrlFor].
 */
@Composable
fun Omnibox(
    onOpenControl: () -> Unit,
    onOpenMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val manager = ServiceLocator.tabs ?: return
    val controller = ServiceLocator.browser ?: return
    val settingsRepo = ServiceLocator.settingsRepository
    val tabs by manager.tabs.collectAsState()
    val activeId by manager.activeTabId.collectAsState()
    val active = tabs.firstOrNull { it.id == activeId }
    val engineState = manager.engineForActive()?.state?.collectAsState()?.value

    var editing by remember(activeId) { mutableStateOf(false) }
    var draft by remember(activeId) { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    val bookmarks by ServiceLocator.database.bookmarkDao().allFlat().collectAsState(initial = emptyList())
    val history by ServiceLocator.database.historyDao().recent(80).collectAsState(initial = emptyList())

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
                value = if (editing) draft else displayUrl(active?.url),
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                ),
                textStyle = MaterialTheme.typography.bodyMedium,
                leadingIcon = {
                    val secure = (active?.url ?: "").startsWith("https://")
                    Icon(
                        imageVector = if (secure) Icons.Filled.Lock else Icons.Outlined.LockOpen,
                        contentDescription = if (secure) "Secure connection (HTTPS)" else "Not secure",
                        tint = if (secure) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                },
                placeholder = {
                    Text("Search or enter address", style = MaterialTheme.typography.bodyMedium)
                },
                keyboardOptions = KeyboardOptions(
                    imeAction = androidx.compose.ui.text.input.ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(onGo = {
                    editing = false
                    val target = draft.trim()
                    if (target.isNotBlank()) {
                        scope.launch { controller.openUrl(resolveTarget(target, settingsRepo)) }
                        draft = ""
                    }
                }),
            )
            ShieldsBadge()
            IconButton(onClick = onOpenMenu) {
                Icon(Icons.Filled.MoreVert, contentDescription = "Menu")
            }
        }
        if (engineState?.isLoading == true) {
            LinearProgressIndicator(
                progress = { (engineState.progress / 100f).coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        }
        if (editing) {
            SuggestionList(
                query = draft,
                bookmarks = bookmarks,
                history = history,
                onOpen = { target ->
                    editing = false
                    draft = ""
                    scope.launch { controller.openUrl(target) }
                },
                onSearch = { term ->
                    editing = false
                    draft = ""
                    scope.launch { controller.openUrl(settingsRepo.searchUrlFor(term)) }
                },
            )
        }
        // Copy-URL quick affordance (long list of share options lives in the menu).
        if (!editing && active?.url?.startsWith("http") == true) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = active.title.ifBlank { displayUrl(active.url) },
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { editing = true; draft = displayUrl(active.url) },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(onClick = {
                    clipboard.setText(AnnotatedString(active.url))
                    Toast.makeText(context, "URL copied", Toast.LENGTH_SHORT).show()
                }) {
                    Icon(
                        Icons.Filled.ContentCopy, contentDescription = "Copy URL",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun displayUrl(url: String?): String =
    if (url == null || url == NEW_TAB_URL || url.startsWith("about:")) "" else url

/** Chrome-like resolution: URL-ish input navigates, everything else searches. */
internal fun resolveTarget(input: String, settingsRepo: com.motion.browser.data.SettingsRepository): String {
    val trimmed = input.trim()
    val looksLikeUrl =
        trimmed.startsWith("http://") || trimmed.startsWith("https://") ||
            trimmed.matches(Regex("^[a-z0-9-]+(\\.[a-z0-9-]+)+(:\\d+)?(/.*)?$", RegexOption.IGNORE_CASE))
    return if (looksLikeUrl) {
        if (trimmed.startsWith("http")) trimmed else "https://$trimmed"
    } else {
        settingsRepo.searchUrlFor(trimmed)
    }
}

@Composable
private fun SuggestionList(
    query: String,
    bookmarks: List<BookmarkEntity>,
    history: List<HistoryEntity>,
    onOpen: (String) -> Unit,
    onSearch: (String) -> Unit,
) {
    val q = query.trim()
    if (q.isEmpty()) return
    val bookmarkHits = bookmarks.filter { it.title.contains(q, true) || it.url.contains(q, true) }.take(3)
    val historyHits = history.filter { it.title.contains(q, true) || it.url.contains(q, true) }.take(3)
    val seen = bookmarkHits.map { it.url }.toSet()
    val historyDedup = historyHits.filter { it.url !in seen }

    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth()) {
            SuggestionRow(Icons.Filled.Search, "Search \"$q\"") { onSearch(q) }
            bookmarkHits.forEach { b ->
                SuggestionRow(Icons.Filled.Star, b.title, subtitle = b.url) { onOpen(b.url) }
            }
            historyDedup.forEach { h ->
                SuggestionRow(Icons.Filled.History, h.title, subtitle = h.url) { onOpen(h.url) }
            }
        }
    }
}

@Composable
private fun SuggestionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon, contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Column(Modifier
            .weight(1f)
            .padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(
                    subtitle, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Shields indicator/toggle in the omnibox: shield icon shows the anti-tracking
 * state; a badge carries the session blocked-request count (0 → no badge).
 */
@Composable
private fun ShieldsBadge() {
    val shieldsOn by com.motion.browser.shields.ShieldsState.enabled.collectAsState()
    val blocked by ShieldsEngine.blockedTotal.collectAsState()
    val scope = rememberCoroutineScope()
    IconButton(onClick = {
        scope.launch {
            runCatching { ServiceLocator.settingsRepository.setShieldsEnabled(!shieldsOn) }
        }
    }) {
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
