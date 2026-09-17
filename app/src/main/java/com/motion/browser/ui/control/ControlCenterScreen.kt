@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.motion.browser.ui.control

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.FactCheck
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.motion.browser.R
import com.motion.browser.ServiceLocator
import com.motion.browser.agent.runtime.RuntimeStatus
import com.motion.browser.data.entity.ApprovalEntity
import com.motion.browser.data.entity.GoalEntity
import com.motion.browser.data.entity.MemoryEntity
import com.motion.browser.data.entity.PermissionEntity
import com.motion.browser.data.entity.RunEntity
import com.motion.browser.data.entity.TriggerEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Internal sub-screens (contract signature of ControlCenterScreen only exposes onBack). */
internal sealed interface SubScreen {
    data object None : SubScreen
    data object Providers : SubScreen
    data object Logs : SubScreen
    data class GoalEditor(val goalId: String?) : SubScreen
}

/**
 * §36 Control Center: overview counters, goals, scheduled triggers, approvals,
 * history, memory, permission rules, provider/log entry points and the emergency stop.
 * All data comes from real ServiceLocator flows; every action performs a real call.
 */
@Composable
fun ControlCenterScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val runtime = ServiceLocator.agentRuntime
    val goalDao = remember { ServiceLocator.database.goalDao() }
    val memoryDao = remember { ServiceLocator.database.memoryDao() }
    val permissionDao = remember { ServiceLocator.database.permissionDao() }

    val status by runtime.status.collectAsState()
    val emergency by runtime.emergencyStopActive.collectAsState()
    val goals by remember { goalDao.all() }.collectAsState(initial = emptyList())
    val triggers by remember { ServiceLocator.database.triggerDao().all() }
        .collectAsState(initial = emptyList())
    val approvals by remember { ServiceLocator.approvalQueue.pending() }
        .collectAsState(initial = emptyList())
    val runs by remember { ServiceLocator.database.runDao().recent(50) }
        .collectAsState(initial = emptyList())
    val permissions by remember { permissionDao.all() }.collectAsState(initial = emptyList())

    var subScreen by remember { mutableStateOf<SubScreen>(SubScreen.None) }
    var memoryScope by remember { mutableStateOf("GLOBAL") }
    val memories by remember(memoryScope) { memoryDao.byScope(memoryScope) }
        .collectAsState(initial = emptyList())

    var confirmEmergency by remember { mutableStateOf(false) }
    var goalToDelete by remember { mutableStateOf<GoalEntity?>(null) }
    var showPermissionDialog by remember { mutableStateOf(false) }

    BackHandler(enabled = subScreen != SubScreen.None) { subScreen = SubScreen.None }

    when (val current = subScreen) {
        SubScreen.Providers -> ProvidersScreen(onBack = { subScreen = SubScreen.None })
        SubScreen.Logs -> LogsScreen(onBack = { subScreen = SubScreen.None })
        is SubScreen.GoalEditor -> GoalEditorScreen(
            goalId = current.goalId,
            onBack = { subScreen = SubScreen.None },
        )
        SubScreen.None -> {
            // ---- derived counters (§36) -------------------------------------------
            val running = if (status is RuntimeStatus.Running) 1 else 0
            val paused = if (status is RuntimeStatus.Paused) 1 else 0
            val scheduled = triggers.count { it.enabled }
            val waitingApproval = approvals.size
            val failed = runs.count { it.status.equals("FAILED", ignoreCase = true) }

            fun resolveApproval(id: String, approved: Boolean) {
                scope.launch { runCatching { ServiceLocator.approvalQueue.resolve(id, approved) } }
            }

            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(stringResource(R.string.cc_title)) },
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.action_back),
                                )
                            }
                        },
                    )
                },
                snackbarHost = { SnackbarHost(snackbar) },
            ) { padding ->
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // ---------------- Overview ----------------
                    item(key = "overview_header") {
                        SectionHeader(Icons.Outlined.Flag, stringResource(R.string.cc_section_overview))
                    }
                    item(key = "overview_counters") {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CounterCard(stringResource(R.string.cc_count_running), running, running > 0)
                            CounterCard(stringResource(R.string.cc_count_scheduled), scheduled, scheduled > 0)
                            CounterCard(stringResource(R.string.cc_count_approval), waitingApproval, waitingApproval > 0)
                            CounterCard(stringResource(R.string.cc_count_paused), paused, paused > 0)
                            CounterCard(stringResource(R.string.cc_count_failed), failed, failed > 0)
                        }
                    }
                    item(key = "emergency") {
                        if (emergency) {
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                ),
                            ) {
                                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Outlined.Warning, contentDescription = null)
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            stringResource(R.string.cc_emergency_active),
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    Button(
                                        onClick = { runCatching { runtime.resume() } },
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.error,
                                            contentColor = MaterialTheme.colorScheme.onError,
                                        ),
                                    ) {
                                        Text(stringResource(R.string.action_resume))
                                    }
                                }
                            }
                        } else {
                            Button(
                                onClick = { confirmEmergency = true },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError,
                                ),
                            ) {
                                Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.cc_emergency_stop))
                            }
                        }
                    }

                    // ---------------- Goals ----------------
                    item(key = "goals_header") {
                        SectionHeader(Icons.Outlined.Flag, stringResource(R.string.cc_section_goals))
                    }
                    item(key = "goals_add") {
                        Button(onClick = { subScreen = SubScreen.GoalEditor(null) }) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.cc_new_goal))
                        }
                    }
                    if (goals.isEmpty()) {
                        item(key = "goals_empty") { EmptyState(title = stringResource(R.string.cc_empty_goals)) }
                    } else {
                        items(goals, key = { "goal_" + it.id }) { goal ->
                            GoalRow(
                                goal = goal,
                                onToggle = { checked ->
                                    scope.launch {
                                        runCatching { goalDao.update(goal.copy(enabled = checked)) }
                                    }
                                },
                                onRun = {
                                    scope.launch(Dispatchers.Default) {
                                        val error = runCatching { runtime.runGoal(goal.id) }.exceptionOrNull()
                                        withContext(Dispatchers.Main) {
                                            snackbar.showSnackbar(
                                                if (error == null) context.getString(R.string.goal_run_started)
                                                else context.getString(R.string.err_generic, error.message ?: "")
                                            )
                                        }
                                    }
                                },
                                onEdit = { subScreen = SubScreen.GoalEditor(goal.id) },
                                onDelete = { goalToDelete = goal },
                            )
                        }
                    }

                    // ---------------- Scheduled ----------------
                    item(key = "scheduled_header") {
                        SectionHeader(Icons.Outlined.Schedule, stringResource(R.string.cc_section_scheduled))
                    }
                    if (triggers.isEmpty()) {
                        item(key = "scheduled_empty") { EmptyState(title = stringResource(R.string.cc_empty_triggers)) }
                    } else {
                        val goalNames = goals.associate { it.id to it.name }
                        items(triggers, key = { "trg_" + it.id }) { trigger ->
                            TriggerRow(trigger, goalNames[trigger.goalId])
                        }
                    }

                    // ---------------- Approvals ----------------
                    item(key = "approvals_header") {
                        SectionHeader(Icons.Outlined.FactCheck, stringResource(R.string.cc_section_approvals))
                    }
                    if (approvals.isEmpty()) {
                        item(key = "approvals_empty") { EmptyState(title = stringResource(R.string.cc_empty_approvals)) }
                    } else {
                        items(approvals, key = { "apr_" + it.id }) { approval ->
                            ApprovalCard(
                                approval = approval,
                                onApprove = { resolveApproval(approval.id, true) },
                                onReject = { resolveApproval(approval.id, false) },
                            )
                        }
                    }

                    // ---------------- History ----------------
                    item(key = "history_header") {
                        SectionHeader(Icons.Outlined.History, stringResource(R.string.cc_section_history))
                    }
                    if (runs.isEmpty()) {
                        item(key = "history_empty") { EmptyState(title = stringResource(R.string.cc_empty_history)) }
                    } else {
                        items(runs, key = { "run_" + it.id }) { run -> RunRow(run) }
                    }

                    // ---------------- Memory ----------------
                    item(key = "memory_header") {
                        SectionHeader(Icons.Outlined.Psychology, stringResource(R.string.cc_section_memory))
                    }
                    item(key = "memory_scopes") {
                        val scopeOptions = listOf(
                            "GLOBAL" to R.string.memory_scope_global,
                            "SITE" to R.string.memory_scope_site,
                            "GOAL" to R.string.memory_scope_goal,
                            "RUN" to R.string.memory_scope_run,
                            "TEMP" to R.string.memory_scope_temp,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            scopeOptions.forEach { (value, labelRes) ->
                                FilterChip(
                                    selected = memoryScope == value,
                                    onClick = { memoryScope = value },
                                    label = { Text(stringResource(labelRes)) },
                                )
                            }
                        }
                    }
                    if (memories.isEmpty()) {
                        item(key = "memory_empty") { EmptyState(title = stringResource(R.string.cc_empty_memory)) }
                    } else {
                        items(memories, key = { "mem_" + it.id }) { memory ->
                            MemoryRow(memory = memory, onDelete = {
                                scope.launch {
                                    runCatching { memoryDao.delete(memory) }
                                    snackbar.showSnackbar(context.getString(R.string.memory_deleted))
                                }
                            })
                        }
                    }

                    // ---------------- Permissions ----------------
                    item(key = "permissions_header") {
                        SectionHeader(Icons.Outlined.AdminPanelSettings, stringResource(R.string.cc_section_permissions))
                    }
                    item(key = "permissions_add") {
                        TextButton(onClick = { showPermissionDialog = true }) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.perm_add_rule))
                        }
                    }
                    if (permissions.isEmpty()) {
                        item(key = "permissions_empty") { EmptyState(title = stringResource(R.string.cc_empty_permissions)) }
                    } else {
                        items(permissions, key = { "perm_" + it.id }) { rule -> PermissionRow(rule) }
                    }

                    // ---------------- Providers / Logs ----------------
                    item(key = "nav_providers") {
                        NavRow(
                            icon = Icons.Outlined.Cloud,
                            title = stringResource(R.string.cc_open_providers),
                            onClick = { subScreen = SubScreen.Providers },
                        )
                    }
                    item(key = "nav_logs") {
                        NavRow(
                            icon = Icons.Outlined.ReceiptLong,
                            title = stringResource(R.string.cc_open_logs),
                            onClick = { subScreen = SubScreen.Logs },
                        )
                    }
                    item(key = "bottom_spacer") { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }

    // ---- dialogs ------------------------------------------------------------------
    if (confirmEmergency) {
        AlertDialog(
            onDismissRequest = { confirmEmergency = false },
            title = { Text(stringResource(R.string.cc_emergency_title)) },
            text = { Text(stringResource(R.string.cc_emergency_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmEmergency = false
                    runCatching { runtime.stopAll() }
                    scope.launch { snackbar.showSnackbar(context.getString(R.string.ai_stop_done)) }
                }) { Text(stringResource(R.string.cc_emergency_stop)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmEmergency = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    goalToDelete?.let { goal ->
        AlertDialog(
            onDismissRequest = { goalToDelete = null },
            title = { Text(stringResource(R.string.goal_delete_title)) },
            text = { Text(stringResource(R.string.goal_delete_text, goal.name)) },
            confirmButton = {
                TextButton(onClick = {
                    val target = goal
                    goalToDelete = null
                    scope.launch {
                        runCatching {
                            ServiceLocator.triggerManager?.cancelForGoal(target.id)
                            goalDao.delete(target)
                        }
                        snackbar.showSnackbar(context.getString(R.string.goal_deleted))
                    }
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { goalToDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (showPermissionDialog) {
        PermissionDialog(
            onDismiss = { showPermissionDialog = false },
            onSave = { rule ->
                showPermissionDialog = false
                scope.launch {
                    runCatching { permissionDao.upsert(rule) }
                    snackbar.showSnackbar(context.getString(R.string.perm_saved))
                }
            },
        )
    }
}

// ---------------------------------------------------------------------------
// Section building blocks
// ---------------------------------------------------------------------------

@Composable
private fun SectionHeader(icon: ImageVector, title: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun CounterCard(label: String, value: Int, highlight: Boolean) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (highlight) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (highlight) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.width(104.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(value.toString(), style = MaterialTheme.typography.headlineSmall)
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
        }
    }
}

@Composable
private fun NavRow(icon: ImageVector, title: String, onClick: () -> Unit) {
    Card {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun GoalRow(
    goal: GoalEntity,
    onToggle: (Boolean) -> Unit,
    onRun: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        goal.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        goal.instruction,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if ((goal.nextRun ?: 0L) > 0) stringResource(R.string.goal_next_run, formatTimestamp(goal.nextRun ?: 0L))
                        else stringResource(R.string.goal_not_scheduled),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Switch(checked = goal.enabled, onCheckedChange = onToggle)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onRun) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.action_run_now))
                }
                TextButton(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.action_edit))
                }
                TextButton(
                    onClick = onDelete,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.action_delete))
                }
            }
        }
    }
}

@Composable
private fun TriggerRow(trigger: TriggerEntity, goalName: String?) {
    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(
                goalName ?: trigger.goalId,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ) {
                    Text(
                        trigger.type,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    if ((trigger.lastFired ?: 0L) > 0) stringResource(R.string.trigger_last_fired, formatTimestamp(trigger.lastFired ?: 0L))
                    else stringResource(R.string.trigger_never_fired),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ApprovalCard(approval: ApprovalEntity, onApprove: () -> Unit, onReject: () -> Unit) {
    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    approval.domain,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    formatTimestamp(approval.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                approval.action,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                stringResource(R.string.approval_details),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                approval.argsJson.take(200),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onReject) { Text(stringResource(R.string.action_reject)) }
                Button(onClick = onApprove) { Text(stringResource(R.string.action_approve)) }
            }
        }
    }
}

@Composable
private fun RunRow(run: RunEntity) {
    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = statusColor(run.status).copy(alpha = 0.16f),
                    contentColor = statusColor(run.status),
                ) {
                    Text(
                        runStatusLabel(run.status),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    run.mode,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    formatTimestamp(run.startedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                run.instruction,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            run.resultSummary?.takeIf { it.isNotBlank() }?.let { summary ->
                Spacer(Modifier.height(4.dp))
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun MemoryRow(memory: MemoryEntity, onDelete: () -> Unit) {
    var handled by remember { mutableStateOf(false) }
    val dismissState = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
        if (value == SwipeToDismissBoxValue.EndToStart) {
            if (!handled) {
                handled = true
                onDelete()
            }
            true
        } else {
            false
        }
    })
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                modifier = Modifier.fillMaxSize()
                    .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(12.dp))
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        },
    ) {
        Card {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Text(
                    memory.key,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    memory.value,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun PermissionRow(rule: PermissionEntity) {
    val context = LocalContext.current
    val labels = buildList {
        if (rule.read) add(context.getString(R.string.perm_read))
        if (rule.navigate) add(context.getString(R.string.perm_navigate))
        if (rule.fillForms) add(context.getString(R.string.perm_fill_forms))
        if (rule.submit) add(context.getString(R.string.perm_submit))
        if (rule.download) add(context.getString(R.string.perm_download))
        if (rule.upload) add(context.getString(R.string.perm_upload))
    }
    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(rule.domain, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                labels.joinToString(" · ").ifEmpty { context.getString(R.string.label_none) },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PermissionDialog(onDismiss: () -> Unit, onSave: (PermissionEntity) -> Unit) {
    var domain by remember { mutableStateOf("") }
    var read by remember { mutableStateOf(true) }
    var navigate by remember { mutableStateOf(true) }
    var fillForms by remember { mutableStateOf(false) }
    var submit by remember { mutableStateOf(false) }
    var download by remember { mutableStateOf(false) }
    var upload by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.perm_add_rule)) },
        text = {
            Column {
                OutlinedTextField(
                    value = domain,
                    onValueChange = { domain = it },
                    label = { Text(stringResource(R.string.perm_domain_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                SwitchRow(stringResource(R.string.perm_read), read) { read = it }
                SwitchRow(stringResource(R.string.perm_navigate), navigate) { navigate = it }
                SwitchRow(stringResource(R.string.perm_fill_forms), fillForms) { fillForms = it }
                SwitchRow(stringResource(R.string.perm_submit), submit) { submit = it }
                SwitchRow(stringResource(R.string.perm_download), download) { download = it }
                SwitchRow(stringResource(R.string.perm_upload), upload) { upload = it }
            }
        },
        confirmButton = {
            TextButton(
                enabled = domain.isNotBlank(),
                onClick = {
                    onSave(
                        PermissionEntity(
                            id = UUID.randomUUID().toString(),
                            domain = domain.trim().lowercase(),
                            read = read,
                            navigate = navigate,
                            fillForms = fillForms,
                            submit = submit,
                            download = download,
                            upload = upload,
                        )
                    )
                },
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

// ---------------------------------------------------------------------------
// Local helpers
// ---------------------------------------------------------------------------

private fun formatTimestamp(ts: Long, pattern: String = "dd MMM HH:mm"): String =
    if (ts <= 0) "-" else SimpleDateFormat(pattern, Locale.getDefault()).format(Date(ts))

/** Localized run status label; unknown status values fall back to the raw value. */
@Composable
private fun runStatusLabel(status: String): String {
    return when (status.uppercase()) {
        "SUCCEEDED" -> stringResource(R.string.run_status_succeeded)
        "RUNNING" -> stringResource(R.string.run_status_running)
        "FAILED" -> stringResource(R.string.run_status_failed)
        "PENDING" -> stringResource(R.string.run_status_pending)
        "PAUSED" -> stringResource(R.string.run_status_paused)
        "STOPPED" -> stringResource(R.string.run_status_stopped)
        else -> status
    }
}

private fun statusColor(status: String): Color = when (status.uppercase()) {
    "SUCCEEDED" -> Color(0xFF2E9E5B)
    "RUNNING" -> Color(0xFF5B8CFF)
    "FAILED" -> Color(0xFFE5534B)
    "PENDING" -> Color(0xFFB98A1F)
    "PAUSED" -> Color(0xFF8A8FA8)
    "STOPPED" -> Color(0xFF8A6FC9)
    else -> Color(0xFF8A8FA8)
}
