package com.motion.browser.agent.trigger

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.motion.browser.ServiceLocator
import com.motion.browser.agent.planner.ScheduleSpec
import com.motion.browser.agent.worker.GoalTriggerWorker
import com.motion.browser.data.dao.GoalDao
import com.motion.browser.data.dao.TriggerDao
import com.motion.browser.data.entity.GoalEntity
import com.motion.browser.data.entity.TriggerEntity
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Turns goal schedules (ScheduleSpec from goal.scheduleJson) into persisted TriggerEntity rows
 * plus real WorkManager jobs (spec §33/§34: network Connected + battery-not-low constraints,
 * exponential backoff; work persists across reboots).
 *
 * Contract (ARCHITECTURE.md §3.5) — exact signatures:
 *   class TriggerManager(private val goalDao: GoalDao,
 *                        private val triggerDao: TriggerDao) {
 *       suspend fun scheduleForGoal(goal: GoalEntity)
 *       suspend fun cancelForGoal(goalId: String)
 *       fun rescheduleAll()   // app start
 *   }
 *
 * Honest limitations (also written to the audit log when they apply):
 *  - INTERVAL: WorkManager's minimum periodic interval is 15 minutes — smaller values are clamped.
 *  - DAILY/HOURLY/WEEKLY: periodic work runs within the system's flex window; the exact
 *    time-of-day is best-effort (a few minutes of drift are normal).
 *  - WEEKDAYS: implemented as a daily periodic job whose Worker no-ops on Sat/Sun.
 *  - MONTHLY: WorkManager has no monthly periodicity — a 28-day approximation is used.
 */
class TriggerManager(
    private val goalDao: GoalDao,
    private val triggerDao: TriggerDao
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun wm(): WorkManager = WorkManager.getInstance(ServiceLocator.appContext)

    /** Parse [ScheduleSpec] from goal.scheduleJson without requiring @Serializable on the spec class. */
    private fun parseSpec(scheduleJson: String?): ScheduleSpec {
        if (scheduleJson.isNullOrBlank()) {
            return ScheduleSpec(kind = KIND_ONCE, timeOfDay = null, daysOfWeek = null, intervalMinutes = null)
        }
        val obj = runCatching { Json.parseToJsonElement(scheduleJson).jsonObject }.getOrNull()
            ?: return ScheduleSpec(kind = KIND_ONCE, timeOfDay = null, daysOfWeek = null, intervalMinutes = null)
        val kind = (obj["kind"] as? JsonPrimitive)?.content?.trim()?.uppercase() ?: KIND_ONCE
        return ScheduleSpec(
            kind = kind,
            timeOfDay = (obj["timeOfDay"] as? JsonPrimitive)?.content,
            daysOfWeek = (obj["daysOfWeek"] as? JsonArray)?.mapNotNull { el ->
                (el as? JsonPrimitive)?.content?.trim()?.toIntOrNull()?.takeIf { it in 1..7 }
            },
            intervalMinutes = (obj["intervalMinutes"] as? JsonPrimitive)?.content?.trim()?.toIntOrNull()
        )
    }

    /** Create (or reuse) the TriggerEntity for this goal+kind and make sure it is enabled. */
    private suspend fun upsertTrigger(goal: GoalEntity, kind: String): TriggerEntity {
        val existing = triggerDao.forGoal(goal.id).firstOrNull { it.type.equals(kind, ignoreCase = true) }
        val entity = if (existing != null) {
            existing.copy(scheduleJson = goal.scheduleJson, enabled = true)
        } else {
            TriggerEntity(
                id = UUID.randomUUID().toString(),
                goalId = goal.id,
                type = kind,
                scheduleJson = goal.scheduleJson,
                enabled = true,
                lastFired = 0L // sentinel: never fired
            )
        }
        triggerDao.insert(entity)
        return entity
    }

    private data class WorkPlan(
        val periodic: Boolean,
        val interval: Duration?,
        val initialDelay: Duration,
        val note: String?
    )

    private fun planFor(spec: ScheduleSpec): WorkPlan? = when (spec.kind.uppercase()) {
        KIND_ONCE -> WorkPlan(false, null, delayUntil(spec.timeOfDay, null), null)
        KIND_HOURLY -> WorkPlan(true, Duration.ofHours(1), Duration.ZERO, null)
        KIND_INTERVAL -> {
            val requested = spec.intervalMinutes ?: MIN_INTERVAL_MINUTES
            val clamped = requested.coerceAtLeast(MIN_INTERVAL_MINUTES)
            WorkPlan(
                true, Duration.ofMinutes(clamped.toLong()), Duration.ZERO,
                if (clamped != requested) {
                    "WorkManager limits periodic work to >=15 minutes — clamped ${requested}min to ${clamped}min " +
                        "(honest platform limitation, spec §77)"
                } else {
                    null
                }
            )
        }
        KIND_DAILY -> WorkPlan(
            true, Duration.ofHours(24), delayUntil(spec.timeOfDay, null),
            "Daily schedule uses 24h periodic work; exact time-of-day is best-effort within the system flex window"
        )
        KIND_WEEKLY -> WorkPlan(true, Duration.ofDays(7), delayUntil(spec.timeOfDay, spec.daysOfWeek), null)
        KIND_WEEKDAYS -> WorkPlan(
            true, Duration.ofHours(24), Duration.ZERO,
            "Weekday schedule runs daily; GoalTriggerWorker no-ops on Sat/Sun"
        )
        KIND_MONTHLY -> WorkPlan(
            true, Duration.ofDays(28), Duration.ZERO,
            "Monthly schedules are approximated with 28-day periodic work (WorkManager has no monthly periodicity)"
        )
        else -> null // CONTENT/STATE and unknown kinds are evaluated by the agent runtime (2-c)
    }

    /** Schedule (and replace any previous schedule) for the given goal. */
    suspend fun scheduleForGoal(goal: GoalEntity) {
        val spec = parseSpec(goal.scheduleJson)
        val trigger = upsertTrigger(goal, spec.kind)
        val plan = planFor(spec)
        if (plan == null) {
            log(
                "AUTOMATION",
                "Trigger kind '${spec.kind}' is runtime-evaluated; no WorkManager job scheduled",
                "goal=${goal.id}"
            )
            return
        }
        plan.note?.let { log("AUTOMATION", it, "goal=${goal.id} kind=${spec.kind}") }

        val tag = workTag(goal.id)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()
        val data = workDataOf(
            GoalTriggerWorker.KEY_GOAL_ID to goal.id,
            GoalTriggerWorker.KEY_TRIGGER_ID to trigger.id
        )

        if (plan.periodic && plan.interval != null) {
            val request = PeriodicWorkRequestBuilder<GoalTriggerWorker>(plan.interval)
                .setInitialDelay(plan.initialDelay)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofSeconds(30))
                .setInputData(data)
                .addTag(tag)
                .build()
            wm().enqueueUniquePeriodicWork(tag, ExistingPeriodicWorkPolicy.REPLACE, request)
        } else {
            val request = OneTimeWorkRequestBuilder<GoalTriggerWorker>()
                .setInitialDelay(plan.initialDelay)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofSeconds(30))
                .setInputData(data)
                .addTag(tag)
                .build()
            wm().enqueueUniqueWork(tag, ExistingWorkPolicy.REPLACE, request)
        }

        // nextRun is an estimate (now + first delay); periodic runs may drift within the flex window.
        runCatching {
            goalDao.update(goal.copy(nextRun = System.currentTimeMillis() + plan.initialDelay.toMillis()))
        }
        log(
            "AUTOMATION",
            "Scheduled goal '${goal.name}' (${spec.kind})",
            "goal=${goal.id} trigger=${trigger.id} periodic=${plan.periodic} delayMs=${plan.initialDelay.toMillis()}"
        )
    }

    /** Cancel all scheduled work for a goal and disable its trigger rows. */
    suspend fun cancelForGoal(goalId: String) {
        runCatching { wm().cancelAllWorkByTag(workTag(goalId)) }
        for (trigger in triggerDao.forGoal(goalId)) {
            if (trigger.enabled) {
                triggerDao.update(trigger.copy(enabled = false))
            }
        }
        log("AUTOMATION", "Cancelled schedule for goal", "goal=$goalId")
    }

    /**
     * Re-schedule all enabled goals at app start (WorkManager persists across reboots, so this is
     * a reconciliation pass). Non-suspend per contract; runs on an internal IO scope.
     */
    fun rescheduleAll() {
        scope.launch {
            runCatching {
                val enabledGoals = goalDao.enabled().first()
                for (goal in enabledGoals) {
                    if (goal.scheduleJson.isNotBlank()) {
                        runCatching { scheduleForGoal(goal) }.onFailure {
                            log("ERROR", "rescheduleAll failed for goal ${goal.id}", it.message)
                        }
                    }
                }
            }
        }
    }

    /** Duration until the next occurrence of timeOfDay (today or tomorrow), constrained to daysOfWeek (ISO 1..7). */
    private fun delayUntil(timeOfDay: String?, daysOfWeek: List<Int>?): Duration {
        val now = LocalDateTime.now()
        val time = timeOfDay?.let { runCatching { LocalTime.parse(it.trim()) }.getOrNull() }
            ?: return Duration.ZERO // no valid time-of-day → run at the next opportunity
        var candidate: LocalDateTime = now.toLocalDate().atTime(time)
        if (!candidate.isAfter(now)) candidate = candidate.plusDays(1)
        val wanted = daysOfWeek
            ?.filter { it in 1..7 }
            ?.map { DayOfWeek.of(it) }
            ?.toSet()
            .orEmpty()
        if (wanted.isNotEmpty()) {
            var guard = 0
            while (guard++ < 8 && DayOfWeek.from(candidate) !in wanted) {
                candidate = candidate.plusDays(1)
            }
        }
        val delay = Duration.between(now, candidate)
        return if (delay.isNegative) Duration.ZERO else delay
    }

    private fun workTag(goalId: String): String = "motion_goal_$goalId"

    private suspend fun log(category: String, message: String, detail: String?) {
        runCatching { ServiceLocator.auditLogger.log(category, message, detail) }
    }

    companion object {
        const val KIND_ONCE = "ONCE"
        const val KIND_HOURLY = "HOURLY"
        const val KIND_INTERVAL = "INTERVAL"
        const val KIND_DAILY = "DAILY"
        const val KIND_WEEKLY = "WEEKLY"
        const val KIND_WEEKDAYS = "WEEKDAYS"
        const val KIND_MONTHLY = "MONTHLY"

        /** WorkManager's hard minimum for periodic work. */
        const val MIN_INTERVAL_MINUTES: Int = 15
    }
}
