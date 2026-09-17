@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.motion.browser.ui.control

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.motion.browser.R
import com.motion.browser.ServiceLocator
import com.motion.browser.agent.planner.GoalDraft
import com.motion.browser.data.entity.GoalEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.Calendar
import java.util.UUID

/** Parsed goal schedule (canonical JSON shape, matches planner ScheduleSpec). */
private data class LocalSchedule(
    val kind: String,
    val timeOfDay: String?,
    val intervalMinutes: Int?,
    val daysOfWeek: List<Int>?,
)

/**
 * §37 goal editor. Natural language → [Planner.draftGoalFromText] via
 * [GoalPlannerHolder] (coordinator-wired) → preview card with [Edit]/[Activate].
 * Activate persists a real GoalEntity and schedules it through TriggerManager.
 */
@Composable
fun GoalEditorScreen(goalId: String?, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val goalDao = remember { ServiceLocator.database.goalDao() }

    // Form state
    var nlText by remember { mutableStateOf("") }
    var interpreting by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf<GoalDraft?>(null) }
    var editing by remember { mutableStateOf(false) }
    var editingGoal by remember { mutableStateOf<GoalEntity?>(null) }

    var name by remember { mutableStateOf("") }
    var instruction by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("ONCE") }
    var timeOfDay by remember { mutableStateOf("08:00") }
    var intervalMinutes by remember { mutableStateOf("60") }
    var selectedDays by remember { mutableStateOf(setOf(1, 2, 3, 4, 5)) }
    var domains by remember { mutableStateOf("") }
    var policy by remember { mutableStateOf("AUTO") }
    var maxSteps by remember { mutableFloatStateOf(20f) }
    var notify by remember { mutableStateOf(true) }

    // Load an existing goal (via all() — no getById assumption).
    LaunchedEffect(goalId) {
        if (goalId != null) {
            val loaded = runCatching { goalDao.all().first().find { it.id == goalId } }.getOrNull()
            if (loaded != null) {
                editingGoal = loaded
                editing = true
                name = loaded.name
                instruction = loaded.instruction
                domains = loaded.allowedDomains
                policy = loaded.confirmationPolicy
                maxSteps = loaded.maxSteps.toFloat()
                notify = !loaded.notificationPolicy.equals("OFF", ignoreCase = true)
                parseSchedule(loaded.scheduleJson)?.let { s ->
                    kind = s.kind
                    s.timeOfDay?.takeIf { it.isNotBlank() }?.let { timeOfDay = it }
                    s.intervalMinutes?.let { intervalMinutes = it.toString() }
                    s.daysOfWeek?.takeIf { it.isNotEmpty() }?.let { selectedDays = it.toSet() }
                }
            }
        }
    }

    fun interpret() {
        val text = nlText.trim()
        if (text.isEmpty() || interpreting) return
        interpreting = true
        scope.launch(Dispatchers.Default) {
            val result = runCatching { planner()?.draftGoalFromText(text) }
                .fold(onSuccess = { it }, onFailure = { null })
            withContext(Dispatchers.Main) {
                interpreting = false
                when {
                    result == null ->
                        snackbar.showSnackbar(context.getString(R.string.ge_planner_unavailable))
                    else -> {
                        draft = result
                        editing = false
                        name = result.name
                        instruction = result.instruction
                        result.schedule?.let { s ->
                            s.kind.takeIf { it.isNotBlank() }?.let { kind = it }
                            s.timeOfDay?.takeIf { it.isNotBlank() }?.let { timeOfDay = it }
                            s.intervalMinutes?.let { intervalMinutes = it.toString() }
                            s.daysOfWeek?.takeIf { it.isNotEmpty() }?.let { selectedDays = it.toSet() }
                        }
                        domains = result.domains.joinToString(", ")
                        notify = result.notify
                    }
                }
            }
        }
    }

    fun activate() {
        val safeTime = timeOfDay.trim().takeIf { Regex("""^\d{1,2}:\d{2}$""").matches(it) } ?: "08:00"
        val interval = intervalMinutes.trim().toIntOrNull()?.coerceIn(5, 1440) ?: 60
        val scheduleJson = buildScheduleJson(
            kind = kind,
            timeOfDay = safeTime,
            intervalMinutes = interval,
            days = if (kind == "WEEKLY") selectedDays.sorted() else null,
        )
        val now = System.currentTimeMillis()
        val base = editingGoal
        val goal = GoalEntity(
            id = base?.id ?: UUID.randomUUID().toString(),
            name = name.trim().ifBlank {
                instruction.trim().take(40).ifBlank { context.getString(R.string.ge_default_name) }
            },
            instruction = instruction.trim(),
            enabled = true,
            scheduleJson = scheduleJson,
            allowedDomains = domains.trim(),
            blockedDomains = base?.blockedDomains ?: "",
            allowedActions = base?.allowedActions ?: "",
            blockedActions = base?.blockedActions ?: "",
            confirmationPolicy = policy,
            notificationPolicy = if (notify) "ON" else "OFF",
            memoryPolicy = base?.memoryPolicy ?: "GLOBAL",
            maxSteps = maxSteps.toInt().coerceIn(5, 50),
            maxRuntimeMinutes = base?.maxRuntimeMinutes ?: 15,
            maxDownloads = base?.maxDownloads ?: 5,
            maxPosts = base?.maxPosts ?: 1,
            maxRetries = base?.maxRetries ?: 2,
            createdAt = base?.createdAt ?: now,
            updatedAt = now,
            lastRun = base?.lastRun ?: 0L,
            nextRun = computeNextRun(kind, safeTime, interval, if (kind == "WEEKLY") selectedDays.sorted() else null),
            status = "ACTIVE",
        )
        scope.launch(Dispatchers.Default) {
            var scheduled = false
            try {
                if (base != null) goalDao.update(goal) else goalDao.insert(goal)
                val triggerManager = ServiceLocator.triggerManager
                if (triggerManager != null) {
                    runCatching { triggerManager.scheduleForGoal(goal) }
                        .onSuccess { scheduled = true }
                }
                runCatching {
                    ServiceLocator.auditLogger.log(
                        "AUTOMATION",
                        (if (base != null) "Goal updated: " else "Goal created: ") + goal.name,
                        goal.scheduleJson,
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    snackbar.showSnackbar(context.getString(R.string.err_generic, e.message ?: ""))
                }
                return@launch
            }
            withContext(Dispatchers.Main) {
                if (scheduled) {
                    onBack()
                } else {
                    // Honest limitation note (spec §77 style) instead of fake scheduling.
                    snackbar.showSnackbar(context.getString(R.string.ge_saved_no_scheduler))
                    onBack()
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (editingGoal != null) R.string.ge_title_edit else R.string.ge_title_new
                        )
                    )
                },
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
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Natural-language input (only for new goals)
            if (editingGoal == null) {
                Text(
                    stringResource(R.string.ge_nl_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = nlText,
                    onValueChange = { nlText = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4,
                )
                Button(
                    onClick = { interpret() },
                    enabled = nlText.isNotBlank() && !interpreting,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (interpreting) {
                        CircularProgressIndicator(
                            modifier = Modifier.width(16.dp).height(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        stringResource(
                            if (interpreting) R.string.ge_interpreting else R.string.ge_interpret
                        )
                    )
                }
            }

            // Draft preview card
            val currentDraft = draft
            if (currentDraft != null && !editing) {
                DraftPreviewCard(draft = currentDraft, onEdit = { editing = true }, onActivate = { activate() })
            }

            // Editable form
            if (editing || currentDraft != null || editingGoal != null) {
                Text(
                    stringResource(R.string.ge_name_label),
                    style = MaterialTheme.typography.labelLarge,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Text(
                    stringResource(R.string.ge_instruction_label),
                    style = MaterialTheme.typography.labelLarge,
                )
                OutlinedTextField(
                    value = instruction,
                    onValueChange = { instruction = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 5,
                )

                DropdownField(
                    label = stringResource(R.string.ge_schedule_kind),
                    options = scheduleKindOptions(),
                    selectedValue = kind,
                    onSelect = { kind = it },
                )
                when (kind) {
                    "INTERVAL" -> OutlinedTextField(
                        value = intervalMinutes,
                        onValueChange = { intervalMinutes = it.filter { ch -> ch.isDigit() } },
                        label = { Text(stringResource(R.string.ge_interval_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    "HOURLY" -> Unit
                    else -> OutlinedTextField(
                        value = timeOfDay,
                        onValueChange = { timeOfDay = it },
                        label = { Text(stringResource(R.string.ge_time_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
                if (kind == "WEEKLY") {
                    Text(
                        stringResource(R.string.ge_weekdays_label),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    WeekdayChips(selected = selectedDays, onToggle = { day ->
                        selectedDays = if (day in selectedDays) selectedDays - day else selectedDays + day
                    })
                }

                OutlinedTextField(
                    value = domains,
                    onValueChange = { domains = it },
                    label = { Text(stringResource(R.string.ge_domains_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                DropdownField(
                    label = stringResource(R.string.ge_policy_label),
                    options = listOf(
                        "AUTO" to stringResource(R.string.policy_auto),
                        "APPROVE_SUBMIT" to stringResource(R.string.policy_approve_submit),
                        "APPROVE_ALL" to stringResource(R.string.policy_approve_all),
                    ),
                    selectedValue = policy,
                    onSelect = { policy = it },
                )

                Text(
                    stringResource(R.string.ge_max_steps, maxSteps.toInt()),
                    style = MaterialTheme.typography.labelLarge,
                )
                Slider(
                    value = maxSteps,
                    onValueChange = { maxSteps = it },
                    valueRange = 5f..50f,
                    steps = 44,
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.ge_notification),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = notify, onCheckedChange = { notify = it })
                }

                Button(onClick = { activate() }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.ge_activate))
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// Preview card
// ---------------------------------------------------------------------------

@Composable
private fun DraftPreviewCard(draft: GoalDraft, onEdit: () -> Unit, onActivate: () -> Unit) {
    val context = LocalContext.current
    Card {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(R.string.ge_understood),
                style = MaterialTheme.typography.titleMedium,
            )
            PreviewRow(stringResource(R.string.ge_draft_name), draft.name)
            PreviewRow(
                stringResource(R.string.ge_draft_schedule),
                scheduleDescription(
                    context,
                    LocalSchedule(
                        kind = draft.schedule?.kind ?: "ONCE",
                        timeOfDay = draft.schedule?.timeOfDay,
                        intervalMinutes = draft.schedule?.intervalMinutes,
                        daysOfWeek = draft.schedule?.daysOfWeek,
                    ),
                ),
            )
            PreviewRow(
                stringResource(R.string.ge_draft_domains),
                draft.domains.joinToString(", ").ifBlank { context.getString(R.string.label_none) },
            )
            PreviewRow(
                stringResource(R.string.ge_draft_actions),
                draft.actions.joinToString(", ").ifBlank { context.getString(R.string.label_none) },
            )
            PreviewRow(
                stringResource(R.string.ge_draft_notify),
                context.getString(if (draft.notify) R.string.common_yes else R.string.common_no),
            )
            if (draft.explanation.isNotBlank()) {
                Text(
                    draft.explanation,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onEdit) { Text(stringResource(R.string.ge_edit)) }
                Button(onClick = onActivate) { Text(stringResource(R.string.ge_activate)) }
            }
        }
    }
}

@Composable
private fun PreviewRow(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---------------------------------------------------------------------------
// Dropdown helper (stable APIs only: OutlinedTextField + DropdownMenu)
// ---------------------------------------------------------------------------

@Composable
private fun DropdownField(
    label: String,
    options: List<Pair<String, String>>,
    selectedValue: String,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val currentLabel = options.firstOrNull { it.first == selectedValue }?.second ?: selectedValue
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = currentLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
            modifier = Modifier.fillMaxWidth(),
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable { open = true }
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (value, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onSelect(value)
                        open = false
                    },
                )
            }
        }
    }
}

@Composable
private fun scheduleKindOptions(): List<Pair<String, String>> = listOf(
    "ONCE" to stringResource(R.string.schedule_once),
    "HOURLY" to stringResource(R.string.schedule_hourly),
    "INTERVAL" to stringResource(R.string.schedule_interval),
    "DAILY" to stringResource(R.string.schedule_daily),
    "WEEKLY" to stringResource(R.string.schedule_weekly),
    "WEEKDAYS" to stringResource(R.string.schedule_weekdays),
    "MONTHLY" to stringResource(R.string.schedule_monthly),
)

@Composable
private fun WeekdayChips(selected: Set<Int>, onToggle: (Int) -> Unit) {
    val labels = listOf(
        1 to R.string.weekday_mon, 2 to R.string.weekday_tue, 3 to R.string.weekday_wed,
        4 to R.string.weekday_thu, 5 to R.string.weekday_fri, 6 to R.string.weekday_sat,
        7 to R.string.weekday_sun,
    )
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        labels.forEach { (day, labelRes) ->
            FilterChip(
                selected = day in selected,
                onClick = { onToggle(day) },
                label = { Text(stringResource(labelRes)) },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Schedule JSON (canonical shape = serialized ScheduleSpec, see worklog) + helpers
// ---------------------------------------------------------------------------

private fun buildScheduleJson(kind: String, timeOfDay: String, intervalMinutes: Int, days: List<Int>?): String {
    return buildJsonObject {
        put("kind", kind)
        if (kind != "INTERVAL" && kind != "HOURLY") put("timeOfDay", timeOfDay)
        if (kind == "INTERVAL") put("intervalMinutes", intervalMinutes)
        if (kind == "WEEKLY" && !days.isNullOrEmpty()) {
            putJsonArray("daysOfWeek") { days.forEach { add(it) } }
        }
    }.toString()
}

private fun parseSchedule(json: String): LocalSchedule? = runCatching {
    val obj = Json.parseToJsonElement(json).jsonObject
    LocalSchedule(
        kind = obj["kind"]?.jsonPrimitive?.content ?: "ONCE",
        timeOfDay = obj["timeOfDay"]?.jsonPrimitive?.content,
        intervalMinutes = obj["intervalMinutes"]?.jsonPrimitive?.content?.toIntOrNull(),
        daysOfWeek = obj["daysOfWeek"]?.jsonArray?.mapNotNull { it.jsonPrimitive.content.toIntOrNull() },
    )
}.getOrNull()

@Composable
private fun scheduleDescription(context: android.content.Context, s: LocalSchedule): String {
    val time = s.timeOfDay ?: "08:00"
    return when (s.kind) {
        "HOURLY" -> context.getString(R.string.schedule_desc_hourly)
        "INTERVAL" -> context.getString(R.string.schedule_desc_interval, s.intervalMinutes ?: 60)
        "DAILY" -> context.getString(R.string.schedule_desc_daily, time)
        "WEEKLY" -> context.getString(
            R.string.schedule_desc_weekly,
            dayNames(s.daysOfWeek),
            time,
        )
        "WEEKDAYS" -> context.getString(R.string.schedule_desc_weekdays, time)
        "MONTHLY" -> context.getString(R.string.schedule_desc_monthly, time)
        else -> context.getString(R.string.schedule_desc_once, time)
    }
}

@Composable
private fun dayNames(days: List<Int>?): String {
    val all = listOf(
        1 to R.string.weekday_mon, 2 to R.string.weekday_tue, 3 to R.string.weekday_wed,
        4 to R.string.weekday_thu, 5 to R.string.weekday_fri, 6 to R.string.weekday_sat,
        7 to R.string.weekday_sun,
    )
    val selected = days?.filter { it in 1..7 }.orEmpty()
    return all.filter { it.first in selected }.joinToString(", ") { stringResource(it.second) }
        .ifBlank { stringResource(R.string.schedule_weekly) }
}

/** Approximate next-run hint for display; TriggerManager/WorkManager is the source of truth. */
private fun computeNextRun(kind: String, timeOfDay: String, intervalMinutes: Int, days: List<Int>?): Long {
    return try {
        when (kind) {
            "INTERVAL" -> System.currentTimeMillis() + intervalMinutes * 60_000L
            "HOURLY" -> System.currentTimeMillis() + 3_600_000L
            else -> {
                val parts = timeOfDay.split(":")
                val hour = parts.getOrNull(0)?.trim()?.toIntOrNull()?.coerceIn(0, 23) ?: 8
                val minute = parts.getOrNull(1)?.trim()?.toIntOrNull()?.coerceIn(0, 59) ?: 0
                val cal = Calendar.getInstance()
                cal.set(Calendar.HOUR_OF_DAY, hour)
                cal.set(Calendar.MINUTE, minute)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                if (cal.timeInMillis <= System.currentTimeMillis()) {
                    cal.add(Calendar.DAY_OF_YEAR, 1)
                }
                if (kind == "WEEKLY" && !days.isNullOrEmpty()) {
                    var guard = 0
                    while (guard < 8) {
                        val dow = cal.get(Calendar.DAY_OF_WEEK) // 1=Sunday .. 7=Saturday
                        val isoDay = if (dow == Calendar.SUNDAY) 7 else dow - 1 // 1=Mon .. 7=Sun
                        if (days.contains(isoDay) && cal.timeInMillis > System.currentTimeMillis()) break
                        cal.add(Calendar.DAY_OF_YEAR, 1)
                        guard++
                    }
                }
                cal.timeInMillis
            }
        }
    } catch (_: Exception) {
        System.currentTimeMillis() + 900_000L
    }
}
