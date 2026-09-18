package com.motion.browser.ui.browser

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddToHomeScreen
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.BookmarkAdded
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FindInPage
import androidx.compose.material.icons.filled.GTranslate
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Tab
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.motion.browser.ServiceLocator
import kotlinx.coroutines.launch

/** Menu items shown in the browser overflow sheet. */
internal data class MenuItem(
    val icon: ImageVector,
    val label: String,
    val action: () -> Unit,
    val enabled: Boolean = true,
)

/**
 * Browser menu v3 — discoverability-first restructure:
 *   1. MOTION AI section with two prominent cards (AI Chat, AI Agents)
 *   2. Settings row (prominent, with subtitle)
 *   3. Quick-access grid (new tab, incognito, bookmarks, history, downloads, …)
 *   4. Page actions (share, translate, print, save, info, site settings, shields)
 *   5. Help & feedback
 * Implemented as a ModalBottomSheet with grouped rows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserMenu(
    onDismiss: () -> Unit,
    onOpenControl: () -> Unit,
    onOpenAi: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenBookmarks: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenDownloads: () -> Unit,
    onShowFindBar: () -> Unit,
    onShowPageInfo: () -> Unit,
    onShowSiteSettings: () -> Unit,
) {
    val context = LocalContext.current
    val manager = ServiceLocator.tabs ?: return
    val controller = ServiceLocator.browser
    val scope = rememberCoroutineScope()
    val tabs by manager.tabs.collectAsState()
    val activeId by manager.activeTabId.collectAsState()
    val active = tabs.firstOrNull { it.id == activeId }
    val settings = ServiceLocator.settingsRepository.settings.collectAsState()
    var bookmarked by remember { mutableStateOf(false) }
    var desktop by remember { mutableStateOf(false) }

    LaunchedEffect(active?.url) {
        val url = active?.url
        bookmarked = if (url != null && url.startsWith("http")) {
            runCatching { controller?.isBookmarked(url) ?: false }.getOrDefault(false)
        } else false
        desktop = runCatching { manager.desktopSiteEnabled(activeId ?: "") }.getOrDefault(false)
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 8.dp)) {

            // ---- 1. MOTION AI — the two flagship surfaces, impossible to miss.
            Text(
                "MOTION AI",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 8.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AiCard(
                    icon = Icons.Filled.AutoAwesome,
                    title = "Motion AI Chat",
                    subtitle = "Ask, summarize, translate pages",
                    onClick = { onDismiss(); onOpenAi() },
                    modifier = Modifier.weight(1f),
                )
                AiCard(
                    icon = Icons.Filled.SmartToy,
                    title = "AI Agents",
                    subtitle = "Autonomous browsing tasks",
                    onClick = { onDismiss(); onOpenControl() },
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(8.dp))

            // ---- 2. Settings — prominent full-width row.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onDismiss(); onOpenSettings() }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Settings, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column(Modifier
                    .weight(1f)
                    .padding(start = 16.dp)) {
                    Text("Settings", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Theme, search engine, privacy, site settings, downloads",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    Icons.Filled.ChevronRight, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 6.dp))

            // ---- 3. Quick-access grid (4 tiles per row, like Chrome mobile).
            Row(Modifier.fillMaxWidth()) {
                GridTile(Icons.Filled.Add, "New tab") { onDismiss(); manager.createTab() }
                GridTile(Icons.Filled.VisibilityOff, "Incognito") {
                    onDismiss(); manager.createTab(isPrivate = true)
                }
                GridTile(Icons.Filled.BookmarkAdded, "Bookmarks") { onDismiss(); onOpenBookmarks() }
                GridTile(Icons.Filled.History, "History") { onDismiss(); onOpenHistory() }
            }
            Row(Modifier.fillMaxWidth()) {
                GridTile(Icons.Filled.Download, "Downloads") { onDismiss(); onOpenDownloads() }
                GridTile(
                    if (bookmarked) Icons.Filled.BookmarkAdded else Icons.Filled.BookmarkAdd,
                    if (bookmarked) "Bookmarked" else "Bookmark",
                    enabled = active?.url?.startsWith("http") == true,
                ) {
                    val url = active?.url ?: return@GridTile
                    scope.launch {
                        runCatching {
                            controller?.toggleBookmark(active?.title?.ifBlank { url } ?: url, url)
                        }
                        bookmarked = runCatching { controller?.isBookmarked(url) ?: false }.getOrDefault(false)
                    }
                }
                GridTile(Icons.Filled.FindInPage, "Find in page") { onDismiss(); onShowFindBar() }
                GridTile(Icons.Filled.DesktopWindows,
                    if (desktop) "Mobile site" else "Desktop site",
                ) {
                    val enabled = !desktop
                    desktop = enabled
                    runCatching { manager.setDesktopSite(activeId ?: "", enabled) }
                }
            }
            MenuRow(Icons.Filled.Tab, "Reopen closed tab") {
                onDismiss(); runCatching { manager.reopenClosed() }
            }

            HorizontalDivider(Modifier.padding(vertical = 6.dp))

            // ---- 4. Page actions.
            val canActOnPage = active?.url?.startsWith("http") == true
            MenuRow(Icons.Filled.Share, "Share", enabled = canActOnPage) {
                onDismiss()
                val url = active?.url ?: return@MenuRow
                runCatching {
                    context.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, url)
                                putExtra(Intent.EXTRA_SUBJECT, active.title.ifBlank { url })
                            },
                            "Share page"
                        )
                    )
                }
            }
            MenuRow(Icons.Filled.GTranslate, "Translate with AI", enabled = canActOnPage) {
                onDismiss(); onOpenAi()
            }
            MenuRow(Icons.Filled.AddToHomeScreen, "Add to home screen", enabled = canActOnPage) {
                onDismiss()
                runCatching {
                    addToHomeScreen(context, active?.url ?: "", active?.title?.ifBlank { "Motion" } ?: "Motion")
                }
            }
            MenuRow(Icons.Filled.Print, "Print", enabled = canActOnPage) {
                onDismiss()
                runCatching {
                    val wv = manager.getWebView(activeId ?: "") ?: return@MenuRow
                    val pm = context.getSystemService(android.print.PrintManager::class.java)
                    pm.print(active?.title?.ifBlank { "Motion page" } ?: "Motion page", wv.createPrintDocumentAdapter("motion-doc"), null)
                }
            }
            MenuRow(Icons.Filled.Save, "Save page (MHTML)", enabled = canActOnPage) {
                onDismiss()
                runCatching {
                    val wv = manager.getWebView(activeId ?: "") ?: return@MenuRow
                    wv.saveWebArchive(
                        java.io.File(
                            context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOCUMENTS),
                            (active?.title?.ifBlank { "page" } ?: "page").replace(Regex("[^A-Za-z0-9_. -]"), "").take(60) + ".mht"
                        ).absolutePath
                    )
                }
            }
            MenuRow(Icons.Filled.Info, "Page information", enabled = canActOnPage) {
                onDismiss(); onShowPageInfo()
            }
            MenuRow(Icons.Filled.Security, "Site settings") {
                onDismiss(); onShowSiteSettings()
            }
            MenuRow(
                Icons.Filled.Shield,
                if (settings.value.shieldsEnabled) "Shields: ON" else "Shields: OFF",
            ) {
                scope.launch {
                    runCatching {
                        ServiceLocator.settingsRepository.setShieldsEnabled(!settings.value.shieldsEnabled)
                    }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 6.dp))

            // ---- 5. Help & feedback.
            MenuRow(Icons.Filled.AutoAwesome, "Help & feedback") {
                onDismiss(); onOpenAi()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AiCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 6.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Square quick-access tile with icon above a label (Chrome mobile menu style). */
@Composable
internal fun RowScope.GridTile(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
    else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
    Column(
        modifier = Modifier
            .weight(1f)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = tint)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
internal fun MenuRow(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon, contentDescription = null,
            tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
        )
        Text(
            label,
            modifier = Modifier.padding(start = 16.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        )
    }
}

/** Creates a Pinned Shortcut (API 26+) pointing at the current URL. */
private fun addToHomeScreen(context: android.content.Context, url: String, title: String) {
    if (url.isBlank()) return
    val shortcutManager = context.getSystemService(android.content.pm.ShortcutManager::class.java)
        ?: return
    if (shortcutManager.isRequestPinShortcutSupported) {
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).apply {
            setPackage(context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val shortcut = android.content.pm.ShortcutInfo.Builder(context, "page_" + url.hashCode())
            .setShortLabel(title.take(30))
            .setIntent(intent)
            .build()
        runCatching {
            shortcutManager.requestPinShortcut(shortcut, null)
        }
    }
}
