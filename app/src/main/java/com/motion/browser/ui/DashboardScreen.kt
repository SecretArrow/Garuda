package com.motion.browser.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.motion.browser.ServiceLocator
import com.motion.browser.data.ScheduleEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Agent dashboard (plan Prompt 7 §2): tasks with status/progress/token cost,
 * expandable step audit log, and the recurring-task scheduler.
 */
@Composable
fun DashboardScreen(modifier: Modifier = Modifier) {
    var tab by remember { mutableIntStateOf(0) }
    Column(modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(tab == 0, onClick = { tab = 0 }, text = { Text("Tasks") })
            Tab(tab == 1, onClick = { tab = 1 }, text = { Text("Schedules") })
        }
        if (tab == 0) TaskList(Modifier.weight(1f)) else ScheduleList(Modifier.weight(1f))
    }
}

@Composable
private fun TaskList(modifier: Modifier = Modifier) {
    val tasks by ServiceLocator.database.taskDao().all().collectAsState(initial = emptyList())
    var expandedTask by remember { mutableStateOf<String?>(null) }
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    LazyColumn(modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (tasks.isEmpty()) {
            item {
                Text(
                    "No tasks yet — open the agent chat and describe a browsing task.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(tasks, key = { it.id }) { task ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(
                            task.goal.take(80),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        AssistChip(onClick = {}, label = { Text(task.status) })
                    }
                    LinearProgressIndicator(
                        progress = {
                            if (task.maxSteps > 0) task.stepCount.toFloat() / task.maxSteps else 0f
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    )
                    Text(
                        "step ${task.stepCount}/${task.maxSteps} · tokens ${task.promptTokens + task.completionTokens}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    task.summary?.let {
                        Text(
                            "Result: ${it.take(200)}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    task.error?.let {
                        Text(
                            "Error: ${it.take(200)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    Row(Modifier.padding(top = 4.dp)) {
                        when (task.status) {
                            "RUNNING", "PLANNING", "WAITING_HUMAN" -> {
                                IconButton(onClick = { ServiceLocator.orchestrator?.pause(task.id) }) {
                                    Icon(Icons.Filled.Pause, contentDescription = "Pause")
                                }
                                IconButton(onClick = { ServiceLocator.orchestrator?.stop(task.id) }) {
                                    Icon(Icons.Filled.Stop, contentDescription = "Stop")
                                }
                            }
                            "PAUSED" -> {
                                IconButton(onClick = {
                                    CoroutineScope(Dispatchers.IO).launch {
                                        ServiceLocator.database.taskDao().setStatus(task.id, "QUEUED", System.currentTimeMillis())
                                    }
                                    ServiceLocator.orchestrator?.launch(
                                        task.id,
                                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO),
                                    )
                                }) {
                                    Icon(Icons.Filled.PlayArrow, contentDescription = "Resume")
                                }
                            }
                        }
                        if (task.status == "WAITING_HUMAN") {
                            TextButton(onClick = {
                                ServiceLocator.orchestrator?.answerApproval(task.id, true)
                            }) { Text("Allow") }
                            TextButton(onClick = {
                                ServiceLocator.orchestrator?.answerApproval(task.id, false)
                            }) { Text("Deny") }
                        }
                        IconButton(onClick = {
                            CoroutineScope(Dispatchers.IO).launch {
                                ServiceLocator.database.stepDao().deleteForTask(task.id)
                                ServiceLocator.database.taskDao().delete(task.id)
                            }
                        }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete")
                        }
                        TextButton(onClick = {
                            expandedTask = if (expandedTask == task.id) null else task.id
                        }) { Text("Log") }
                    }
                    if (expandedTask == task.id) {
                        val steps by ServiceLocator.database.stepDao().forTaskLive(task.id)
                            .collectAsState(initial = emptyList())
                        Column(Modifier.padding(top = 6.dp)) {
                            steps.takeLast(40).forEach { step ->
                                Text(
                                    "${timeFmt.format(Date(step.at))} ${step.kind}/${step.label}: ${step.detail.take(100)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (step.ok) MaterialTheme.colorScheme.onSurfaceVariant
                                    else MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScheduleList(modifier: Modifier = Modifier) {
    val schedules by ServiceLocator.database.scheduleDao().all().collectAsState(initial = emptyList())
    var showDialog by remember { mutableStateOf(false) }
    val timeFmt = remember { SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()) }

    Column(modifier.padding(8.dp)) {
        TextButton(onClick = { showDialog = true }) { Text("+ New recurring task") }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(schedules, key = { it.id }) { schedule ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(schedule.goal.take(80), style = MaterialTheme.typography.titleSmall)
                            Text(
                                "${schedule.spec} · next ${timeFmt.format(Date(schedule.nextRunAt))}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = {
                            CoroutineScope(Dispatchers.IO).launch {
                                ServiceLocator.database.scheduleDao().delete(schedule.id)
                            }
                        }) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
                    }
                }
            }
        }
    }

    if (showDialog) {
        var goal by remember { mutableStateOf("") }
        var spec by remember { mutableStateOf("daily 08:00") }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("New recurring task") },
            text = {
                Column {
                    androidx.compose.material3.OutlinedTextField(
                        value = goal, onValueChange = { goal = it },
                        label = { Text("Goal") }, modifier = Modifier.fillMaxWidth(),
                    )
                    androidx.compose.material3.OutlinedTextField(
                        value = spec, onValueChange = { spec = it },
                        label = { Text("Schedule (daily 08:00 · every 6h · weekly mon 09:30)") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (goal.isNotBlank()) {
                        val now = System.currentTimeMillis()
                        CoroutineScope(Dispatchers.IO).launch {
                            ServiceLocator.database.scheduleDao().insert(
                                ScheduleEntity(
                                    id = "sch_${now}", goal = goal, spec = spec,
                                    createdAt = now, nextRunAt = com.motion.browser.agent.runtime.ScheduleSpec.nextRunAt(spec, now),
                                )
                            )
                        }
                        showDialog = false
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showDialog = false }) { Text("Cancel") } },
        )
    }
}
