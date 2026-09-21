package com.motion.browser.ui

import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FindInPage
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Tab
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.motion.browser.ServiceLocator
import com.motion.browser.browser.BrowserEngine
import com.motion.browser.data.BrowserData
import com.motion.browser.data.BookmarkEntity
import com.motion.browser.data.HistoryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Browser shell v2 (full-featured phase): tab strip, omnibox with bookmark /
 * history / search suggestions, load progress, error card + retry, pull to
 * refresh, find-in-page, tab switcher, bottom navigation and a Chrome-style
 * menu. The agent layer attaches to the active WebView via the DevTools socket.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(modifier: Modifier = Modifier, onNavigate: (MotionScreen) -> Unit = {}) {
    val engine = ServiceLocator.browser
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var showTabs by remember { mutableStateOf(false) }
    var showFind by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    if (engine.tabs.isEmpty()) engine.createTab(BrowserEngine.HOME_URL)
    val active = engine.activeTab ?: return

    // WebView state (progress/title/error) is not Compose state — poll lightly.
    LaunchedEffect(active.id) {
        while (true) {
            refresh++
            delay(300)
        }
    }

    Column(modifier.fillMaxSize()) {
        TabStrip(engine, refresh,
            onSwitch = { engine.switchTo(it); refresh++ },
            onNew = { engine.createTab(BrowserEngine.HOME_URL); refresh++ },
        )

        Omnibox(
            engine = engine, active = active, refresh = refresh, menuOpen = menuOpen,
            onMenuToggle = { menuOpen = !menuOpen },
            onDismissMenu = { menuOpen = false },
            onNavigate = onNavigate,
            onReload = { active.webView.reload(); refresh++ },
            onCloseTab = {
                engine.closeTab(engine.activeIndex); refresh++
            },
            onShowFind = { showFind = true },
        )

        if (active.progress in 1..99) {
            LinearProgressIndicator(
                progress = { active.progress / 100f },
                modifier = Modifier.fillMaxWidth().height(2.dp),
            )
        }

        if (showFind) {
            FindInPageBar(webView = active.webView, onDismiss = { showFind = false })
        }

        Box(Modifier.fillMaxWidth().weight(1f)) {
            var pulling by remember { mutableStateOf(false) }
            PullToRefreshBox(
                isRefreshing = pulling,
                onRefresh = {
                    pulling = true
                    active.webView.reload()
                    scope.launch(Dispatchers.Main) {
                        while (active.progress in 1..99) delay(200)
                        pulling = false
                    }
                },
                modifier = Modifier.fillMaxSize(),
            ) {
                val isError = active.lastError != null &&
                    active.webView.url.orEmpty().startsWith("http")
                if (active.webView.url == "about:blank") {
                    NewTabPage(onOpenUrl = { url ->
                        active.webView.loadUrl(normalizeUrl(url)); refresh++
                    })
                } else {
                    key(active.id) {
                        AndroidView(
                            factory = { _ ->
                                active.webView.apply {
                                    parent?.let { (it as ViewGroup).removeView(this) }
                                    layoutParams = ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                if (isError) {
                    ErrorCard(
                        error = active.lastError.orEmpty(),
                        url = active.webView.url.orEmpty(),
                        onRetry = { active.lastError = null; active.webView.reload(); refresh++ },
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    )
                }
            }
        }

        BottomBar(
            engine = engine,
            onBack = { active.webView.goBack(); refresh++ },
            onForward = { active.webView.goForward(); refresh++ },
            onHome = {
                active.webView.loadUrl(BrowserEngine.HOME_URL); refresh++
            },
            onTabs = { showTabs = true },
            onMenu = { menuOpen = true },
        )
    }

    if (showTabs) {
        TabSwitcherSheet(
            engine = engine,
            onSelect = { engine.switchTo(it); refresh++; showTabs = false },
            onClose = { engine.closeTab(it); refresh++ },
            onNew = {
                engine.createTab(BrowserEngine.HOME_URL); refresh++; showTabs = false
            },
            onDismiss = { showTabs = false },
        )
    }
}

@Composable
private fun TabStrip(
    engine: BrowserEngine,
    refresh: Int,
    onSwitch: (Int) -> Unit,
    onNew: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        engine.tabs.forEachIndexed { index, tab ->
            AssistChip(
                onClick = { onSwitch(index) },
                label = {
                    Text(
                        (tab.title.ifBlank { "Tab ${index + 1}" }).take(18),
                        color = if (index == engine.activeIndex) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                    )
                },
                modifier = Modifier.padding(end = 6.dp),
            )
        }
        IconButton(onClick = onNew) {
            Icon(Icons.Filled.Add, contentDescription = "New tab")
        }
    }
}

@Composable
private fun Omnibox(
    engine: BrowserEngine,
    active: com.motion.browser.browser.MotionTab,
    refresh: Int,
    menuOpen: Boolean,
    onMenuToggle: () -> Unit,
    onDismissMenu: () -> Unit,
    onNavigate: (MotionScreen) -> Unit,
    onReload: () -> Unit,
    onCloseTab: () -> Unit,
    onShowFind: () -> Unit,
) {
    var urlDraft by remember(active.id, refresh) {
        mutableStateOf(if (active.webView.url == "about:blank") "" else active.webView.url.orEmpty())
    }
    var suggestions by remember { mutableStateOf(false) }
    val query = urlDraft.trim()

    OutlinedTextField(
        value = urlDraft,
        onValueChange = { urlDraft = it; suggestions = it.isNotBlank() },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        singleLine = true,
        shape = RoundedCornerShape(24.dp),
        placeholder = { Text("Search or enter address") },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = {
            val target = normalizeUrl(urlDraft)
            urlDraft = target
            active.webView.loadUrl(target)
            suggestions = false
        }),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            focusedBorderColor = Color.Transparent,
            unfocusedBorderColor = Color.Transparent,
        ),
        leadingIcon = {
            if (active.incognito) {
                Icon(Icons.Filled.VisibilityOff, contentDescription = "Incognito",
                    tint = MaterialTheme.colorScheme.secondary)
            } else {
                Icon(
                    if ((active.webView.url ?: "").startsWith("https://")) Icons.Filled.Lock
                    else Icons.Filled.Language,
                    contentDescription = null,
                )
            }
        },
        trailingIcon = {
            Row {
                IconButton(onClick = onReload) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Reload")
                }
                Box {
                    IconButton(onClick = onMenuToggle) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Menu")
                    }
                    BrowserMenu(
                        expanded = menuOpen,
                        engine = engine,
                        active = active,
                        onDismiss = onDismissMenu,
                        onNavigate = onNavigate,
                        onCloseTab = onCloseTab,
                        onShowFind = onShowFind,
                    )
                }
            }
        },
    )

    if (suggestions && query.isNotBlank()) {
        SuggestionPanel(
            query = query,
            onOpen = { target ->
                suggestions = false
                urlDraft = target
                active.webView.loadUrl(target)
            },
            onDismiss = { suggestions = false },
        )
    }
}

@Composable
private fun SuggestionPanel(
    query: String,
    onOpen: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val db = ServiceLocator.database
    val bookmarks by BrowserData.bookmarks(db, query).collectAsState(initial = emptyList())
    val history by BrowserData.history(db, query).collectAsState(initial = emptyList())
    val bookmarkHits = bookmarks.take(3)
    val historyHits = history.filter { h -> bookmarkHits.none { it.url == h.url } }.take(4)

    Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Column {
            if (bookmarkHits.isEmpty() && historyHits.isEmpty()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            onOpen(
                                "https://duckduckgo.com/?q=" +
                                    java.net.URLEncoder.encode(query, "UTF-8")
                            )
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Search, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.size(8.dp))
                    Text("Search \"$query\"", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            } else {
                bookmarkHits.forEach { bookmark ->
                    SuggestionRow(
                        icon = { Icon(Icons.Filled.Star, contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary) },
                        title = bookmark.title, subtitle = bookmark.url, onOpen = { onOpen(bookmark.url) },
                    )
                }
                historyHits.forEach { entry ->
                    SuggestionRow(
                        icon = { Icon(Icons.Filled.History, contentDescription = null) },
                        title = entry.title, subtitle = entry.url, onOpen = { onOpen(entry.url) },
                    )
                }
            }
            HorizontalDivider()
            Row(
                Modifier.fillMaxWidth().clickable { onDismiss() }.padding(8.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) { Text("Close suggestions") }
            }
        }
    }
}

@Composable
private fun SuggestionRow(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    onOpen: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onOpen() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Spacer(Modifier.size(8.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun BrowserMenu(
    expanded: Boolean,
    engine: BrowserEngine,
    active: com.motion.browser.browser.MotionTab,
    onDismiss: () -> Unit,
    onNavigate: (MotionScreen) -> Unit,
    onCloseTab: () -> Unit,
    onShowFind: () -> Unit,
) {
    val bookmarked by BrowserData.bookmarks(
        ServiceLocator.database, ""
    ).collectAsState(initial = emptyList())
    val isBookmarked = bookmarked.any { it.url == (active.webView.url ?: "").substringBefore('#') }

    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(if (isBookmarked) "Remove bookmark" else "Add bookmark") },
            leadingIcon = {
                Icon(if (isBookmarked) Icons.Filled.Star else Icons.Filled.StarBorder, null)
            },
            onClick = {
                onDismiss()
                kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                    BrowserData.toggleBookmark(
                        active.webView.url ?: "", active.title, ServiceLocator.database
                    )
                }
            },
        )
        DropdownMenuItem(
            text = { Text("New tab") },
            leadingIcon = { Icon(Icons.Filled.Add, null) },
            onClick = {
                onDismiss()
                engine.createTab(BrowserEngine.HOME_URL)
            },
        )
        DropdownMenuItem(
            text = { Text("New incognito tab") },
            leadingIcon = { Icon(Icons.Filled.VisibilityOff, null) },
            onClick = {
                onDismiss()
                engine.createTab("about:blank", incognito = true)
            },
        )
        DropdownMenuItem(
            text = { Text("Desktop site") },
            leadingIcon = { Icon(Icons.Filled.Language, null) },
            onClick = {
                onDismiss()
                engine.setDesktopMode(active, !active.desktopMode)
            },
        )
        DropdownMenuItem(
            text = { Text("Find in page") },
            leadingIcon = { Icon(Icons.Filled.FindInPage, null) },
            onClick = { onDismiss(); onShowFind() },
        )
        DropdownMenuItem(
            text = { Text("Bookmarks") },
            leadingIcon = { Icon(Icons.Filled.Star, null) },
            onClick = { onDismiss(); onNavigate(MotionScreen.Bookmarks) },
        )
        DropdownMenuItem(
            text = { Text("History") },
            leadingIcon = { Icon(Icons.Filled.History, null) },
            onClick = { onDismiss(); onNavigate(MotionScreen.History) },
        )
        DropdownMenuItem(
            text = { Text("Downloads") },
            leadingIcon = { Icon(Icons.Filled.Download, null) },
            onClick = { onDismiss(); onNavigate(MotionScreen.Downloads) },
        )
        DropdownMenuItem(
            text = { Text("Settings") },
            leadingIcon = { Icon(Icons.Filled.Settings, null) },
            onClick = { onDismiss(); onNavigate(MotionScreen.Settings) },
        )
        DropdownMenuItem(
            text = { Text("Close tab") },
            leadingIcon = { Icon(Icons.Filled.Close, null) },
            onClick = { onDismiss(); onCloseTab() },
        )
    }
}

@Composable
private fun BottomBar(
    engine: BrowserEngine,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onHome: () -> Unit,
    onTabs: () -> Unit,
    onMenu: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            IconButton(onClick = onForward, modifier = Modifier.weight(1f)) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Forward")
            }
            IconButton(onClick = onHome, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Home, contentDescription = "Home")
            }
            IconButton(onClick = onTabs, modifier = Modifier.weight(1f)) {
                BadgedTabIcon(engine.tabs.size)
            }
            IconButton(onClick = onMenu, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.MoreVert, contentDescription = "Menu")
            }
        }
    }
}

@Composable
private fun BadgedTabIcon(count: Int) {
    androidx.compose.material3.BadgedBox(badge = {
        androidx.compose.material3.Badge { Text(count.toString()) }
    }) {
        Icon(Icons.Filled.Tab, contentDescription = "Tabs")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TabSwitcherSheet(
    engine: BrowserEngine,
    onSelect: (Int) -> Unit,
    onClose: (Int) -> Unit,
    onNew: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            "Tabs (${engine.tabs.size})",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(16.dp),
        )
        LazyColumn(Modifier.fillMaxWidth().height(320.dp)) {
            items(engine.tabs.size) { index ->
                val tab = engine.tabs.getOrNull(index) ?: return@items
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(index) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            tab.title.ifBlank { "Tab ${index + 1}" },
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            tab.webView.url.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = { onClose(index) }) {
                        Icon(Icons.Filled.Close, contentDescription = "Close tab")
                    }
                }
            }
        }
        TextButton(
            onClick = onNew,
            modifier = Modifier.padding(16.dp),
        ) { Text("New tab") }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ErrorCard(error: String, url: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(16.dp)) {
            Text("Page failed to load", style = MaterialTheme.typography.titleSmall)
            Text(
                url, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                error, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            TextButton(onClick = onRetry) { Text("Try again") }
        }
    }
}

/** Chrome-like URL resolution: bare domains get https, other text searches DuckDuckGo. */
fun normalizeUrl(input: String): String {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return "about:blank"
    val looksLikeUrl = trimmed.startsWith("http") ||
        trimmed.matches(Regex("^[a-z0-9-]+(\\.[a-z0-9-]+)+(:\\d+)?(/.*)?$", RegexOption.IGNORE_CASE))
    return if (looksLikeUrl) {
        if (trimmed.startsWith("http")) trimmed else "https://$trimmed"
    } else {
        "https://duckduckgo.com/?q=" + java.net.URLEncoder.encode(trimmed, "UTF-8")
    }
}
