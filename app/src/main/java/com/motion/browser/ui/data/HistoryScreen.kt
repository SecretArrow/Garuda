package com.motion.browser.ui.data

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.motion.browser.ServiceLocator
import com.motion.browser.data.entity.HistoryEntity
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Browsing history: search, day grouping, multi-select delete, clear all,
 * open in browser. Private-tab visits are never recorded (TabManager gate).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HistoryScreen(
    onBack: () -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val dao = ServiceLocator.database.historyDao()
    var search by remember { mutableStateOf("") }
    var selection by remember { mutableStateOf(setOf<String>()) }
    var confirmClear by remember { mutableStateOf(false) }
    val dayFormat = remember { SimpleDateFormat("EEEE, d MMM yyyy", Locale.getDefault()) }

    val entries by (if (search.isBlank()) dao.recent(300) else dao.search(search, 300))
        .collectAsState(initial = emptyList())

    val grouped: List<Pair<String, List<HistoryEntity>>> = remember(entries) {
        entries.groupBy { dayFormat.format(Date(it.visitedAt)) }
            .map { (day, items) -> day to items }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (selection.isEmpty()) "History" else "${selection.size} selected") },
                navigationIcon = {
                    IconButton(onClick = {
                        if (selection.isNotEmpty()) selection = emptySet() else onBack()
                    }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    if (selection.isNotEmpty()) {
                        IconButton(onClick = { selection = entries.map { it.id }.toSet() }) {
                            Icon(Icons.Filled.SelectAll, contentDescription = "Select all")
                        }
                        IconButton(onClick = {
                            scope.launch {
                                runCatching { dao.deleteByIds(selection.toList()) }
                                selection = emptySet()
                            }
                        }) { Icon(Icons.Filled.Delete, contentDescription = "Delete selected") }
                    } else {
                        TextButton(onClick = { confirmClear = true }) { Text("Clear all") }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier
            .fillMaxSize()
            .padding(padding)) {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                placeholder = { Text("Search history") },
            )
            if (entries.isEmpty()) {
                EmptyPane("No browsing history. Pages you visit appear here.")
            }
            LazyColumn(Modifier.fillMaxSize()) {
                grouped.forEach { (day, items) ->
                    item(key = "header_$day") {
                        Text(
                            day,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                        HorizontalDivider()
                    }
                    items(items, key = { it.id }) { entry ->
                        ListItem(
                            headlineContent = { Text(entry.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                Text(
                                    entry.url + "  ·  " + entry.visitCount + "×",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            leadingContent = {
                                Icon(Icons.Filled.History, contentDescription = null)
                            },
                            trailingContent = {
                                if (selection.isNotEmpty()) {
                                    androidx.compose.material3.Checkbox(
                                        checked = entry.id in selection,
                                        onCheckedChange = { on ->
                                            selection = if (on) selection + entry.id else selection - entry.id
                                        },
                                    )
                                } else {
                                    IconButton(onClick = { onOpenUrl(entry.url) }) {
                                        Icon(
                                            Icons.AutoMirrored.Filled.OpenInNew,
                                            contentDescription = "Open page",
                                        )
                                    }
                                }
                            },
                            modifier = Modifier
                                .combinedClickable(
                                    onClick = {
                                        if (selection.isNotEmpty()) {
                                            selection = if (entry.id in selection) selection - entry.id else selection + entry.id
                                        } else {
                                            onOpenUrl(entry.url)
                                        }
                                    },
                                    onLongClick = { selection = selection + entry.id },
                                ),
                        )
                    }
                }
            }
        }
    }

    if (confirmClear) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear all history?") },
            text = { Text("All browsing history will be removed. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    scope.launch { runCatching { dao.clear() } }
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}
