package com.motion.browser

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.motion.browser.ai.core.ProviderConfig
import com.motion.browser.ai.core.ProviderType
import com.motion.browser.ai.core.RoleType
import com.motion.browser.data.entity.GoalEntity
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * AUTONOMOUS agent E2E on a real website (spec §16/§21):
 * a stored goal is run through [com.motion.browser.agent.runtime.MotionAgentRuntime.runGoal]
 * — the exact scheduled-goal path (observe → plan → act → re-observe, SafetyGuard, Room
 * persistence) — while the planner LLM is a scripted OpenAI-compatible server on device
 * loopback (network_security_config.xml permits cleartext to 127.0.0.1 only).
 *
 * Target: https://example.com/ (real website, stable content: "Example Domain").
 * If the emulator has no outbound network at test time, the same loop runs against the
 * bundled local test page so the result stays deterministic — the log line
 * "AUTONOMOUS_E2E target=... online=..." records which path executed.
 */
@RunWith(AndroidJUnit4::class)
class AutonomousAgentE2ETest {

    @Test
    fun autonomousAgent_drivesWebsite_extractsAndCompletesGoal(): Unit = runBlocking {
        ApplicationProvider.getApplicationContext<MotionApp>().onCreate()
        ActivityScenario.launch(MainActivity::class.java)
        Thread.sleep(1_500)
        val browser = ServiceLocator.requireBrowser()

        // Dedicated tab: the agent navigates THIS tab only; other tests (running in the
        // same process after us) keep seeing their pristine new-tab state.
        val agentTab = ServiceLocator.tabs.createTab()
        ServiceLocator.tabs.switchTab(agentTab)

        val server = MockWebServer()
        val ai = ServiceLocator.providerManager
        val cfgId = "mock_planner_e2e"
        val goalId = "e2e_goal_autonomous"
        try {
            // ---- 1) Target: real website when reachable, local asset fallback otherwise ----
            val realUrl = "https://example.com/"
            val online = probe(realUrl)
            val target = if (online) realUrl else "file:///android_asset/motion_test.html"
            val expectUrlFragment = if (online) "example.com" else "motion_test.html"
            val expectText = if (online) "Example Domain" else "Motion Test Page"
            println("AUTONOMOUS_E2E target=$target online=$online")

            // ---- 2) Scripted planner (OpenAI-compatible over device loopback) ----
            server.start()
            server.enqueue(plan("""{"steps":[{"tool":"openUrl","args":{"url":"$target"},"reason":"open the target site"}],"done":false,"summary":""}"""))
            server.enqueue(plan("""{"steps":[{"tool":"extractText","args":{},"reason":"read the page content"}],"done":false,"summary":""}"""))
            server.enqueue(plan("""{"steps":[],"done":true,"summary":"Extracted the main heading from the page"}"""))
            server.enqueue(plan("""{"steps":[],"done":true,"summary":"Done (safety net)"}"""))

            ai.saveConfig(
                ProviderConfig(
                    id = cfgId,
                    type = ProviderType.CUSTOM,
                    name = "E2E Mock Planner",
                    baseUrl = "http://127.0.0.1:${server.port}/v1",
                    model = "mock-planner",
                    enabled = true
                ),
                apiKey = "e2e-test-key"
            )
            ai.setRoleModel(RoleType.PLANNER, cfgId)

            // ---- 2b) Permission rules: explicitly allow both target domains.
            //     read=true is mandatory: a rule row with read=false blocks the whole domain.
            val permDao = ServiceLocator.database.permissionDao()
            permDao.upsert(
                com.motion.browser.data.entity.PermissionEntity(
                    id = "e2e_perm_example", domain = "example.com",
                    read = true, navigate = true
                )
            )
            permDao.upsert(
                com.motion.browser.data.entity.PermissionEntity(
                    id = "e2e_perm_file", domain = "file",
                    read = true, navigate = true
                )
            )

            // ---- 3) AUTONOMOUS run of a stored goal (same path as scheduled goals) ----
            ServiceLocator.database.goalDao().insert(
                GoalEntity(
                    id = goalId,
                    name = "E2E: extract website heading",
                    instruction = "Open $target and report the page's main heading text.",
                    enabled = true,
                    maxSteps = 10,
                    maxRuntimeMinutes = 8,
                    notificationPolicy = "SILENT"
                )
            )
            ServiceLocator.agentRuntime.runGoal(goalId)

            // ---- 4) Verify persisted run + real page effects ----
            val run = ServiceLocator.database.runDao().byGoal(goalId).first().last()
            assertEquals(
                "run must end SUCCESS (summary=${run.resultSummary})",
                "SUCCESS", run.status
            )

            val goalAfter = ServiceLocator.database.goalDao().getById(goalId)
            assertEquals("goal must end COMPLETED (summary=${run.resultSummary})", "COMPLETED", goalAfter?.status)

            val stepTools = ServiceLocator.database.stepDao().getByRun(run.id).map { it.tool }
            assertTrue("openUrl must have executed, got: $stepTools", stepTools.contains("openUrl"))
            assertTrue(
                "page must be the target, was: ${browser.currentUrl()}",
                browser.currentUrl().contains(expectUrlFragment)
            )
            val pageText = browser.extractText()
            assertTrue(
                "page text must be extractable, was: ${pageText.take(200)}",
                pageText.contains(expectText)
            )
        } finally {
            // Full cleanup: tests share one app process — leave no trace behind.
            runCatching { ai.deleteConfig(cfgId) }
            runCatching { ServiceLocator.database.goalDao().deleteById(goalId) }
            runCatching {
                ServiceLocator.tabs.closeTab(agentTab)
                if (ServiceLocator.tabs.activeTabId.value == null) ServiceLocator.tabs.createTab()
            }
            runCatching { server.shutdown() }
        }
    }

    /** Probe outbound network from the app process (4 s budget). */
    private fun probe(url: String): Boolean = try {
        val client = OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .build()
        client.newCall(Request.Builder().url(url).build()).execute().use { it.isSuccessful }
    } catch (t: Throwable) {
        false
    }

    /** One planner response: content = a Motion Plan JSON, wrapped in an OpenAI-compatible body. */
    private fun plan(contentJson: String): MockResponse {
        val body = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":${jsonString(contentJson)}}}]," +
            "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":10}}"
        return MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(body)
    }

    /** JSON-escape [s] into a JSON string literal (no serializer edge cases). */
    private fun jsonString(s: String): String {
        val sb = StringBuilder("\"")
        for (ch in s) when (ch) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (ch < ' ') sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
        }
        return sb.append('"').toString()
    }
}
