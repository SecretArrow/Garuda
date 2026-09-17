package com.motion.browser.agent.goal

import com.motion.browser.agent.audit.AuditLogger
import com.motion.browser.agent.planner.GoalDraft
import com.motion.browser.agent.planner.ScheduleSpec
import com.motion.browser.data.dao.GoalDao
import com.motion.browser.data.entity.GoalEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json

/**
 * CRUD + lifecycle for automation goals (ARCHITECTURE.md §3.6, spec §37).
 * All mutations are audited under the AUTOMATION category.
 */
class GoalManager(
    private val goalDao: GoalDao,
    private val audit: AuditLogger
) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun all(): Flow<List<GoalEntity>> = goalDao.all()

    fun enabled(): Flow<List<GoalEntity>> = goalDao.enabled()

    fun byId(id: String): Flow<GoalEntity?> = goalDao.byId(id)

    suspend fun get(id: String): GoalEntity? = goalDao.getById(id)

    suspend fun create(goal: GoalEntity): GoalEntity {
        val now = System.currentTimeMillis()
        val stamped = goal.copy(
            createdAt = if (goal.createdAt > 0) goal.createdAt else now,
            updatedAt = now
        )
        goalDao.insert(stamped)
        audit.log("AUTOMATION", "Goal created: ${stamped.name}", stamped.id)
        return stamped
    }

    suspend fun update(goal: GoalEntity) {
        goalDao.update(goal.copy(updatedAt = System.currentTimeMillis()))
        audit.log("AUTOMATION", "Goal updated: ${goal.name}", goal.id)
    }

    /**
     * Deletes the goal row only. Associated triggers are cleaned by agent 2-d's
     * TriggerManager.cancelForGoal(goalId) via the cross-scope request noted in worklog.md —
     * this keeps ownership boundaries clean without a Room FK cascade.
     */
    suspend fun deleteGoal(goal: GoalEntity) {
        goalDao.delete(goal)
        audit.log("AUTOMATION", "Goal deleted: ${goal.name}", goal.id)
    }

    suspend fun deleteById(id: String) {
        val goal = goalDao.getById(id) ?: return
        deleteGoal(goal)
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        goalDao.setEnabled(id, enabled)
        audit.log("AUTOMATION", "Goal ${if (enabled) "enabled" else "disabled"}", id)
    }

    /**
     * §37: converts an AI/heuristic [GoalDraft] into a persisted goal.
     * The goal starts DISABLED — the user activates it explicitly from the draft preview.
     */
    suspend fun createFromDraft(draft: GoalDraft): GoalEntity {
        val now = System.currentTimeMillis()
        val goal = GoalEntity(
            name = draft.name.ifBlank { "Untitled goal" },
            instruction = draft.instruction,
            enabled = false,
            scheduleJson = draft.schedule?.let { json.encodeToString(ScheduleSpec.serializer(), it) } ?: "",
            allowedDomains = draft.domains,
            allowedActions = draft.actions,
            confirmationPolicy = "RISKY",
            notificationPolicy = if (draft.notify) "ALL" else "FAILURES",
            memoryPolicy = "GOAL",
            createdAt = now,
            updatedAt = now,
            lastRun = null,
            nextRun = null,
            status = "IDLE"
        )
        goalDao.insert(goal)
        audit.log("AUTOMATION", "Goal created from draft: ${goal.name}", draft.explanation.take(300))
        return goal
    }
}
