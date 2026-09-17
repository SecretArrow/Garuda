package com.motion.browser.tools

import android.net.Uri
import com.motion.browser.ServiceLocator
import com.motion.browser.agent.notify.MotionNotifier
import com.motion.browser.browser.BrowserController
import com.motion.browser.security.PromptInjectionDefense
import com.motion.browser.security.ToolAction
import com.motion.browser.tools.impl.ExtractionTools
import com.motion.browser.tools.impl.InspectionTools
import com.motion.browser.tools.impl.InteractionTools
import com.motion.browser.tools.impl.MediaTools
import com.motion.browser.tools.impl.NavigationTools
import com.motion.browser.tools.impl.SystemTools
import com.motion.browser.tools.impl.TabTools
import java.security.MessageDigest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * ToolRegistry — the ONLY entry point the agent executor uses to run tools
 * (ARCHITECTURE.md §3.5 contract — exact signatures:
 *   class ToolRegistry(private val browser: BrowserController,
 *                      private val notifier: MotionNotifier) {
 *       fun all(): List<BrowserTool>
 *       fun get(id: String): BrowserTool?
 *       suspend fun execute(id: String, argsJson: String, ctx: ToolContext): ToolResult
 *   }
 *
 * Execution pipeline (fail-closed):
 *  1. Resolve tool (unknown → error).
 *  2. MANUAL mode lockdown: only READ tools run ("Manual mode: AI may not operate the browser").
 *  3. Resolve current domain from browser.currentUrl().
 *  4. SafetyGuard.preAction with signature "toolId:sha256(args)[..12]" and maxSteps=25
 *     (the goal's own maxSteps budget is enforced by the ActionExecutor; 25 is the default
 *     per-action rate cap for the guard).
 *  5. Approval: when the guard requires approval, a pending approval is created and awaited
 *     (user may edit the args; edited args are used). Timeout / rejection / expiry → error.
 *  6. Execute, wrap failures, record rate-limit usage, audit-log a compact, secret-redacted
 *     result (category AGENT, message "tool <id>").
 */
class ToolRegistry(
    private val browser: BrowserController,
    private val notifier: MotionNotifier
) {

    private val tools: List<BrowserTool> =
        NavigationTools(browser).all() +
            TabTools().all() +
            InteractionTools(browser).all() +
            ExtractionTools(browser).all() +
            InspectionTools(browser).all() +
            MediaTools(browser).all() +
            SystemTools(browser, notifier).all()

    /** All real tools (40 — spec §17 requires ≥36). */
    fun all(): List<BrowserTool> = tools

    fun get(id: String): BrowserTool? = tools.firstOrNull { it.id == id }

    suspend fun execute(id: String, argsJson: String, ctx: ToolContext): ToolResult {
        val tool = get(id) ?: return ToolResult.fail("Unknown tool: $id")

        // 2. Manual mode: the human is driving; the AI may only read.
        if (ctx.mode == ToolContext.MODE_MANUAL && tool.action != ToolAction.READ) {
            return ToolResult.fail("Manual mode: AI may not operate the browser")
        }

        val domain = domainOf()
        val signature = tool.id + ":" + sha256(argsJson).take(12)

        // 3./4. Safety guard (fail-closed: if the guard itself is unavailable, deny).
        val guard = runCatching {
            ServiceLocator.safetyGuard.preAction(ctx.goalId, domain, tool.action, signature, DEFAULT_MAX_STEPS)
        }.getOrElse {
            return ToolResult.fail("Safety guard unavailable — action denied (fail-closed): ${it.message ?: it.javaClass.simpleName}")
        }
        if (!guard.allowed) {
            audit("SECURITY", "tool ${tool.id} denied", "domain=$domain reason=${guard.reason}")
            return ToolResult.fail(guard.reason)
        }

        // 5. Approval flow. Approval is requested even without a runId (ApprovalQueue accepts
        //    null runId) because silently executing SUBMIT/DOWNLOAD/UPLOAD without an explicit
        //    user decision would violate §25 — fail-closed.
        var effectiveArgs = argsJson
        if (guard.requiresApproval) {
            val approvals = runCatching { ServiceLocator.approvalQueue }.getOrElse {
                return ToolResult.fail("Approval queue unavailable — action denied (fail-closed): ${it.message ?: it.javaClass.simpleName}")
            }
            runCatching { approvals.expireStale() }
            val approvalId = approvals.request(ctx.runId, domain, tool.id, argsJson)
            audit("SECURITY", "approval requested", "tool=${tool.id} domain=$domain approval=$approvalId")
            val resolved = approvals.awaitResolution(approvalId, APPROVAL_TIMEOUT_MS)
            when {
                resolved == null -> {
                    audit("SECURITY", "approval timeout", "tool=${tool.id} approval=$approvalId")
                    return ToolResult.fail("Approval timeout")
                }
                resolved.status == "REJECTED" -> {
                    audit("SECURITY", "approval rejected", "tool=${tool.id} approval=$approvalId")
                    return ToolResult.fail("Rejected by user")
                }
                resolved.status == "EXPIRED" -> {
                    audit("SECURITY", "approval expired", "tool=${tool.id} approval=$approvalId")
                    return ToolResult.fail("Approval expired without user decision")
                }
                else -> effectiveArgs = resolved.argsJson.ifBlank { argsJson } // user may have edited args
            }
        }

        // 6. Execute with the (possibly user-edited) args; honest error wrapping.
        val result = try {
            tool.execute(effectiveArgs, ctx)
        } catch (t: Throwable) {
            ToolResult.fail("${tool.id} failed: ${t.message ?: t.javaClass.simpleName}")
        }

        // Rate-limit accounting (shared limiter inside the guard).
        runCatching { ServiceLocator.safetyGuard.recordExecution(ctx.goalId, tool.action.name) }

        // Audit: compact result, secrets redacted (spec §63).
        auditResult(tool, result)
        return result
    }

    private suspend fun auditResult(tool: BrowserTool, result: ToolResult) {
        val dataStr = result.data?.let { it.toString().take(MAX_AUDIT_DATA_CHARS) }
        val detail = buildJsonObject {
            put("ok", result.ok)
            result.error?.let { put("error", it) }
            dataStr?.let { put("data", it) }
        }.toString()
        val secrets = runCatching {
            ServiceLocator.secretStore.keys().mapNotNull { key -> ServiceLocator.secretStore.get(key) }
        }.getOrDefault(emptyList())
        val redacted = PromptInjectionDefense.redactSecrets(detail, secrets)
        ServiceLocator.auditLogger.log("AGENT", "tool ${tool.id}", redacted.take(MAX_AUDIT_DETAIL_CHARS))
    }

    private suspend fun audit(category: String, message: String, detail: String) {
        runCatching { ServiceLocator.auditLogger.log(category, message, detail) }
    }

    /** Current top-level domain from the browser; "unknown" when the browser is not ready. */
    private suspend fun domainOf(): String = try {
        val url = browser.currentUrl()
        val host = Uri.parse(url).host
        host?.lowercase()?.trim()?.takeIf { it.isNotBlank() } ?: UNKNOWN_DOMAIN
    } catch (t: Throwable) {
        UNKNOWN_DOMAIN
    }

    private fun sha256(text: String): String = try {
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    } catch (t: Throwable) {
        "nohash"
    }

    companion object {
        /**
         * Default per-action rate cap used by SafetyGuard.preAction. The goal's own maxSteps
         * budget is enforced by the ActionExecutor (agent 2-c); this is the simple guard cap.
         */
        const val DEFAULT_MAX_STEPS: Int = 25

        /** How long the executor waits for a user approval decision before giving up. */
        const val APPROVAL_TIMEOUT_MS: Long = 3_600_000L

        private const val UNKNOWN_DOMAIN = "unknown"
        private const val MAX_AUDIT_DATA_CHARS = 2_000
        private const val MAX_AUDIT_DETAIL_CHARS = 4_000
    }
}
