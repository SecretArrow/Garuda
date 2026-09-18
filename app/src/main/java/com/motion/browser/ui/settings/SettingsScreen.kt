package com.motion.browser.ui.settings

import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Cookie
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.motion.browser.ServiceLocator
import com.motion.browser.data.SearchEngine
import com.motion.browser.data.StartupMode
import com.motion.browser.data.ThemeMode
import kotlinx.coroutines.launch

/**
 * Full browser Settings (spec §12.4): General, Appearance, Privacy & Security,
 * Site Settings, Downloads, Accessibility, Advanced. Every toggle writes to
 * [com.motion.browser.data.SettingsRepository] and is applied live to the
 * engines by TabManager's settings collector.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val repo = ServiceLocator.settingsRepository
    val settings by repo.settings.collectAsState()
    val scope = rememberCoroutineScope()

    var showSearchDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showStartupDialog by remember { mutableStateOf(false) }
    var showHomepageDialog by remember { mutableStateOf(false) }
    var showClearDataDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            // ---------------------------------------------------------- General
            SectionHeader("General")
            SettingEntry(Icons.Filled.Home, "Homepage", settings.homepage.ifBlank { "New Tab page" }) {
                showHomepageDialog = true
            }
            SettingEntry(Icons.Filled.Search, "Search engine", settings.searchEngine.label) {
                showSearchDialog = true
            }
            SettingEntry(
                Icons.Filled.DesktopWindows, "On startup",
                when (settings.startupMode) {
                    StartupMode.RESTORE_SESSION -> "Restore previous tabs"
                    StartupMode.NEW_TAB -> "Open the New Tab page"
                    StartupMode.HOMEPAGE -> "Open homepage"
                },
            ) { showStartupDialog = true }
            SettingEntry(Icons.Filled.Public, "Language", "Follow system") { }
            SettingSwitch(Icons.Filled.Shield, "Shields (anti-tracking)", settings.shieldsEnabled) {
                scope.launch { runCatching { repo.setShieldsEnabled(it) } }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            // ---------------------------------------------------------- Appearance
            SectionHeader("Appearance")
            SettingEntry(Icons.Filled.Palette, "Theme", themeLabel(settings.themeMode)) {
                showThemeDialog = true
            }
            SettingSwitch(Icons.Filled.Palette, "Dynamic color (Material You)", settings.dynamicColor) {
                scope.launch { runCatching { repo.setDynamicColor(it) } }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            // ---------------------------------------------------------- Accessibility
            SectionHeader("Accessibility")
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Accessibility, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(16.dp))
                    Text("Text size", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "${settings.textZoom}%",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Slider(
                    value = settings.textZoom.toFloat(),
                    onValueChange = { scope.launch { runCatching { repo.setTextZoom(it.toInt()) } } },
                    valueRange = 50f..200f,
                    steps = 14,
                )
                SettingSwitchInline("Force dark mode in websites", settings.forceDarkInWebView) {
                    scope.launch { runCatching { repo.setForceDark(it) } }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            // ---------------------------------------------------------- Privacy
            SectionHeader("Privacy & security")
            SettingEntry(Icons.Filled.Delete, "Clear browsing data", "History, cookies, cache") {
                showClearDataDialog = true
            }
            SettingSwitch(Icons.Filled.Cookie, "Allow cookies", settings.cookiesEnabled) {
                scope.launch { runCatching { repo.setCookiesEnabled(it) } }
            }
            SettingSwitch(Icons.Filled.Cookie, "Block third-party cookies", settings.blockThirdPartyCookies) {
                scope.launch { runCatching { repo.setBlockThirdPartyCookies(it) } }
            }
            SettingSwitch(Icons.Filled.Policy, "Send “Do Not Track” request", settings.doNotTrack) {
                scope.launch { runCatching { repo.setDoNotTrack(it) } }
            }
            SettingSwitch(Icons.Filled.Security, "Safe Browsing", settings.safeBrowsing) {
                scope.launch { runCatching { repo.setSafeBrowsing(it) } }
            }
            SettingSwitch(Icons.Filled.Security, "Block pop-up windows", settings.blockPopups) {
                scope.launch { runCatching { repo.setBlockPopups(it) } }
            }
            SettingSwitch(Icons.Filled.Public, "Allow media autoplay", settings.autoplayEnabled) {
                scope.launch { runCatching { repo.setAutoplayEnabled(it) } }
            }
            SettingSwitch(Icons.Filled.Delete, "Clear data on exit", settings.clearOnExit) {
                scope.launch { runCatching { repo.setClearOnExit(it) } }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            // ---------------------------------------------------------- Site settings
            SectionHeader("Site settings")
            SettingSwitch(Icons.Filled.Info, "Camera", settings.siteCamera) {
                scope.launch { runCatching { repo.setSiteCamera(it) } }
            }
            SettingSwitch(Icons.Filled.Info, "Microphone", settings.siteMicrophone) {
                scope.launch { runCatching { repo.setSiteMicrophone(it) } }
            }
            SettingSwitch(Icons.Filled.Info, "Location", settings.siteLocation) {
                scope.launch { runCatching { repo.setSiteLocation(it) } }
            }
            SettingSwitch(Icons.Filled.Info, "Notifications", settings.siteNotifications) {
                scope.launch { runCatching { repo.setSiteNotifications(it) } }
            }
            SettingSwitch(Icons.Filled.Info, "Clipboard access", settings.siteClipboard) {
                scope.launch { runCatching { repo.setSiteClipboard(it) } }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            // ---------------------------------------------------------- Downloads
            SectionHeader("Downloads")
            SettingSwitch(Icons.Filled.Download, "Download notifications", settings.downloadNotifications) {
                scope.launch { runCatching { repo.setDownloadNotifications(it) } }
            }
            SettingSwitch(Icons.Filled.Download, "Download over Wi-Fi only", settings.downloadWifiOnly) {
                scope.launch { runCatching { repo.setDownloadWifiOnly(it) } }
            }
            SettingEntry(Icons.Filled.Download, "Download location", "Download/Motion") { }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            // ---------------------------------------------------------- Advanced
            SectionHeader("Advanced")
            SettingSwitch(Icons.Filled.DesktopWindows, "Desktop site by default", settings.desktopSiteDefault) {
                scope.launch { runCatching { repo.setDesktopSiteDefault(it) } }
            }
            SettingSwitch(Icons.Filled.Info, "Hardware acceleration", settings.hardwareAcceleration) {
                scope.launch { runCatching { repo.setHardwareAcceleration(it) } }
            }
            SettingEntry(
                Icons.Filled.Info, "Engine",
                "WebView " + (android.webkit.WebView.getCurrentWebViewPackage()?.versionName ?: "system"),
            ) { }
            SettingEntry(Icons.Filled.Info, "Version", appVersion()) { }

            Spacer(Modifier.height(24.dp))
        }
    }

    // ---------------------------------------------------------------- dialogs

    if (showSearchDialog) {
        RadioDialog(
            title = "Search engine",
            options = SearchEngine.entries.filter { it != SearchEngine.CUSTOM }.map { it.label },
            selectedIndex = SearchEngine.entries.filter { it != SearchEngine.CUSTOM }
                .indexOfFirst { it == settings.searchEngine }.takeIf { it >= 0 } ?: 0,
            onSelect = { index ->
                showSearchDialog = false
                val engine = SearchEngine.entries.filter { it != SearchEngine.CUSTOM }[index]
                scope.launch { runCatching { repo.setSearchEngine(engine) } }
            },
            onDismiss = { showSearchDialog = false },
        )
    }
    if (showThemeDialog) {
        RadioDialog(
            title = "Theme",
            options = listOf("Follow system", "Light", "Dark"),
            selectedIndex = when (settings.themeMode) {
                ThemeMode.SYSTEM -> 0; ThemeMode.LIGHT -> 1; ThemeMode.DARK -> 2
            },
            onSelect = { index ->
                showThemeDialog = false
                val mode = when (index) { 0 -> ThemeMode.SYSTEM; 1 -> ThemeMode.LIGHT; else -> ThemeMode.DARK }
                scope.launch { runCatching { repo.setThemeMode(mode) } }
            },
            onDismiss = { showThemeDialog = false },
        )
    }
    if (showStartupDialog) {
        RadioDialog(
            title = "On startup",
            options = listOf("Restore previous tabs", "Open the New Tab page", "Open homepage"),
            selectedIndex = when (settings.startupMode) {
                StartupMode.RESTORE_SESSION -> 0; StartupMode.NEW_TAB -> 1; StartupMode.HOMEPAGE -> 2
            },
            onSelect = { index ->
                showStartupDialog = false
                val mode = when (index) { 0 -> StartupMode.RESTORE_SESSION; 1 -> StartupMode.NEW_TAB; else -> StartupMode.HOMEPAGE }
                scope.launch { runCatching { repo.setStartupMode(mode) } }
            },
            onDismiss = { showStartupDialog = false },
        )
    }
    if (showHomepageDialog) {
        var home by remember { mutableStateOf(settings.homepage) }
        AlertDialog(
            onDismissRequest = { showHomepageDialog = false },
            title = { Text("Homepage") },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = home,
                    onValueChange = { home = it },
                    singleLine = true,
                    placeholder = { Text("https://example.com or about:newtab") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showHomepageDialog = false
                    scope.launch { runCatching { repo.setHomepage(home.trim()) } }
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showHomepageDialog = false }) { Text("Cancel") }
            },
        )
    }
    if (showClearDataDialog) {
        ClearDataDialog(onDismiss = { showClearDataDialog = false })
    }
}

@Composable
private fun ClearDataDialog(onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var history by remember { mutableStateOf(true) }
    var cookies by remember { mutableStateOf(true) }
    var cache by remember { mutableStateOf(true) }
    var downloads by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Clear browsing data") },
        text = {
            Column {
                ClearRow("Browsing history", history) { history = it }
                ClearRow("Cookies and site data", cookies) { cookies = it }
                ClearRow("Cached files", cache) { cache = it }
                ClearRow("Download records", downloads) { downloads = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                scope.launch {
                    runCatching {
                        val db = ServiceLocator.database
                        if (history) db.historyDao().clear()
                        if (cookies) {
                            CookieManager.getInstance().removeAllCookies(null)
                            CookieManager.getInstance().flush()
                            WebStorage.getInstance().deleteAllData()
                        }
                        if (cache) runCatching { ServiceLocator.appContext.cacheDir.deleteRecursively() }
                        if (downloads) db.downloadDao().deleteByStatus("COMPLETED")
                    }
                }
            }) { Text("Clear") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ClearRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Checkbox(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun RadioDialog(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEachIndexed { index, option ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(index) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = index == selectedIndex, onClick = { onSelect(index) })
                        Text(option, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

@Composable
private fun SettingEntry(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon, contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Column(Modifier
            .weight(1f)
            .padding(start = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettingSwitch(
    icon: ImageVector,
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon, contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Text(
            title, Modifier
                .weight(1f)
                .padding(start = 16.dp),
            style = MaterialTheme.typography.bodyLarge,
        )
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SettingSwitchInline(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun themeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.SYSTEM -> "Follow system"
    ThemeMode.LIGHT -> "Light"
    ThemeMode.DARK -> "Dark"
}

private fun appVersion(): String = runCatching {
    val ctx = ServiceLocator.appContext
    ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
}.getOrDefault("?")
