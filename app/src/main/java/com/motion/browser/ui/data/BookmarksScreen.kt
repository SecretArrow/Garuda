package com.motion.browser.ui.data

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.motion.browser.ServiceLocator
import com.motion.browser.data.entity.BookmarkEntity
import kotlinx.coroutines.launch

/**
 * Bookmarks manager: search, folders, multi-select delete, rename/move, open.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BookmarksScreen(
    onBack: () -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val dao = ServiceLocator.database.bookmarkDao()
    var search by remember { mutableStateOf("") }
    var selection by remember { mutableStateOf(setOf<String>()) }
    var editTarget by remember { mutableStateOf<BookmarkEntity?>(null) }

    val bookmarks by (if (search.isBlank()) dao.allFlat() else dao.search(search))
        .collectAsState(initial = emptyList())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (selection.isEmpty()) "Bookmarks" else "${selection.size} selected") },
                navigationIcon = {
                    IconButton(onClick = {
                        if (selection.isNotEmpty()) selection = emptySet() else onBack()
                    }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    if (selection.isNotEmpty()) {
                        IconButton(onClick = { selection = bookmarks.map { it.id }.toSet() }) {
                            Icon(Icons.Filled.SelectAll, contentDescription = "Select all")
                        }
                        IconButton(onClick = {
                            scope.launch {
                                runCatching {
                                    selection.forEach { dao.deleteById(it) }
                                }
                                selection = emptySet()
                            }
                        }) { Icon(Icons.Filled.Delete, contentDescription = "Delete selected") }
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
                placeholder = { Text("Search bookmarks") },
            )
            if (bookmarks.isEmpty()) {
                EmptyPane("No bookmarks yet — use the menu → Add bookmark on any page.")
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(bookmarks, key = { it.id }) { bm ->
                    ListItem(
                        headlineContent = { Text(bm.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(bm.url, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingContent = {
                            Icon(
                                if (bm.folder.isNotBlank()) Icons.Filled.Folder else Icons.Filled.Star,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                        trailingContent = {
                            if (selection.isNotEmpty()) {
                                androidx.compose.material3.Checkbox(
                                    checked = bm.id in selection,
                                    onCheckedChange = { on ->
                                        selection = if (on) selection + bm.id else selection - bm.id
                                    },
                                )
                            } else {
                                FilledTonalIconButton(onClick = { editTarget = bm }) {
                                    Icon(Icons.Filled.Edit, contentDescription = "Edit bookmark")
                                }
                            }
                        },
                        modifier = Modifier
                            .combinedClickable(
                                onClick = {
                                    if (selection.isNotEmpty()) {
                                        selection = if (bm.id in selection) selection - bm.id else selection + bm.id
                                    } else {
                                        onOpenUrl(bm.url)
                                    }
                                },
                                onLongClick = { selection = selection + bm.id },
                            ),
                    )
                }
            }
        }
    }

    editTarget?.let { target ->
        var title by remember { mutableStateOf(target.title) }
        var url by remember { mutableStateOf(target.url) }
        var folder by remember { mutableStateOf(target.folder) }
        AlertDialog(
            onDismissRequest = { editTarget = null },
            title = { Text("Edit bookmark") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Title") })
                    OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("URL") })
                    OutlinedTextField(value = folder, onValueChange = { folder = it }, label = { Text("Folder (empty = root)") })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        runCatching { dao.update(target.id, title, url, folder.trim(), System.currentTimeMillis()) }
                        editTarget = null
                    }
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = {
                    scope.launch { runCatching { dao.deleteById(target.id) } }
                    editTarget = null
                }) { Text("Delete") }
            },
        )
    }
}

@Composable
internal fun EmptyPane(text: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.size(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
