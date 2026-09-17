package com.motion.browser.agent.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.motion.browser.ServiceLocator
import java.time.LocalDate

/**
 * WorkManager worker fired by TriggerManager schedules (spec §33/§34/§39).
 *
 * Behavior:
 *  - reads goalId (+ triggerId) from input data;
 *  - skips honestly (Result.success) when the emergency stop is active (§27);
 *  - WEEKDAYS triggers no-op on Saturday/Sunday (skip-weekend logic);
 *  - records lastFired on the trigger row, then calls MotionAgentRuntime.runGoal (agent 2-c);
 *  - failures → Result.retry (exponential backoff is set on the request), capped retries.
 */
class GoalTriggerWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val goalId = inputData.getString(KEY_GOAL_ID)
        val triggerId = inputData.getString(KEY_TRIGGER_ID)
        if (goalId.isNullOrBlank()) return Result.failure()

        return try {
            val runtime = ServiceLocator.agentRuntime

            // Emergency stop (§27): skip silently but honestly — this is a deliberate pause, not a failure.
            if (runtime.emergencyStopActive.value) {
                log(
                    "AUTOMATION",
                    "Trigger skipped: emergency stop is active",
                    "goal=$goalId trigger=$triggerId"
                )
                return Result.success()
            }

            val trigger = triggerId?.let { id ->
                runCatching { ServiceLocator.database.triggerDao().getById(id) }.getOrNull()
            }

            // WEEKDAYS: the schedule runs daily; weekends are skipped with a no-op success.
            if (trigger != null && trigger.type.equals("WEEKDAYS", ignoreCase = true)) {
                val isoDay = LocalDate.now().dayOfWeek.value // 1=Mon … 6=Sat, 7=Sun
                if (isoDay >= 6) {
                    log(
                        "AUTOMATION",
                        "Weekday trigger skipped (weekend)",
                        "goal=$goalId trigger=$triggerId day=$isoDay"
                    )
                    return Result.success()
                }
            }

            // Record the firing time (honest bookkeeping for the goal editor's next-run display).
            if (trigger != null) {
                runCatching {
                    ServiceLocator.database.triggerDao()
                        .update(trigger.copy(lastFired = System.currentTimeMillis()))
                }
            }

            runtime.runGoal(goalId, triggerId)
            Result.success()
        } catch (t: Throwable) {
            log(
                "ERROR",
                "GoalTriggerWorker failed — retry scheduled",
                "goal=$goalId trigger=$triggerId ${t.javaClass.simpleName}: ${t.message}"
            )
            if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
        }
    }

    private suspend fun log(category: String, message: String, detail: String?) {
        runCatching { ServiceLocator.auditLogger.log(category, message, detail) }
    }

    companion object {
        const val KEY_GOAL_ID = "goalId"
        const val KEY_TRIGGER_ID = "triggerId"
        private const val MAX_RETRIES = 3
    }
}
