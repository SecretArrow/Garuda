package com.motion.browser.tools

/**
 * Context handed to every tool execution (ARCHITECTURE.md §3.5 contract — exact signature:
 *   data class ToolContext(val tabId: String?, val runId: String?, val goalId: String?)
 *
 * DOCUMENTED DEVIATION (coordinator-approved): adds `mode` so the registry can enforce
 * manual-mode lockdown. Defaults to "AUTONOMOUS" so existing 3-arg call sites keep compiling.
 *
 * mode: "MANUAL"     — the AI may not operate the browser (only READ tools pass the registry).
 *       "COPILOT"    — AI proposes, same guards/approvals as autonomous.
 *       "AUTONOMOUS" — full agent mode; SafetyGuard + ApprovalQueue always apply.
 */
data class ToolContext(
    val tabId: String?,
    val runId: String?,
    val goalId: String?,
    val mode: String = MODE_AUTONOMOUS
) {
    companion object {
        const val MODE_MANUAL = "MANUAL"
        const val MODE_COPILOT = "COPILOT"
        const val MODE_AUTONOMOUS = "AUTONOMOUS"
    }
}
