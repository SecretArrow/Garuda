package com.garuda.browser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.garuda.browser.ServiceLocator

/**
 * Chat drawer (plan Prompt 7 §1): full-height bottom sheet to talk with the
 * agent — task input, live tool-call badges ("[tool] click e12…"), streamed
 * narration, stop button. Every line shown is real agent output.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatDrawer(
    seedText: String?,
    onDismiss: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var input by remember { mutableStateOf(seedText.orEmpty()) }
    val lines = remember { mutableStateListOf<String>() }
    val listState = rememberLazyListState()

    val tasks by ServiceLocator.database.taskDao().all().collectAsState(initial = emptyList())
    val stepsForActive = remember { mutableStateListOf<Pair<String, String>>() }
    val activeTask = tasks.firstOrNull {
        it.status == "RUNNING" || it.status == "PLANNING" || it.status == "WAITING_HUMAN"
    } ?: tasks.firstOrNull()

    // Live narration stream (text deltas + tool badges).
    LaunchedEffect(Unit) {
        com.garuda.browser.agent.runtime.ChatBus.events.collect { line ->
            lines.add(line)
            listState.animateScrollToItem((lines.size - 1).coerceAtLeast(0))
        }
    }
    // Audit trail of the active task (persisted, survives restarts).
    LaunchedEffect(activeTask?.id) {
        val taskId = activeTask?.id ?: return@LaunchedEffect
        ServiceLocator.database.stepDao().forTaskLive(taskId).collect { steps ->
            stepsForActive.clear()
            steps.forEach { stepsForActive.add("${it.kind}/${it.label}" to it.detail.take(120)) }
            listState.animateScrollToItem((lines.size + steps.size - 1).coerceAtLeast(0))
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 12.dp)) {
            Text(
                "Agent",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 6.dp),
            )

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        RoundedCornerShape(12.dp),
                    )
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (lines.isEmpty() && stepsForActive.isEmpty()) {
                    item {
                        Text(
                            "Describe a task — the agent will browse, click and fill forms for you.\n" +
                                "Contoh: \"Buka wikipedia, cari 'Garuda Pancasila', buka artikelnya, rangkum 3 poin.\"",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(lines.size) { i ->
                    val line = lines[i]
                    val isTool = line.startsWith("[tool]")
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 1.dp)
                    ) {
                        Text(
                            line,
                            style = if (isTool) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodySmall,
                            color = if (isTool) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                items(stepsForActive.size) { i ->
                    val (k, v) = stepsForActive[i]
                    Text(
                        "• $k — $v",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("What should the agent do?") },
                    minLines = 1,
                    maxLines = 4,
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp, bottom = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        val goal = input.trim()
                        if (goal.isNotEmpty()) {
                            ServiceLocator.enqueueTaskOnActiveTab(goal)
                            lines.clear()
                            input = ""
                            com.garuda.browser.agent.runtime.GarudaAgentService.start(context)
                        }
                    },
                    enabled = input.isNotBlank(),
                ) { Text("Run") }

                TextButton(onClick = {
                    activeTask?.let { ServiceLocator.orchestrator?.stop(it.id) }
                }) { Text("Stop") }

                TextButton(onClick = {
                    activeTask?.let { ServiceLocator.orchestrator?.pause(it.id) }
                }) { Text("Pause") }

                if (activeTask != null) {
                    AssistChip(onClick = {}, label = {
                        Text("${activeTask.status} · step ${activeTask.stepCount}")
                    })
                }
            }
        }
    }
}
