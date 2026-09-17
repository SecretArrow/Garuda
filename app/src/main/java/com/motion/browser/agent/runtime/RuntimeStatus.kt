package com.motion.browser.agent.runtime

/**
 * Runtime status exposed by [MotionAgentRuntime.status] (ARCHITECTURE.md §3.6).
 * Exact contract — do not rename members; agents 2-f (UI) and the coordinator bind to these.
 */
sealed class RuntimeStatus {
    object Idle : RuntimeStatus()
    data class Running(val runId: String, val goalId: String?, val step: Int, val lastAction: String) : RuntimeStatus()
    data class WaitingApproval(val approvalId: String, val runId: String) : RuntimeStatus()
    data class Paused(val runId: String?, val reason: String) : RuntimeStatus()
    data class Failed(val runId: String?, val reason: String) : RuntimeStatus()
}
