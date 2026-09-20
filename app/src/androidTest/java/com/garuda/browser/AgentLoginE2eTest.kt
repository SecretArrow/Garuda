package com.garuda.browser

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.garuda.browser.agent.action.ActionExecutor
import com.garuda.browser.agent.action.CdpPageControl
import com.garuda.browser.agent.action.DomainRateLimiter
import com.garuda.browser.agent.action.HumanHandoffCaptchaPipeline
import com.garuda.browser.agent.action.Notifier
import com.garuda.browser.agent.llm.LlmMessage
import com.garuda.browser.agent.llm.LlmProvider
import com.garuda.browser.agent.llm.LlmRequest
import com.garuda.browser.agent.runtime.ProviderChain
import com.garuda.browser.agent.runtime.ProviderChainEntry
import com.garuda.browser.agent.llm.StreamEvent
import com.garuda.browser.agent.llm.ToolCallRequest
import com.garuda.browser.agent.runtime.AgentOrchestrator
import com.garuda.browser.agent.runtime.AgentSession
import com.garuda.browser.browser.BrowserEngine
import com.garuda.browser.data.AgentSettings
import com.garuda.browser.data.GarudaDatabase
import com.garuda.browser.data.TaskEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Full agent E2E on a real page (plan Prompt 4 + 6 acceptance): a scripted
 * provider drives the orchestrator through perceive → type → type → click →
 * finish on a real WebView via real CDP trusted input. Login must succeed.
 */
@RunWith(AndroidJUnit4::class)
class AgentLoginE2eTest {

    /** Scripted "LLM": inspects the latest page state and decides like an agent. */
    private class ScriptedProvider : LlmProvider {
        override val protocol = "scripted"
        var step = 0

        override suspend fun chat(request: LlmRequest, apiKey: String?): Flow<StreamEvent> = flow {
            val lastPage = request.messages.lastOrNull()?.content.orEmpty()
            val markFor: (predicate: (String) -> Boolean) -> String? = { predicate ->
                Regex("e\\d+ (INPUT|TEXTAREA)[^\\n]*")
                    .findAll(lastPage)
                    .map { it.value }
                    .firstOrNull(predicate)
                    ?.substringBefore(' ')
            }
            val user = markFor { it.contains("TEXT") || it.contains("text=") }
                ?: markFor { it.contains("ph=\"Username\"") }
            val pass = markFor { it.contains("password") || it.contains("ph=\"Password\"") }
            val login = Regex("e\\d+ BUTTON[^\n]*\"[^\"]*[Ll]og in[^\"]*\"").findAll(lastPage)
                .map { it.value.substringBefore(' ') }.firstOrNull()
                ?: Regex("e\\d+ BUTTON").findAll(lastPage).map { it.value.substringBefore(' ') }.firstOrNull()

            when (step++) {
                0 -> emit(StreamEvent.ToolCall(ToolCallRequest("c0", "type",
                    JSONObject().put("markId", user ?: "e2").put("text", "garuda").toString())))
                1 -> emit(StreamEvent.ToolCall(ToolCallRequest("c1", "type",
                    JSONObject().put("markId", pass ?: "e3").put("text", "hunter2").toString())))
                2 -> emit(StreamEvent.ToolCall(ToolCallRequest("c2", "click",
                    JSONObject().put("markId", login ?: "e4").toString())))
                else -> emit(StreamEvent.ToolCall(ToolCallRequest("c3", "finish",
                    JSONObject().put("summary", "Logged into Acme Portal as garuda").toString())))
            }
        }

        override suspend fun listModels(baseUrl: String, apiKey: String?) = listOf("scripted")
    }

    @Test
    fun agentCompletesLoginOnRealPage() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, GarudaDatabase::class.java)
            .allowMainThreadQueries().build()
        val engine = ServiceLocator.browser
        withContext(Dispatchers.Main) {
            engine.createTab("file:///android_asset/login.html")
        }
        androidx.test.core.app.ActivityScenario.launch(
            com.garuda.browser.MainActivity::class.java
        )

        val session = com.garuda.browser.CdpTrustedInputTest.attachSessionWithDiagnostics(engine, tab)
        assertTrue("CDP session required — ${com.garuda.cdp.DevToolsLocator.diagnostics()}", session != null)
        session!!.waitForLoad(15_000)

        val agentSession = object : AgentSession {
            override suspend fun page() = CdpPageControl(session!!)
            override val tabOps: ActionExecutor.TabOps = object : ActionExecutor.TabOps {
                override suspend fun openTab(url: String): Int { engine.createTab(url); return engine.tabs.size - 1 }
                override suspend fun switchTo(index: Int): Boolean = engine.switchTo(index)
                override suspend fun closeTab(index: Int?): Boolean = engine.closeTab(index ?: engine.activeIndex)
                override suspend fun currentTabIndex(): Int = engine.activeIndex
            }
        }

        val orchestrator = AgentOrchestrator(
            db = db,
            settingsProvider = { AgentSettings(requireConfirmRisky = false, minActionIntervalMs = 100) },
            sessionFor = { agentSession },
            chainFor = { ProviderChain(listOf(ProviderChainEntry(ScriptedProvider(), "scripted-1", null))) },
            approvals = { _, _, _ -> true },
            notifier = { _, _, _ -> },
            captcha = HumanHandoffCaptchaPipeline { _, _, _ -> },
        )

        val taskId = "e2e_login"
        val now = System.currentTimeMillis()
        db.taskDao().insert(TaskEntity(id = taskId, goal = "Log into the Acme Portal test page",
            status = "QUEUED", createdAt = now, updatedAt = now, tabKey = tab.id, maxSteps = 25))

        orchestrator.launch(taskId, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO))

        // Poll until the task finishes (or fails with the audit trail in the error).
        withTimeout(120_000) {
            var task = db.taskDao().byId(taskId)!!
            while (task.status !in listOf("DONE", "FAILED", "STOPPED")) {
                kotlinx.coroutines.delay(500)
                task = db.taskDao().byId(taskId)!!
            }
            assertEquals("Task must finish successfully; error=${task.error}", "DONE", task.status)
            assertTrue("Summary must mention login", task.summary.orEmpty().contains("Logged"))
        }

        // Verify the page state: real trusted login happened.
        val loggedIn = session!!.evaluate("window.__loggedIn === true").optBoolean("value", false)
        assertEquals("Agent must have logged in via trusted input", true, loggedIn)
        val welcome = session.evaluate("document.getElementById('result').textContent").optString("value")
        assertEquals("Welcome, Garuda!", welcome)

        val steps = db.stepDao().forTask(taskId)
        assertTrue("Audit trail must contain actions", steps.any { it.kind == "action" })
        session.close()
        db.close()
        Unit
    }
}
