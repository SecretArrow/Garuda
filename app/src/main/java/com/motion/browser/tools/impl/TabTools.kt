package com.motion.browser.tools.impl

import com.motion.browser.ServiceLocator
import com.motion.browser.browser.tabs.TabManager
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
 * Tab management tools (spec §17). Operate on the TabManager (agent 2-a) via ServiceLocator.tabs;
 * fail honestly when the browser UI is not attached.
 * Action mapping: tab state changes = NAVIGATE; listing = READ.
 */
internal class TabTools {

    fun all(): List<BrowserTool> = listOf(createTab, closeTab, switchTab, duplicateTab, listTabs)

    private fun tabs(): TabManager? = runCatching { ServiceLocator.tabs }.getOrNull()

    private val createTab = object : BaseTool(
        "createTab", ToolAction.NAVIGATE,
        "Open a new browser tab. Args: {\"url\": string?, \"isPrivate\": boolean?}. " +
            "With no url a new-tab page is opened. Returns {\"tabId\": string}."
    ) {
        override suspend fun execute(argsJson: String, ctx: ToolContext): ToolResult {
            val tm = tabs() ?: return ToolResult.fail("Tabs unavailable — browser is not attached")
            val a = ToolArgs.parse(argsJson)
            val url = ToolArgs.str(a, "url")?.trim().takeIf { !it.isNullOrBlank() } ?: "about:newtab"
            val isPrivate = ToolArgs.bool(a, "isPrivate", false)
            return try {
                val tabId = tm.createTab(url, isPrivate)
                ToolResult.ok(buildJsonObject { put("tabId", tabId); put("url", url); put("isPrivate", isPrivate) })
            } catch (t: Throwable) {
                ToolResult.fail("createTab failed: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    private val closeTab = object : BaseTool(
        "closeTab", ToolAction.NAVIGATE,
        "Close a tab. Args: {\"tabId\": string?} — defaults to the active tab."
    ) {
        override suspend fun execute(argsJson: String, ctx: ToolContext): ToolResult {
            val tm = tabs() ?: return ToolResult.fail("Tabs unavailable — browser is not attached")
            val a = ToolArgs.parse(argsJson)
            val tabId = ToolArgs.str(a, "tabId") ?: tm.activeTabId.value
                ?: return ToolResult.fail("closeTab needs a 'tabId' or an active tab")
            return try {
                tm.closeTab(tabId)
                ToolResult.ok(buildJsonObject { put("closed", tabId) })
            } catch (t: Throwable) {
                ToolResult.fail("closeTab failed: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    private val switchTab = object : BaseTool(
        "switchTab", ToolAction.NAVIGATE,
        "Switch the browser to another open tab. Args: {\"tabId\": string (required)}."
    ) {
        override suspend fun execute(argsJson: String, ctx: ToolContext): ToolResult {
            val tm = tabs() ?: return ToolResult.fail("Tabs unavailable — browser is not attached")
            val a = ToolArgs.parse(argsJson)
            val tabId = ToolArgs.str(a, "tabId")
                ?: return ToolResult.fail("switchTab requires a 'tabId' argument")
            if (tm.tabs.value.none { it.id == tabId }) {
                return ToolResult.fail("No open tab with id $tabId — use listTabs to see ids")
            }
            return try {
                tm.switchTab(tabId)
                ToolResult.ok(buildJsonObject { put("switchedTo", tabId) })
            } catch (t: Throwable) {
                ToolResult.fail("switchTab failed: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    private val duplicateTab = object : BaseTool(
        "duplicateTab", ToolAction.NAVIGATE,
        "Duplicate an existing tab (same URL, new tab). Args: {\"tabId\": string?} — defaults to the active tab. " +
            "Returns {\"newTabId\": string}."
    ) {
        override suspend fun execute(argsJson: String, ctx: ToolContext): ToolResult {
            val tm = tabs() ?: return ToolResult.fail("Tabs unavailable — browser is not attached")
            val a = ToolArgs.parse(argsJson)
            val tabId = ToolArgs.str(a, "tabId") ?: tm.activeTabId.value
                ?: return ToolResult.fail("duplicateTab needs a 'tabId' or an active tab")
            val before = tm.openCount
            return try {
                tm.duplicateTab(tabId)
                val newId = tm.tabs.value.map { it.id }.lastOrNull { it != tabId }
                ToolResult.ok(
                    buildJsonObject {
                        put("duplicated", tabId)
                        put("openTabs", tm.openCount)
                        if (tm.openCount > before && newId != null) put("newTabId", newId)
                    }
                )
            } catch (t: Throwable) {
                ToolResult.fail("duplicateTab failed: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    private val listTabs = object : BaseTool(
        "listTabs", ToolAction.READ,
        "List all open tabs with id, url, title, private flag, loading state and back/forward availability. " +
            "Returns a JSON array of tab objects. Read-only."
    ) {
        override suspend fun execute(argsJson: String, ctx: ToolContext): ToolResult {
            val tm = tabs() ?: return ToolResult.fail("Tabs unavailable — browser is not attached")
            val active = tm.activeTabId.value
            val arr: JsonArray = JsonArray(
                tm.tabs.value.map { tab ->
                    buildJsonObject {
                        put("id", tab.id)
                        put("url", tab.url)
                        put("title", tab.title)
                        put("isPrivate", tab.isPrivate)
                        put("isLoading", tab.isLoading)
                        put("canGoBack", tab.canGoBack)
                        put("canGoForward", tab.canGoForward)
                        put("isActive", tab.id == active)
                    }
                }
            )
            return ToolResult.ok(buildJsonObject { put("tabs", arr); put("count", arr.size) })
        }
    }
}
