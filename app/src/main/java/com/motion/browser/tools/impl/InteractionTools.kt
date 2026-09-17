package com.motion.browser.tools.impl

import com.motion.browser.browser.BrowserController
import com.motion.browser.security.ToolAction
import com.motion.browser.tools.BrowserTool
import com.motion.browser.tools.BaseTool
import com.motion.browser.tools.ToolArgs
import com.motion.browser.tools.ToolContext
import com.motion.browser.tools.ToolResult
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Page-interaction tools (spec §17), all implemented on BrowserController (agent 2-a).
 *
 * Action mapping (per ARCHITECTURE.md §3.5 / spec §17):
 *  - form operations (typeText, clearInput, selectOption, check/uncheck) → FILL_FORM;
 *  - click/scroll/wait movement → NAVIGATE;
 *  - submitForm → SUBMIT (always requires user approval).
 */
internal class InteractionTools(private val browser: BrowserController) {

    fun all(): List<BrowserTool> = listOf(
        scroll, scrollToText, clickElement, clickCoordinates, typeText, clearInput,
        pressKey, selectOption, checkCheckbox, uncheckCheckbox, waitForElement, submitForm
    )

    private val scroll = object : BaseTool(
        "scroll", ToolAction.NAVIGATE,
        "Scroll the active page vertically. Args: {\"dy\": number?} — pixels; positive scrolls down, " +
            "negative scrolls up (default 600)."
    ) {
        override suspend fun execute(argsJson: String, ctx: ToolContext): ToolResult {
            val a = ToolArgs.parse(argsJson)
            val dy = ToolArgs.int(a, "dy", 600)
            return try {
                val ok = browser.scroll(dy)
                if (ok) ToolResult.ok(buildJsonObject { put("dy", dy) })
                else ToolResult.fail("Scroll did not apply (page may not be scrollable)")
            } catch (t: Throwable) {
                ToolResult.fail("scroll failed: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    private val scrollToText = object : BaseTool(
        "scrollToText", ToolAction.NAVIGATE,
        "Scroll the page so the first occurrence of the given text becomes visible. " +
            "Args: {\"text\": string (required)}. Returns {\"found\": boolean}."
    ) {
        override suspend fun execute(argsJson: String, ctx: ToolContext): ToolResult {
            val a = ToolArgs.parse(argsJson)
            val text = ToolArgs.str(a, "text").orEmpty()
            if (text.isBlank()) return ToolResult.fail("scrollToText requires a non-empty 'text' argument")
            return try {
                val found = browser.scrollToText(text)
                ToolResult.ok(buildJsonObject { put("text", text); put("found", found) })
            } catch (t: Throwable) {
                ToolResult.fail("scrollToText failed: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    private val clickElement = object : BaseTool(
        "clickElement", ToolAction.NAVIGATE,
        "Click an element located by CSS selector or by its visible text (at least one required). " +
            "Args: {\"selector\": string?, \"text\": string?}. Use submitForm for form submission."
    ) {
        override suspend fun execute(argsJson: String, ctx: ToolContext): ToolResult {
            val a = ToolArgs.parse(argsJson)
            val selector = ToolArgs.str(a, "selector")?.trim().takeIf { !it.isNullOrBlank() }
            val text = ToolArgs.str(a, "text")?.trim().takeIf { !it.isNullOrBlank() }
            if (selector == null && text == null) {
                return ToolResult.fail("clickElement requires 'selector' and/or 'text'")
            }
            return try {
                val ok = browser.clickElement(selector, text)
                if (ok) ToolResult.ok(buildJsonObject { selector?.let { put("selector", it) }; text?.let { put("text", it) } })
                else ToolResult.fail("Element not found or not clickable")
            } catch (t: Throwable) {
                ToolResult.fail("clickElement failed: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    private val clickCoordinates = object : BaseTool(
        "clickCoordinates", ToolAction.NAVIGATE,
        "Click at viewport pixel coordinates. Args: {\"x\": number (required), \"y\": number (required)}. " +
            "Prefer clickElement when a selector is known — coordinates are a fallback."
    ) {
        override suspend fun execute(argsJson: String, ctx: ToolContext): ToolResult {
            val a = ToolArgs.parse(argsJson)
            val x = ToolArgs.int(a, "x", Int.MIN_VALUE)
            val y = ToolArgs.int(a, "y", Int.MIN_VALUE)
            if (x == Int.MIN_VALUE || y == Int.MIN_VALUE) {
                return ToolResult.fail("clickCoordinates requires numeric 'x' and 'y' arguments")
            }
            return try {
                val ok = browser.clickCoordinates(x, y)
                if (ok) ToolResult.ok(buildJsonObject { put("x", x); put("y", y) })
                else ToolResult.fail("Coordinate click did not land on a clickable target")
            } catch (t: Throwable) {
                ToolResult.fail("clickCoordinates failed: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    private val typeText = object : BaseTool(
        "typeText", ToolAction.FILL_FORM,
        "Type text into an input or textarea located by CSS selector (replaces the current value). " +
            "Args: {\"selector\": string (required), \"text\": string (required)}."
    ) {
        override suspend fun execute(argsJson: String, ctx: ToolContext): ToolResult {
            val a = ToolArgs.parse(argsJson)
            val selector = ToolArgs.str(a, "selector")?.trim().orEmpty()
            val text = ToolArgs.str(a, "text").orEmpty()
            if (selector.isBlank()) return ToolResult.fail("typeText requires a 'selector' argument")
            return try {
                val ok = browser.typeText(selector, text)
                if (ok) ToolResult.ok(buildJsonObject { put("selector", selector); put("typedChars", text.length) })
                else ToolResult.fail("Input element not found for selector: $selector")
            } catch (t: Throwable) {
                ToolResult.fail("typeText failed: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    private val clearInput = object : BaseTool(
        "clearInput", ToolAction.FILL_FORM,
        "Clear the value of an input or textarea located by CSS selector. " +
            "Args: {\"selector\": string (required)}."
    ) {
        override suspend fun execute(argsJson: String, ctx: ToolContext): ToolResult {
            val a = ToolArgs.parse(argsJson)
            val selector = ToolArgs.str(a, "selector")?.trim().orEmpty()
            if (selector.isBlank()) return ToolResult.fail("clearInput requires a 'selector' argument")
            return try {
                val ok = browser.clearInput(selector)
                if (ok) ToolResult.ok(buildJsonObject { put("selector", selector); put("cleared", true) })
                else ToolResult.fail("Input element not found for selector: $selector")
            } catch (t: Throwable) {
                ToolResult.fail("clearInput failed: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    private val pressKey = object : BaseTool(
        "pressKey", ToolAction.NAVIGATE,
        "Send a keyboard key (Enter, Tab, Escape) to the focused element by dispatching synthetic " +
            "KeyboardEvents (keydown/keypress/keyup). Honest limitation: synthetic events do NOT come " +
            "from a real IME/hardware keyboard, so some sites may ignore them. " +
            "Args: {\"key\": string (required)} — one of Enter, Tab, Escape."
    ) {
        override suspend fun execute(argsJson: String, ctx: ToolContext): ToolResult {
            val a = ToolArgs.parse(argsJson)
            val key = ToolArgs.str(a, "key")?.trim().orEmpty()
            if (key !in SUPPORTED_KEYS) {
                return ToolResult.fail("pressKey supports only: ${SUPPORTED_KEYS.joinToString()} — got '$key'")
            }
            val script = "(function(){" +
                "var el=document.activeElement||document.body;" +
                "if(!el||el===document.documentElement&&document.body===null)return JSON.stringify({dispatched:false,error:'no focused element'});" +
                "var k=" + ToolArgs.jsString(key) + ";" +
                "var o={key:k,code:k,bubbles:true,cancelable:true};" +
                "try{" +
                "el.dispatchEvent(new KeyboardEvent('keydown',o));" +
                "el.dispatchEvent(new KeyboardEvent('keypress',o));" +
                "el.dispatchEvent(new KeyboardEvent('keyup',o));" +
                "}catch(e){return JSON.stringify({dispatched:false,error:String(e)});}" +
                "return JSON.stringify({dispatched:true,key:k,target:(el.tagName||'').toLowerCase()});" +
                "})()"
            return try {
                val raw = browser.evaluateJs(script)
                val parsed = ToolArgs.parseJsResult(raw)
                ToolResult.ok(
                    buildJsonObject {
                        put("result", parsed)
                        put(
                            "note",
                            "Synthetic keyboard events do not trigger real IME input; sites requiring " +
                                "hardware-level key events may ignore them."
                        )
                    }
                )
            } catch (t: Throwable) {
                ToolResult.fail("pressKey failed: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    private val selectOption = object : BaseTool(
        "selectOption", ToolAction.FILL_FORM,
        "Select an option (by its value) inside a <select> located by CSS selector. " +
            "Args: {\"selector\": string (required), \"value\": string (required)}."
    ) {
        override suspend fun execute(argsJson: String, ctx: ToolContext): ToolResult {
            val a = ToolArgs.parse(argsJson)
            val selector = ToolArgs.str(a, "selector")?.trim().orEmpty()
            val value = ToolArgs.str(a, "value").orEmpty()
            if (selector.isBlank()) return ToolResult.fail("selectOption requires a 'selector' argument")
            return try {
                val ok = browser.selectOption(selector, value)
                if (ok) ToolResult.ok(buildJsonObject { put("selector", selector); put("value", value) })
                else ToolResult.fail("Select element or option not found: $selector")
            } catch (t: Throwable) {
                ToolResult.fail("selectOption failed: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    private val checkCheckbox = object : BaseTool(
        "checkCheckbox", ToolAction.FILL_FORM,
        "Check a checkbox or radio input located by CSS selector. Args: {\"selector\": string (required)}."
    ) {
        override suspend fun execute(argsJson: String, ctx: ToolContext): ToolResult = setChecked(argsJson, true)
    }

    private val uncheckCheckbox = object : BaseTool(
        "uncheckCheckbox", ToolAction.FILL_FORM,
        "Uncheck a checkbox located by CSS selector. Args: {\"selector\": string (required)}."
    ) {
        override suspend fun execute(argsJson: String, ctx: ToolContext): ToolResult = setChecked(argsJson, false)
    }

    private suspend fun setChecked(argsJson: String, checked: Boolean): ToolResult {
        val a = ToolArgs.parse(argsJson)
        val selector = ToolArgs.str(a, "selector")?.trim().orEmpty()
        if (selector.isBlank()) return ToolResult.fail("Checkbox tool requires a 'selector' argument")
        return try {
            val ok = browser.setCheckbox(selector, checked)
            if (ok) ToolResult.ok(buildJsonObject { put("selector", selector); put("checked", checked) })
            else ToolResult.fail("Checkbox not found for selector: $selector")
        } catch (t: Throwable) {
            ToolResult.fail("Checkbox operation failed: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private val waitForElement = object : BaseTool(
        "waitForElement", ToolAction.NAVIGATE,
        "Wait until an element matching the CSS selector exists in the DOM of the active page. " +
            "Args: {\"selector\": string (required), \"timeoutMs\": number?} (default 10000). " +
            "Returns {\"appeared\": boolean}."
    ) {
        override suspend fun execute(argsJson: String, ctx: ToolContext): ToolResult {
            val a = ToolArgs.parse(argsJson)
            val selector = ToolArgs.str(a, "selector")?.trim().orEmpty()
            if (selector.isBlank()) return ToolResult.fail("waitForElement requires a 'selector' argument")
            val timeoutMs = ToolArgs.long(a, "timeoutMs", 10_000L).coerceIn(500L, 60_000L)
            return try {
                val appeared = browser.waitForElement(selector, timeoutMs)
                ToolResult.ok(buildJsonObject { put("selector", selector); put("appeared", appeared); put("timeoutMs", timeoutMs) })
            } catch (t: Throwable) {
                ToolResult.fail("waitForElement failed: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    private val submitForm = object : BaseTool(
        "submitForm", ToolAction.SUBMIT,
        "Submit an HTML form (uses requestSubmit so native validation and submit handlers run). " +
            "HIGH-RISK: always requires explicit user approval before execution. " +
            "Args: {\"selector\": string?} — CSS selector of the form; defaults to the form of the " +
            "focused element or the first form on the page."
    ) {
        override suspend fun execute(argsJson: String, ctx: ToolContext): ToolResult {
            val a = ToolArgs.parse(argsJson)
            val selector = ToolArgs.str(a, "selector")?.trim().takeIf { !it.isNullOrBlank() }
            val selJson = selector?.let { ToolArgs.jsString(it) } ?: "null"
            val script = "(function(){" +
                "var sel=$selJson;" +
                "var f=null;" +
                "if(sel){f=document.querySelector(sel);}" +
                "else{var ae=document.activeElement;f=(ae&&ae.form)?ae.form:document.querySelector('form');}" +
                "if(!f)return JSON.stringify({ok:false,error:'no form found'});" +
                "try{" +
                "if(typeof f.requestSubmit==='function'){f.requestSubmit();}else{f.submit();}" +
                "}catch(e){return JSON.stringify({ok:false,error:String(e)});}" +
                "return JSON.stringify({ok:true,method:(f.method||'get')});" +
                "})()"
            return try {
                val raw = browser.evaluateJs(script)
                val parsed = ToolArgs.parseJsResult(raw)
                if (parsed is kotlinx.serialization.json.JsonObject && parsed["ok"]?.toString() == "false") {
                    val err = (parsed["error"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "form submit failed"
                    ToolResult.fail("submitForm: $err")
                } else {
                    ToolResult.ok(buildJsonObject { put("result", parsed); put("submitted", true) })
                }
            } catch (t: Throwable) {
                ToolResult.fail("submitForm failed: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    companion object {
        val SUPPORTED_KEYS = listOf("Enter", "Tab", "Escape")
    }
}
