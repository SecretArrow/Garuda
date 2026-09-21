package com.motion.browser.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.motion.browser.ServiceLocator
import com.motion.browser.browser.downloads.MotionDownloader
import com.motion.browser.data.BrowserData
import com.motion.browser.data.DownloadEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Download manager: live status, cancel running, delete rows, clear all. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(modifier: Modifier = Modifier) {
    val downloads by BrowserData.downloads(ServiceLocator.database).collectAsState(initial = emptyList())

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Downloads") },
            actions = {
                TextButton(onClick = {
                    CoroutineScope(Dispatchers.IO).launch {
                        ServiceLocator.database.downloadDao().clear()
                    }
                }) { Text("Clear list") }
            },
        )
        if (downloads.isEmpty()) {
            Text(
                "Files you download appear here (saved to Downloads/Motion).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(downloads, key = { it.id }) { download ->
                DownloadRow(download)
            }
        }
    }
}

@Composable
private fun DownloadRow(download: DownloadEntity) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val icon = when (download.status) {
            "RUNNING" -> Icons.Filled.Downloading
            "DONE" -> Icons.Filled.DownloadDone
            "FAILED" -> Icons.Filled.ErrorOutline
            else -> Icons.Filled.Cancel
        }
        Icon(
            icon, contentDescription = download.status,
            tint = when (download.status) {
                "DONE" -> MaterialTheme.colorScheme.primary
                "RUNNING" -> MaterialTheme.colorScheme.tertiary
                else -> MaterialTheme.colorScheme.error
            },
        )
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(download.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                buildString {
                    append(download.status.lowercase().replaceFirstChar { it.uppercase() })
                    if (download.status == "DONE" && download.sizeBytes > 0) {
                        append(" · "); append(MotionDownloader.formatBytes(download.sizeBytes))
                    }
                    if (download.status == "RUNNING") append(" · in progress")
                    download.error?.let { append(" · "); append(it.take(60)) }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (download.status == "RUNNING") {
            IconButton(onClick = { MotionDownloader.cancel(download.id) }) {
                Icon(Icons.Filled.Cancel, contentDescription = "Cancel download")
            }
        } else {
            IconButton(onClick = {
                CoroutineScope(Dispatchers.IO).launch { MotionDownloader.delete(download.id) }
            }) {
                Icon(Icons.Filled.Delete, contentDescription = "Remove from list")
            }
        }
    }
    if (download.status == "RUNNING") {
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
    }
}
