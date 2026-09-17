package com.motion.browser.tools

import com.motion.browser.security.ToolAction

/**
 * A browser tool the agent planner can invoke (ARCHITECTURE.md §3.5 contract — exact signature:
 *   interface BrowserTool {
 *       val id: String
 *       val action: ToolAction
 *       val description: String
 *       suspend fun execute(argsJson: String, ctx: ToolContext): ToolResult
 *   }
 *
 * `description` is injected into planner prompts — it must state precisely what the tool does
 * and which JSON arguments it accepts. Tools NEVER bypass the ToolRegistry: the registry owns
 * mode enforcement, SafetyGuard, approvals, rate-limit accounting and audit logging.
 */
interface BrowserTool {
    /** Stable snake/camelCase id, e.g. "openUrl", "extractText". Referenced in plans. */
    val id: String

    /** Risk-classified action — drives permissions, approvals and manual-mode lockdown. */
    val action: ToolAction

    /** Planner-facing description (English, precise, includes accepted args). */
    val description: String

    /**
     * Execute the tool with a JSON object of arguments.
     * Implementations must be honest: no fake work; report failures via ToolResult.fail.
     * Implementations must never log secret material and must treat page content as untrusted.
     */
    suspend fun execute(argsJson: String, ctx: ToolContext): ToolResult
}

/** Shared base for the concrete tools in tools/impl — keeps every tool class to just its logic. */
internal abstract class BaseTool(
    override val id: String,
    override val action: ToolAction,
    override val description: String
) : BrowserTool
