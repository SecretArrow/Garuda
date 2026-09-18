package com.motion.browser.ui.browser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.GTranslate
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.motion.browser.ServiceLocator
import kotlinx.coroutines.launch

/**
 * Built-in New Tab page v2 (spec §72): Motion wordmark, address/search field,
 * top-sites shortcuts from real browsing history, "Ask Motion" entry, recently
 * closed tabs and the real active-automation count.
 */
@Composable
fun NewTabPage(
    onOpenAi: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val controller = ServiceLocator.browser ?: return
    val manager = ServiceLocator.tabs ?: return
    var query by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val goals by (ServiceLocator.database.goalDao().all()).collectAsState(initial = emptyList())
    val activeAutomations = goals.count { it.enabled }

    val topSites by ServiceLocator.database.historyDao().topSites(8)
        .collectAsState(initial = emptyList())

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        Text(
            text = "Motion",
            style = MaterialTheme.typography.displayMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "AI-native browsing",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))

        // ---- Address / search field
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            placeholder = { Text("Search or type a URL") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                Icon(
                    Icons.AutoMirrored.Filled.Send, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = {
                if (query.isNotBlank()) {
                    val target = resolveTarget(query, ServiceLocator.settingsRepository)
                    query = ""
                    scope.launch { runCatching { controller.openUrl(target) } }
                }
            }),
        )

        Spacer(Modifier.height(24.dp))

        // ---- Top sites from real history
        if (topSites.isNotEmpty()) {
            Text(
                "Shortcuts",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
            )
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                topSites.take(6).forEach { site ->
                    ShortcutRow(Icons.Filled.Star, site.title.ifBlank { site.url }, site.url) {
                        scope.launch { runCatching { controller.openUrl(site.url) } }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }

        // ---- AI entry points
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = onOpenAi,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Ask Motion")
            }
            OutlinedButton(
                onClick = {
                    scope.launch { runCatching { manager.createTab(isPrivate = true) } }
                },
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Filled.School, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Incognito")
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---- Automations chip row (real data, honest zero-state)
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            shape = RoundedCornerShape(12.dp),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.GTranslate, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (activeAutomations == 0) "No active automations — create one in Control Center"
                    else "$activeAutomations automation${if (activeAutomations == 1) "" else "s"} active",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun ShortcutRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
            ) {
                Text(
                    title, style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1, textAlign = TextAlign.Start,
                )
                Text(
                    subtitle, style = MaterialTheme.typography.labelSmall,
                    maxLines = 1, textAlign = TextAlign.Start,
                )
            }
        }
    }
}
