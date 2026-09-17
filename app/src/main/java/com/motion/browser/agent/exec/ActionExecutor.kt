package com.motion.browser.agent.exec

import com.motion.browser.ServiceLocator
import com.motion.browser.agent.approval.ApprovalQueue
import com.motion.browser.agent.audit.AuditLogger
import com.motion.browser.agent.planner.Plan
import com.motion.browser.data.dao.StepDao
import com.motion.browser.data.entity.GoalEntity
import com.motion.browser.data.entity.StepEntity
import com.motion.browser.security.ToolAction
import com.motion.browser.tools.ToolContext
import com.motion.browser.tools.ToolRegistry
import com.motion.browser.tools.ToolResult
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Executes planned steps against the tool registry and persists one StepEntity per step.
 *
 * Division of labor (ARCHITECTURE.md §3.5/§3.6):
 *  - ToolRegistry.execute (agent 2-d) performs guard + approval flow internally and logs audits.
 *  - This executor maps results, persists steps, and enforces the per-goal resource caps
 *    maxDownloads / maxPosts by counting the DOWNLOAD / SUBMIT tool categories already recorded
 *    for the current run (maxSteps is enforced by the runtime loop).
 *
 * The runtime calls this with a SINGLE-step plan and re-observes afterwards (spec §16) —
 * [execute] still iterates a Plan so it stays usable for batch execution.
 *
 * @return terminal status string: "SUCCESS" | "FAIL" | "STOPPED".
 */
class ActionExecutor(
    private val registry: ToolRegistry,
    // Kept for contract parity: approval interaction happens inside ToolRegistry (agent 2-d).
    @Suppress("unused") private val approvals: ApprovalQueue,
    private val audit: AuditLogger
) {

    suspend fun execute(
        runId: String,
        goalId: String?,
        plan: Plan,
        tabId: String?,
        goal: GoalEntity?
    ): String {
        val stepDao: StepDao = ServiceLocator.database.stepDao()

        for (planStep in plan.steps) {
            val tool = runCatching { registry.get(planStep.tool) }.getOrNull()
            val action = tool?.action ?: ToolAction.READ
            val existing = runCatching { stepDao.getByRun(runId) }.getOrElse { emptyList() }

            if (goal != null) {
                val usedOfKind = existing.count {
                    runCatching { registry.get(it.tool)?.action }.getOrNull() == action
                }
                if (action == ToolAction.DOWNLOAD && goal.maxDownloads >= 0 && usedOfKind >= goal.maxDownloads) {
                    audit.log("AGENT", "Download cap reached — stopping run", "maxDownloads=${goal.maxDownloads}")
                    return "STOPPED"
                }
                if (action == ToolAction.SUBMIT && goal.maxPosts >= 0 && usedOfKind >= goal.maxPosts) {
                    audit.log("AGENT", "Submit/post cap reached — stopping run", "maxPosts=${goal.maxPosts}")
                    return "STOPPED"
                }
            }

            // Real enforcement of the goal's confirmation policy (spec §25):
            // APPROVE_ALL → every action needs the user; APPROVE_SUBMIT/AUTO rely on
            // the SafetyGuard + PermissionManager defaults (submit/download = approval).
            val mode = if (goal?.confirmationPolicy.equals("APPROVE_ALL", ignoreCase = true)) {
                ToolContext.MODE_COPILOT
            } else {
                ToolContext.MODE_AUTONOMOUS
            }
            val context = ToolContext(tabId = tabId, runId = runId, goalId = goalId, mode = mode)
            val result: ToolResult = runCatching {
                registry.execute(planStep.tool, planStep.argsJson, context)
            }.getOrElse { t ->
                ToolResult(ok = false, data = null, error = "Tool crashed: ${t.message ?: t.javaClass.simpleName}")
            }

            val status = if (result.ok) "OK" else "FAIL"
            val entity = StepEntity(
                runId = runId,
                index = existing.size,
                tool = planStep.tool,
                argsJson = planStep.argsJson.take(4000),
                resultJson = compactResult(result),
                status = status,
                at = System.currentTimeMillis()
            )
            runCatching { stepDao.insert(entity) }
                .onFailure { audit.log("ERROR", "Failed to persist step record", it.message) }

            if (!result.ok) {
                audit.log("ERROR", "Tool '${planStep.tool}' failed", result.error)
                return "FAIL"
            }
        }
        return "SUCCESS"
    }

    /** Compact, size-bounded result JSON (ToolResult.data is already a JsonElement). */
    private fun compactResult(result: ToolResult): String = buildJsonObject {
        put("ok", result.ok)
        result.error?.let { put("error", it.take(500)) }
        result.data?.let { put("data", it) }
    }.toString().take(4000)
}
