package com.garuda.browser.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.garuda.browser.ServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Human-in-the-loop dialog (plan Prompt 7 §5): surfaces when a task is in
 * WAITING_HUMAN — the agent's ask_human or a risky-action confirmation.
 * Allow / Deny / Always-allow (disables future risky prompts).
 */
@Composable
fun ApprovalDialogs(modifier: Modifier = Modifier) {
    val tasks by ServiceLocator.database.taskDao().all().collectAsState(initial = emptyList())
    val waiting = tasks.firstOrNull { it.status == "WAITING_HUMAN" } ?: return
    val lastStep = ServiceLocator.database.stepDao().forTaskLive(waiting.id)
        .collectAsState(initial = emptyList()).value.lastOrNull()

    AlertDialog(
        onDismissRequest = { /* must be answered explicitly */ },
        modifier = modifier,
        title = { Text("Garuda needs your approval") },
        text = {
            Text(
                buildString {
                    append("Task: ${waiting.goal.take(120)}\n\n")
                    lastStep?.let { append(it.detail.take(240)) }
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = {
                ServiceLocator.orchestrator?.answerApproval(waiting.id, true)
            }) { Text("Allow") }
        },
        dismissButton = {
            TextButton(onClick = {
                ServiceLocator.orchestrator?.answerApproval(waiting.id, false)
            }) { Text("Deny") }
        },
    )
}
