package com.motion.browser.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.motion.browser.ServiceLocator
import com.motion.browser.data.BrowserData
import com.motion.browser.data.HistoryEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Browsing history grouped by day, with search and clear-all. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(onOpenUrl: (String) -> Unit, modifier: Modifier = Modifier) {
    var query by remember { mutableStateOf("") }
    val entries by BrowserData.history(ServiceLocator.database, query)
        .collectAsState(initial = emptyList())
    val dayFormat = remember { SimpleDateFormat("EEEE, d MMM yyyy", Locale.getDefault()) }

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("History") },
            actions = {
                TextButton(onClick = {
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                        ServiceLocator.database.historyDao().clear()
                    }
                }) { Text("Clear all") }
            },
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            singleLine = true,
            placeholder = { Text("Search history") },
        )
        if (entries.isEmpty()) {
            Text(
                "Pages you visit will appear here. Incognito tabs are not recorded.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )
        }
        LazyColumn(Modifier.fillMaxSize()) {
            entries.groupBy { dayFormat.format(Date(it.visitedAt)) }.forEach { (day, items) ->
                item(key = "header_$day") {
                    Text(
                        day,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                items(items.size) { index ->
                    val entry = items.getOrNull(index) ?: return@items
                    HistoryRow(entry, onOpenUrl)
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(entry: HistoryEntity, onOpenUrl: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onOpenUrl(entry.url) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.History, contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(entry.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                entry.url,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Open",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
