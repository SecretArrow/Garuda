@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.motion.browser.ui.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.motion.browser.R
import com.motion.browser.ServiceLocator
import com.motion.browser.agent.runtime.RuntimeStatus
import com.motion.browser.ui.control.EmptyState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One transcript entry. role: "user" | "motion" | "status" (status = runtime progress line). */
private data class PanelMessage(val role: String, val text: String, val time: Long)

/**
 * Agent operating mode as shown in the panel. Derived from RuntimeStatus (v1 has no
 * separate mode flag): Running → AUTONOMOUS, WaitingApproval/Paused → COPILOT,
 * Idle/Failed → MANUAL (the AI never acts while the runtime is idle).
 */
private enum class AgentMode { MANUAL, COPILOT, AUTONOMOUS }

private data class QuickAction(val labelRes: Int, val instructionRes: Int)

/** §73 quick actions; instruction templates are localized strings (visible in the transcript). */
private val QUICK_ACTIONS = listOf(
    QuickAction(R.string.qa_summarize, R.string.instr_summarize),
    QuickAction(R.string.qa_translate, R.string.instr_translate),
    QuickAction(R.string.qa_explain, R.string.instr_explain),
    QuickAction(R.string.qa_extract, R.string.instr_extract),
    QuickAction(R.string.qa_find, R.string.instr_find),
    QuickAction(R.string.qa_compare, R.string.instr_compare),
    QuickAction(R.string.qa_research, R.string.instr_research),
    QuickAction(R.string.qa_act, R.string.instr_act),
    QuickAction(R.string.qa_automate, R.string.instr_automate),
)

/**
 * "Motion AI" bottom sheet: chat transcript, quick actions, live runtime status,
 * inline approvals and the §27 STOP ALL AGENTS control. All agent work runs off the
 * UI thread through ServiceLocator.agentRuntime.
 */
@Composable
fun AiPanel(onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        AiPanelContent()
    }
}

@Composable
private fun AiPanelContent() {
    val context = LocalContext.current
    val runtime = ServiceLocator.agentRuntime
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val status by runtime.status.collectAsState()
    val pendingApprovals by remember { ServiceLocator.approvalQueue.pending() }
        .collectAsState(initial = emptyList())
    val messages = remember { mutableStateListOf<PanelMessage>() }
    var input by remember { mutableStateOf("") }
    var confirmStop by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    // ---- live runtime status → transcript -------------------------------------------
    LaunchedEffect(Unit) {
        var last: RuntimeStatus? = null
        runtime.status.collect { st ->
            fun add(role: String, text: String) {
                messages.add(PanelMessage(role, text, System.currentTimeMillis()))
            }
            val prev = last
            when (st) {
                is RuntimeStatus.Idle -> {
                    val wasBusy = prev is RuntimeStatus.Running || prev is RuntimeStatus.WaitingApproval ||
                        prev is RuntimeStatus.Paused || prev is RuntimeStatus.Failed
                    if (wasBusy) {
                        add("status", context.getString(R.string.ai_task_finished))
                        // Best-effort enrichment with the latest run result + a short audit feed.
                        runCatching {
                            ServiceLocator.database.runDao().recent(1).first().firstOrNull()
                                ?.resultSummary?.takeIf { it.isNotBlank() }
                                ?.let { add("motion", it) }
                        }
                        runCatching {
                            ServiceLocator.auditLogger.recent(3).first().forEach { ev ->
                                add("status", ev.category + ": " + ev.message)
                            }
                        }
                    }
                }
                is RuntimeStatus.Running -> {
                    if (prev !is RuntimeStatus.Running || prev.runId != st.runId) {
                        add("status", context.getString(R.string.ai_status_working))
                    } else if (prev.step != st.step) {
                        add("status", context.getString(R.string.ai_status_step, st.step, st.lastAction))
                    }
                }
                is RuntimeStatus.WaitingApproval ->
                    add("status", context.getString(R.string.ai_status_waiting_approval))
                is RuntimeStatus.Paused ->
                    add("status", context.getString(R.string.ai_status_paused, st.reason))
                is RuntimeStatus.Failed ->
                    add("status", context.getString(R.string.ai_status_failed, st.reason))
            }
            last = st
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    fun resolveApproval(approvalId: String, approved: Boolean) {
        scope.launch { runCatching { ServiceLocator.approvalQueue.resolve(approvalId, approved) } }
    }

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        messages.add(PanelMessage("user", trimmed, System.currentTimeMillis()))
        input = ""
        scope.launch(Dispatchers.Default) {
            try {
                val tabId = ServiceLocator.tabs?.activeTabId?.value
                ServiceLocator.agentRuntime.runInteractive(trimmed, tabId)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    messages.add(
                        PanelMessage(
                            "status",
                            context.getString(R.string.err_generic, e.message ?: "error"),
                            System.currentTimeMillis(),
                        )
                    )
                }
            }
        }
    }

    val mode = when (status) {
        is RuntimeStatus.Running -> AgentMode.AUTONOMOUS
        is RuntimeStatus.WaitingApproval -> AgentMode.COPILOT
        is RuntimeStatus.Paused -> AgentMode.COPILOT
        is RuntimeStatus.Idle -> AgentMode.MANUAL
        is RuntimeStatus.Failed -> AgentMode.MANUAL
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Header + mode chip
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Outlined.SmartToy,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.ai_panel_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            ModeChip(mode)
        }

        if (mode == AgentMode.MANUAL) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.ai_manual_banner),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                )
            }
        }

        // Transcript
        if (messages.isEmpty()) {
            EmptyState(title = stringResource(R.string.ai_empty_transcript))
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().heightIn(max = 340.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(messages) { msg -> Bubble(msg, timeFormat.format(Date(msg.time))) }
            }
        }

        // Inline approval (first pending) — real approve/reject via ApprovalQueue
        pendingApprovals.firstOrNull()?.let { approval ->
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                shape = RoundedCornerShape(12.dp),
            ) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(
                        text = stringResource(R.string.ai_approval_badge),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        text = approval.domain + " · " + approval.action,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = { resolveApproval(approval.id, false) }) {
                            Text(stringResource(R.string.action_reject))
                        }
                        Button(onClick = { resolveApproval(approval.id, true) }) {
                            Text(stringResource(R.string.action_approve))
                        }
                    }
                }
            }
        }

        // §73 quick actions
        Text(
            text = stringResource(R.string.ai_quick_actions),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            QUICK_ACTIONS.forEach { action ->
                val instruction = stringResource(action.instructionRes)
                val label = stringResource(action.labelRes)
                AssistChip(
                    onClick = { send(instruction) },
                    label = { Text(label) },
                )
            }
        }

        // Input row
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.ai_input_hint)) },
                maxLines = 3,
                shape = RoundedCornerShape(16.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send(input) }),
            )
            FilledIconButton(
                onClick = { send(input) },
                enabled = input.isNotBlank(),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                modifier = Modifier.size(52.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = stringResource(R.string.ai_send),
                )
            }
        }

        // §27 STOP ALL AGENTS
        Button(
            onClick = { confirmStop = true },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
        ) {
            Icon(
                imageVector = Icons.Filled.Stop,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.ai_stop_all))
        }

        // §44 AI privacy note
        Text(
            text = stringResource(R.string.ai_privacy_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SnackbarHost(hostState = snackbar)
        Spacer(Modifier.height(8.dp))
    }

    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text(stringResource(R.string.ai_stop_title)) },
            text = { Text(stringResource(R.string.ai_stop_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmStop = false
                    runCatching { runtime.stopAll() }
                    scope.launch { snackbar.showSnackbar(context.getString(R.string.ai_stop_done)) }
                }) { Text(stringResource(R.string.ai_stop_all)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmStop = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

private data class ModeStyle(
    val container: Color,
    val content: Color,
    val dot: Color,
    val labelRes: Int,
)

@Composable
private fun ModeChip(mode: AgentMode) {
    val style = when (mode) {
        AgentMode.MANUAL -> ModeStyle(
            container = MaterialTheme.colorScheme.surfaceVariant,
            content = MaterialTheme.colorScheme.onSurfaceVariant,
            dot = MaterialTheme.colorScheme.onSurfaceVariant,
            labelRes = R.string.ai_mode_manual,
        )
        AgentMode.COPILOT -> ModeStyle(
            container = MaterialTheme.colorScheme.secondaryContainer,
            content = MaterialTheme.colorScheme.onSecondaryContainer,
            dot = MaterialTheme.colorScheme.onSecondaryContainer,
            labelRes = R.string.ai_mode_copilot,
        )
        AgentMode.AUTONOMOUS -> ModeStyle(
            container = MaterialTheme.colorScheme.primaryContainer,
            content = MaterialTheme.colorScheme.onPrimaryContainer,
            dot = MaterialTheme.colorScheme.onPrimaryContainer,
            labelRes = R.string.ai_mode_autonomous,
        )
    }
    Surface(color = style.container, contentColor = style.content, shape = CircleShape) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Box(Modifier.size(8.dp).background(style.dot, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(style.labelRes), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun Bubble(message: PanelMessage, timeText: String) {
    when (message.role) {
        "user" -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp),
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(message.text, style = MaterialTheme.typography.bodyMedium)
                    Text(timeText, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        "status" -> Text(
            text = message.text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        )
        else -> Row(modifier = Modifier.fillMaxWidth()) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                shape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp),
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(message.text, style = MaterialTheme.typography.bodyMedium)
                    Text(timeText, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}