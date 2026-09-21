package com.motion.browser.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Saved pages: search, open, delete (Chrome bookmarks parity). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarksScreen(onOpenUrl: (String) -> Unit, modifier: Modifier = Modifier) {
    var query by remember { mutableStateOf("") }
    val bookmarks by BrowserData.bookmarks(ServiceLocator.database, query)
        .collectAsState(initial = emptyList())

    Column(modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Bookmarks") })
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            singleLine = true,
            placeholder = { Text("Search bookmarks") },
        )
        if (bookmarks.isEmpty()) {
            Text(
                "No bookmarks yet — tap ⋮ → Add bookmark on any page.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(bookmarks, key = { it.id }) { bookmark ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpenUrl(bookmark.url) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.Star, contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                    )
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(bookmark.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            bookmark.url,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = {
                        CoroutineScope(Dispatchers.IO).launch {
                            ServiceLocator.database.bookmarkDao().deleteById(bookmark.id)
                        }
                    }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete bookmark")
                    }
                }
            }
        }
    }
}
