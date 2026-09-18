package com.motion.browser.ui.data

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.motion.browser.ServiceLocator
import com.motion.browser.data.entity.DownloadEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Download manager UI (spec §12.6): live progress, pause/resume/cancel/retry,
 * open/share/delete for completed files, status filter chips.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(onBack: () -> Unit) {
    val downloads by ServiceLocator.database.downloadDao().all().collectAsState(initial = emptyList())
    var filter by remember { mutableStateOf("ALL") }
    val context = LocalContext.current
    val timeFormat = remember { SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()) }

    val filtered = when (filter) {
        "ACTIVE" -> downloads.filter { it.status == "RUNNING" || it.status == "PENDING" }
        "DONE" -> downloads.filter { it.status == "COMPLETED" }
        "FAILED" -> downloads.filter { it.status == "FAILED" || it.status == "CANCELLED" || it.status == "PAUSED" }
        else -> downloads
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Downloads") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        androidx.compose.foundation.layout.Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // Status filter chips
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(selected = filter == "ALL", onClick = { filter = "ALL" }, label = { Text("All") })
                FilterChip(selected = filter == "ACTIVE", onClick = { filter = "ACTIVE" }, label = { Text("Active") })
                FilterChip(selected = filter == "DONE", onClick = { filter = "DONE" }, label = { Text("Done") })
                FilterChip(selected = filter == "FAILED", onClick = { filter = "FAILED" }, label = { Text("Issues") })
            }
            HorizontalDivider(Modifier.padding(top = 4.dp))

            if (filtered.isEmpty()) {
                EmptyPane("No downloads in this view.\nStart one from any web page — files are saved to Download/Motion.")
            }

            LazyColumn(Modifier.fillMaxSize()) {
                items(filtered, key = { it.id }) { dl ->
                    DownloadRow(dl, timeFormat, context)
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun DownloadRow(
    dl: DownloadEntity,
    timeFormat: SimpleDateFormat,
    context: android.content.Context,
) {
    val downloader = ServiceLocator.tabs?.downloads
    ListItem(
        headlineContent = { Text(dl.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            androidx.compose.foundation.layout.Column {
                Text(
                    text = statusLine(dl, timeFormat),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (dl.status == "RUNNING" && dl.totalBytes > 0) {
                    LinearProgressIndicator(
                        progress = {
                            ((dl.bytesDownloaded * 100f) / dl.totalBytes).coerceIn(0f, 100f) / 100f
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                    )
                }
            }
        },
        leadingContent = {
            Icon(
                when {
                    dl.status == "COMPLETED" -> Icons.Filled.DownloadDone
                    dl.status == "FAILED" || dl.status == "CANCELLED" -> Icons.Filled.ErrorOutline
                    else -> Icons.Filled.Download
                },
                contentDescription = null,
                tint = when {
                    dl.status == "COMPLETED" -> MaterialTheme.colorScheme.primary
                    dl.status == "FAILED" || dl.status == "CANCELLED" -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(24.dp),
            )
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (dl.status) {
                    "RUNNING", "PENDING" -> {
                        ActionIcon(Icons.Filled.Pause, "Pause") { runCatching { downloader?.pause(dl.id) } }
                        ActionIcon(Icons.Filled.Close, "Cancel") { runCatching { downloader?.cancel(dl.id) } }
                    }
                    "PAUSED" -> {
                        ActionIcon(Icons.Filled.PlayArrow, "Resume") { runCatching { downloader?.resume(dl.id) } }
                        ActionIcon(Icons.Filled.Close, "Cancel") { runCatching { downloader?.cancel(dl.id) } }
                    }
                    "FAILED", "CANCELLED" -> {
                        ActionIcon(Icons.Filled.Refresh, "Retry") { runCatching { downloader?.retry(dl.id) } }
                        ActionIcon(Icons.Filled.Delete, "Delete") { runCatching { downloader?.delete(dl.id) } }
                    }
                    "COMPLETED" -> {
                        ActionIcon(Icons.Filled.FileOpen, "Open") { openDownloaded(context, dl) }
                        ActionIcon(Icons.Filled.Share, "Share") { shareDownloaded(context, dl) }
                        ActionIcon(Icons.Filled.Delete, "Delete") { runCatching { downloader?.delete(dl.id) } }
                    }
                }
            }
        },
    )
}

@Composable
private fun ActionIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(20.dp))
    }
}

private fun statusLine(dl: DownloadEntity, timeFormat: SimpleDateFormat): String {
    val when_ = timeFormat.format(Date(dl.createdAt))
    return when (dl.status) {
        "RUNNING" -> "${bytesLabel(dl.bytesDownloaded)}${if (dl.totalBytes > 0) " / ${bytesLabel(dl.totalBytes)}" else ""} — downloading…"
        "PENDING" -> "Queued · $when_"
        "PAUSED" -> "Paused at ${bytesLabel(dl.bytesDownloaded)}" + (dl.error?.let { " · $it" } ?: "")
        "COMPLETED" -> "${bytesLabel(dl.totalBytes)} · $when_"
        "CANCELLED" -> "Cancelled · $when_"
        "FAILED" -> "Failed: ${dl.error ?: "unknown error"} · $when_"
        else -> dl.status
    }
}

private fun bytesLabel(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024f)
    bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1024f / 1024f)
    else -> String.format(Locale.US, "%.2f GB", bytes / 1024f / 1024f / 1024f)
}

private fun openDownloaded(context: android.content.Context, dl: DownloadEntity) {
    runCatching {
        val uri = android.net.Uri.parse(dl.filePath)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, dl.mimeType.takeIf { it.isNotBlank() && it != "application/octet-stream" } ?: "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }.onFailure {
        Toast.makeText(context, "No app can open this file", Toast.LENGTH_SHORT).show()
    }
}

private fun shareDownloaded(context: android.content.Context, dl: DownloadEntity) {
    runCatching {
        val uri = android.net.Uri.parse(dl.filePath)
        context.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = dl.mimeType.ifBlank { "*/*" }
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                "Share ${dl.fileName}"
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
