package com.motion.browser.tools.impl

import com.motion.browser.browser.BrowserController
import com.motion.browser.security.ToolAction
import com.motion.browser.tools.BaseTool
import com.motion.browser.tools.ToolArgs
import com.motion.browser.tools.ToolResult
import java.net.URLEncoder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Navigation tools (spec §17). All operate through BrowserController (agent 2-a).
 * Action mapping: page movement = NAVIGATE; find-in-page = READ.
 */
internal class NavigationTools(private val browser: BrowserController) {

    fun all(): List<com.motion.browser.tools.BrowserTool> = listOf(
        openUrl, searchWeb, goBack, goForward, reload, stopLoading, waitForNavigation, findText
    )

    private val openUrl = object : BaseTool(
        "openUrl", ToolAction.NAVIGATE,
        "Navigate to a URL in the current tab, or in a new tab when {\"newTab\":true}. " +
            "Args: {\"url\": string (required), \"newTab\": boolean?}. Waits for the page to finish loading. " +
            "Never use this to bypass a login, captcha or paywall."
    ) {
        override suspend fun execute(argsJson: String, ctx: com.motion.browser.tools.ToolContext): ToolResult {
            val a = ToolArgs.parse(argsJson)
            val url = ToolArgs.str(a, "url")?.trim().orEmpty()
            if (url.isBlank()) return ToolResult.fail("openUrl requires a non-empty 'url' argument")
            val newTab = ToolArgs.bool(a, "newTab", false)
            return try {
                val ok = browser.openUrl(url, newTab)
                if (ok) ToolResult.ok(buildJsonObject { put("url", url); put("newTab", newTab); put("loaded", true) })
                else ToolResult.fail("Page failed to load: $url")
            } catch (t: Throwable) {
                ToolResult.fail("openUrl failed: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    private val searchWeb = object : BaseTool(
        "searchWeb", ToolAction.NAVIGATE,
        "Run a web search on DuckDuckGo (https://duckduckgo.com/?q=) and open the results page. " +
            "Args: {\"query\": string (required)}."
    ) {
        override suspend fun execute(argsJson: String, ctx: com.motion.browser.tools.ToolContext): ToolResult {
            val a = ToolArgs.parse(argsJson)
            val query = ToolArgs.str(a, "query")?.trim().orEmpty()
            if (query.isBlank()) return ToolResult.fail("searchWeb requires a non-empty 'query' argument")
            val url = "https://duckduckgo.com/?q=" + URLEncoder.encode(query, "UTF-8")
            return try {
                val ok = browser.openUrl(url)
                if (ok) ToolResult.ok(buildJsonObject { put("query", query); put("url", url) })
                else ToolResult.fail("Search results page failed to load for: $query")
            } catch (t: Throwable) {
                ToolResult.fail("searchWeb failed: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    private val goBack = object : BaseTool(
        "goBack", ToolAction.NAVIGATE,
        "Go back one step in the active tab's history."
    ) {
        override suspend fun execute(argsJson: String, ctx: com.motion.browser.tools.ToolContext): ToolResult {
            browser.goBack()
            return ToolResult.ok(buildJsonObject { put("action", "goBack") })
        }
    }

    private val goForward = object : BaseTool(
        "goForward", ToolAction.NAVIGATE,
        "Go forward one step in the active tab's history."
    ) {
        override suspend fun execute(argsJson: String, ctx: com.motion.browser.tools.ToolContext): ToolResult {
            browser.goForward()
            return ToolResult.ok(buildJsonObject { put("action", "goForward") })
        }
    }

    private val reload = object : BaseTool(
        "reload", ToolAction.NAVIGATE,
        "Reload the active page."
    ) {
        override suspend fun execute(argsJson: String, ctx: com.motion.browser.tools.ToolContext): ToolResult {
            browser.reload()
            return ToolResult.ok(buildJsonObject { put("action", "reload") })
        }
    }

    private val stopLoading = object : BaseTool(
        "stopLoading", ToolAction.NAVIGATE,
        "Stop the current page load in the active tab."
    ) {
        override suspend fun execute(argsJson: String, ctx: com.motion.browser.tools.ToolContext): ToolResult {
            browser.stopLoading()
            return ToolResult.ok(buildJsonObject { put("action", "stopLoading") })
        }
    }

    private val waitForNavigation = object : BaseTool(
        "waitForNavigation", ToolAction.NAVIGATE,
        "Wait until the active page finishes loading. Args: {\"timeoutMs\": number?} (default 15000). " +
            "Returns {\"finished\": boolean}."
    ) {
        override suspend fun execute(argsJson: String, ctx: com.motion.browser.tools.ToolContext): ToolResult {
            val a = ToolArgs.parse(argsJson)
            val timeoutMs = ToolArgs.long(a, "timeoutMs", 15_000L).coerceIn(500L, 120_000L)
            return try {
                val finished = browser.waitForLoadFinished(timeoutMs)
                ToolResult.ok(buildJsonObject { put("finished", finished); put("timeoutMs", timeoutMs) })
            } catch (t: Throwable) {
                ToolResult.fail("waitForNavigation failed: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    private val findText = object : BaseTool(
        "findText", ToolAction.READ,
        "Find-in-page: search the active page for text and highlight the first match. " +
            "Args: {\"text\": string (required)}. Returns {\"found\": boolean}."
    ) {
        override suspend fun execute(argsJson: String, ctx: com.motion.browser.tools.ToolContext): ToolResult {
            val a = ToolArgs.parse(argsJson)
            val text = ToolArgs.str(a, "text").orEmpty()
            if (text.isBlank()) return ToolResult.fail("findText requires a non-empty 'text' argument")
            return try {
                val found = browser.findText(text)
                ToolResult.ok(buildJsonObject { put("text", text); put("found", found) })
            } catch (t: Throwable) {
                ToolResult.fail("findText failed: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }
}
