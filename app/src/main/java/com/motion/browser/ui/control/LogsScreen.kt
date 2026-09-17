package com.motion.browser.ui.control

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.motion.browser.ServiceLocator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Structured log viewer (spec §63): category filters + expandable entries.
 * Entries never contain secrets (redaction happens in the audit path).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(onBack: () -> Unit) {
    val categories = remember {
        listOf("MOTION_BROWSER", "MOTION_AI", "AGENT", "AUTOMATION", "SECURITY", "NETWORK", "DOWNLOAD", "ERROR")
    }
    var selected by remember { mutableStateOf<String?>(null) }
    var expandedId by remember { mutableStateOf<Long?>(null) }
    val format = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    val events by (if (selected == null) {
        ServiceLocator.auditLogger.recent(300)
    } else {
        ServiceLocator.database.eventDao().byCategory(selected!!, 300)
    }).collectAsState(initial = emptyList())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Logs") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                AssistChip(
                    onClick = { selected = null },
                    label = { Text("ALL") }
                )
                categories.forEach { cat ->
                    AssistChip(
                        onClick = { selected = cat },
                        label = { Text(cat) }
                    )
                }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(events, key = { it.id }) { event ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                            .clickable { expandedId = if (expandedId == event.id) null else event.id }
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = format.format(Date(event.at)),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = event.category,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = when (event.category) {
                                        "ERROR" -> MaterialTheme.colorScheme.error
                                        "SECURITY" -> Color(0xFFB36A00)
                                        else -> MaterialTheme.colorScheme.primary
                                    }
                                )
                            }
                            Text(
                                text = event.message,
                                style = MaterialTheme.typography.bodySmall
                            )
                            if (expandedId == event.id && !event.detail.isNullOrBlank()) {
                                Text(
                                    text = event.detail!!,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
