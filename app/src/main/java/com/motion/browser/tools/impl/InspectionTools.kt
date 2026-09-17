package com.motion.browser.tools.impl

import com.motion.browser.browser.BrowserController
import com.motion.browser.security.ToolAction
import com.motion.browser.tools.BrowserTool
import com.motion.browser.tools.BaseTool
import com.motion.browser.tools.ToolArgs
import com.motion.browser.tools.ToolContext
import com.motion.browser.tools.ToolResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * DOM-inspection tools (spec §17), implemented on BrowserController (agent 2-a).
 * Both are READ tools — safe in MANUAL mode and never approval-gated.
 */
internal class InspectionTools(private val browser: BrowserController) {

    fun all(): List<BrowserTool> = listOf(inspectDom, inspectVisibleElements)

    private val inspectDom = object : BaseTool(
        "inspectDom", ToolAction.READ,
        "Get a compact summarized DOM of the active page as JSON (structure, interactive elements, " +
            "metadata). Cheaper than full extraction; use before deciding the next click/fill step. " +
            "Returns {\"dom\": <object>}."
    ) {
        override suspend fun execute(argsJson: String, ctx: ToolContext): ToolResult {
            return try {
                val raw = browser.inspectDom()
                val parsed = ToolArgs.parseElement(raw)
                when (parsed) {
                    null -> ToolResult.ok(buildJsonObject { put("dom", raw) })
                    else -> ToolResult.ok(buildJsonObject { put("dom", parsed) })
                }
            } catch (t: Throwable) {
                ToolResult.fail("inspectDom failed: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    private val inspectVisibleElements = object : BaseTool(
        "inspectVisibleElements", ToolAction.READ,
        "List the visible interactive elements of the active page (buttons, links, inputs) with id, " +
            "role, text, aria-label, enabled state, viewport bounds and detection confidence — " +
            "sourced from the page observation pipeline. Returns {\"url\", \"title\", \"elements\": [...]}."
    ) {
        override suspend fun execute(argsJson: String, ctx: ToolContext): ToolResult {
            return try {
                val obs = browser.observePage()
                val arr: JsonArray = JsonArray(
                    obs.elements.filter { it.visible }.map { e ->
                        buildJsonObject {
                            put("id", e.id)
                            put("role", e.role)
                            put("text", e.text)
                            put("ariaLabel", e.ariaLabel)
                            put("enabled", e.enabled)
                            put(
                                "bounds",
                                buildJsonObject {
                                    put("x", e.bounds.x)
                                    put("y", e.bounds.y)
                                    put("width", e.bounds.width)
                                    put("height", e.bounds.height)
                                }
                            )
                            put("confidence", e.confidence)
                        }
                    }
                )
                ToolResult.ok(
                    buildJsonObject {
                        put("url", obs.url)
                        put("title", obs.title)
                        put("elements", arr)
                        put("count", arr.size)
                    }
                )
            } catch (t: Throwable) {
                ToolResult.fail("inspectVisibleElements failed: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }
}
