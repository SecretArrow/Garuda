package com.motion.browser.tools

import kotlinx.serialization.json.JsonElement

/**
 * Result of a tool execution (ARCHITECTURE.md §3.5 contract — exact signature:
 *   data class ToolResult(val ok: Boolean, val data: JsonElement? = null, val error: String? = null)
 *
 * `data` is a JSON element so planner prompts can embed it directly; `error` is a short
 * human-readable message safe for logs/UI (callers must redact secrets before persisting).
 */
data class ToolResult(
    val ok: Boolean,
    val data: JsonElement? = null,
    val error: String? = null
) {
    companion object {
        fun ok(data: JsonElement? = null): ToolResult = ToolResult(ok = true, data = data, error = null)
        fun fail(error: String): ToolResult = ToolResult(ok = false, data = null, error = error)
    }
}
