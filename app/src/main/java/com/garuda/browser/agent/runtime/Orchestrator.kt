package com.garuda.browser.agent.runtime

import com.garuda.browser.agent.action.ActionExecutor
import com.garuda.browser.agent.action.ApprovalGateway
import com.garuda.browser.agent.action.CaptchaPipeline
import com.garuda.browser.agent.action.DomainRateLimiter
import com.garuda.browser.agent.action.Notifier
import com.garuda.browser.agent.action.PageControl
import com.garuda.browser.agent.llm.LlmMessage
import com.garuda.browser.agent.llm.LlmProvider
import com.garuda.browser.agent.llm.LlmRequest
import com.garuda.browser.agent.llm.StreamEvent
import com.garuda.browser.agent.llm.ToolCallRequest
import com.garuda.browser.agent.perception.Perception
import com.garuda.browser.agent.perception.PageState
import com.garuda.browser.data.AgentSettings
import com.garuda.browser.data.GarudaDatabase
import com.garuda.browser.data.StepEntity
import com.garuda.browser.data.TaskEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Per-task execution surface handed to the orchestrator by the app. */
interface AgentSession {
    suspend fun page(): PageControl
    val tabOps: ActionExecutor.TabOps
}

/**
 * Live stream of agent narration for the chat drawer (plan Prompt 7 §1):
 * text deltas from the LLM plus tool-call badges, consumed by the UI.
 */
object ChatBus {
    private val _events = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 512)
    val events: kotlinx.coroutines.flow.SharedFlow<String> = _events
    fun emit(text: String) { _events.tryEmit(text) }
}

/** One link in the fallback chain. */
data class ProviderChainEntry(val provider: LlmProvider, val model: String, val apiKey: String?)

/** Resolved provider chain: default + ordered fallbacks. */
data class ProviderChain(val entries: List<ProviderChainEntry>)

/**
 * The heart of the system (plan Prompt 6A): per-task state machine
 * QUEUED → PLANNING → RUNNING → WAITING_HUMAN → PAUSED → DONE/FAILED,
 * the perceive → LLM → execute → verify loop, budgets, stuck detection,
 * context compaction and Room checkpointing for crash resume.
 */
class AgentOrchestrator(
    private val db: GarudaDatabase,
    private val settingsProvider: suspend () -> AgentSettings,
    private val sessionFor: suspend (tabKey: String?) -> AgentSession,
    private val chainFor: suspend () -> ProviderChain,
    private val approvals: ApprovalGateway,
    private val notifier: Notifier,
    private val captcha: CaptchaPipeline,
) {
    private val runningTasks = ConcurrentHashMap<String, kotlinx.coroutines.Job>()
    private val stepSeq = ConcurrentHashMap<String, AtomicInteger>()

    /** Human answers for ask_human / risky approvals, keyed by taskId. */
    private val pendingApprovals = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    fun isRunning(taskId: String): Boolean = runningTasks.containsKey(taskId)

    /** Gateway wired into ActionExecutor: pauses the task until the user answers. */
    inner class TaskApprovalGateway(private val taskId: String) : ApprovalGateway {
        override suspend fun requestApproval(taskId: String, question: String, detail: String): Boolean {
            db.taskDao().setStatus(taskId, "WAITING_HUMAN", System.currentTimeMillis())
            notifier.notify("warn", "Garuda needs you", question)
            audit(taskId, "human", "approval requested", question.take(300), false)
            val deferred = CompletableDeferred<Boolean>()
            pendingApprovals[taskId] = deferred
            val allowed = runCatching { deferred.await() }.getOrDefault(false)
            pendingApprovals.remove(taskId)
            db.taskDao().setStatus(taskId, "RUNNING", System.currentTimeMillis())
            return allowed
        }
    }

    /** Called by the UI to answer a WAITING_HUMAN task. */
    fun answerApproval(taskId: String, allowed: Boolean): Boolean {
        val deferred = pendingApprovals[taskId] ?: return false
        return deferred.complete(allowed)
    }

    fun pause(taskId: String) {
        runningTasks[taskId]?.cancel()
        runningTasks.remove(taskId)
        val now = System.currentTimeMillis()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            runCatching { db.taskDao().setStatus(taskId, "PAUSED", now) }
        }
    }

    fun stop(taskId: String) {
        pendingApprovals[taskId]?.complete(false)
        runningTasks[taskId]?.cancel()
        runningTasks.remove(taskId)
        val now = System.currentTimeMillis()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            runCatching { db.taskDao().fail(taskId, "stopped by user", "STOPPED", now) }
        }
    }

    /** Enqueues (or resumes) a task and starts its loop. */
    fun launch(taskId: String, scope: kotlinx.coroutines.CoroutineScope) {
        if (runningTasks.containsKey(taskId)) return
        val job = scope.launchTask(taskId)
        runningTasks[taskId] = job
        job.invokeOnCompletion { runningTasks.remove(taskId) }
    }

    private fun CoroutineScope.launchTask(taskId: String) =
        launch(Dispatchers.IO) { runTask(taskId) }

    // ------------------------------------------------------------- main loop

    private suspend fun runTask(taskId: String) {
        val settings = settingsProvider()
        var task = db.taskDao().byId(taskId) ?: return
        db.taskDao().setStatus(taskId, "PLANNING", System.currentTimeMillis())
        val session = runCatching { sessionFor(task.tabKey) }.getOrElse {
            db.taskDao().fail(taskId, "no browser session: ${it.message}", "FAILED", System.currentTimeMillis())
            return
        }

        val executor = ActionExecutor(
            page = { session.page() },
            currentState = { latestState.getOrDefault(taskId, null) },
            tabOps = session.tabOps,
            rateLimiter = DomainRateLimiter(settings.minActionIntervalMs.toLong()),
            audit = { kind, label, detail, ok -> audit(taskId, kind, label, detail, ok) },
            approvals = TaskApprovalGateway(taskId),
            notifier = notifier,
            captcha = captcha,
            requireConfirmRisky = settings.requireConfirmRisky,
            taskId = taskId,
        )

        val chain = runCatching { chainFor() }.getOrElse {
            db.taskDao().fail(taskId, "no LLM provider configured", "FAILED", System.currentTimeMillis())
            return
        }
        if (chain.entries.isEmpty()) {
            db.taskDao().fail(taskId, "no LLM provider configured", "FAILED", System.currentTimeMillis())
            return
        }

        db.taskDao().setStatus(taskId, "RUNNING", System.currentTimeMillis())
        stepSeq[taskId] = AtomicInteger(task.stepCount)

        var providerIndex = 0
        var providerFailures = 0
        var promptTokens = task.promptTokens
        var completionTokens = task.completionTokens
        val recentActionSignatures = ArrayDeque<String>()
        latestState.remove(taskId)

        try {
            loop@ while (true) {
                val current = db.taskDao().byId(taskId) ?: break
                if (current.status == "PAUSED" || current.status == "STOPPED") break

                // ---- budget guards (plan 6A) -------------------------------
                if (current.stepCount >= current.maxSteps) {
                    db.taskDao().fail(taskId, "step budget exhausted (${current.maxSteps})", "FAILED", System.currentTimeMillis())
                    break
                }
                if (promptTokens + completionTokens > settings.tokenBudgetPerTask) {
                    db.taskDao().fail(taskId, "token budget exhausted", "FAILED", System.currentTimeMillis())
                    break
                }

                // ---- 1. perceive --------------------------------------------
                val perceiveResult = runCatching { Perception.observe(session.page()) }
                val state = perceiveResult.getOrNull()
                if (state == null) {
                    val err = perceiveResult.exceptionOrNull()
                    db.taskDao().fail(taskId, "perceive failed: ${err?.message}", "FAILED", System.currentTimeMillis())
                    break
                }
                latestState[taskId] = state
                audit(taskId, "perceive", state.url, "${state.elements.size} elements", true)

                // ---- 2. LLM decides (one tool call) -------------------------
                val entry = chain.entries[providerIndex]
                val (provider, model, apiKey) = entry
                val history = buildHistory(taskId, current)
                val messages = ArrayList<LlmMessage>()
                if (current.compactionSummary != null) {
                    messages.add(LlmMessage("user", "Earlier steps summary: ${current.compactionSummary}"))
                }
                messages.addAll(history)
                messages.add(LlmMessage("user", renderTurn(state, current.goal)))

                val request = LlmRequest(
                    model = model,
                    system = AgentSystemPrompt.full(),
                    messages = messages,
                    tools = com.garuda.browser.agent.action.ToolSchemas.all,
                )

                var textBuffer = StringBuilder()
                var toolCall: ToolCallRequest? = null
                var llmError: String? = null
                runCatching {
                    provider.chat(request, apiKey).collect { event ->
                        when (event) {
                            is StreamEvent.TextDelta -> {
                                textBuffer.append(event.text)
                                ChatBus.emit(event.text)
                            }
                            is StreamEvent.ToolCall -> {
                                if (toolCall == null) toolCall = event.call
                                ChatBus.emit("[tool] ${event.call.name} ${event.call.argumentsJson.take(80)}")
                            }
                            is StreamEvent.Usage -> {
                                promptTokens += event.promptTokens
                                completionTokens += event.completionTokens
                            }
                            is StreamEvent.Failure -> llmError = event.message
                            StreamEvent.Done -> Unit
                        }
                    }
                }.onFailure { llmError = it.message }

                if (toolCall == null) {
                    providerFailures++
                    audit(taskId, "llm", model, "no tool call${llmError?.let { ": $it" }.orEmpty()}", false)
                    if (providerFailures >= 3 && providerIndex < chain.entries.size - 1) {
                        providerIndex++ // fallback chain (plan Prompt 5 §7)
                        providerFailures = 0
                        audit(taskId, "llm", "fallback", "switching to provider #$providerIndex", true)
                        continue
                    }
                    if (providerFailures >= 6) {
                        db.taskDao().fail(taskId, llmError ?: "LLM produced no tool call", "FAILED", System.currentTimeMillis())
                        break
                    }
                    messages.add(LlmMessage("assistant", textBuffer.toString()))
                    messages.add(LlmMessage("user", "Choose exactly one tool call now."))
                    continue
                }
                providerFailures = 0
                val call = toolCall!!
                audit(taskId, "llm", model, "${call.name} ${call.argumentsJson.take(200)}", true)

                // ---- stuck detection: identical action 3x in a row (plan 6A)
                val sig = "${call.name}:${JSONObject(call.argumentsJson).optString("markId")}" +
                    ":" + JSONObject(call.argumentsJson).optString("url") +
                    JSONObject(call.argumentsJson).optString("text")
                recentActionSignatures.addLast(sig)
                if (recentActionSignatures.size > 3) recentActionSignatures.removeFirst()
                if (recentActionSignatures.size == 3 && recentActionSignatures.toSet().size == 1) {
                    db.taskDao().fail(taskId, "stuck: repeated identical action 3x ($sig)", "FAILED", System.currentTimeMillis())
                    break
                }

                // ---- 3. execute ---------------------------------------------
                val result = executor.execute(call)
                audit(taskId, "action", call.name, result.summary + (if (result.ok) "" else " [FAIL]"), result.ok)

                // ---- 4. verify + record step --------------------------------
                val seq = (stepSeq[taskId]?.incrementAndGet() ?: 1)
                db.stepDao().insert(StepEntity(
                    taskId = taskId, seq = seq, kind = "action", label = call.name,
                    detail = result.summary.take(600), at = System.currentTimeMillis(), ok = result.ok,
                ))

                // ---- 5. compaction check (history > 70% window) -------------
                if (historyChars(taskId) > COMPACTION_THRESHOLD_CHARS) {
                    compact(taskId, provider, model, apiKey)
                }

                // ---- 6. bookkeeping + finish --------------------------------
                val steps = stepSeq[taskId]?.get() ?: current.stepCount + 1
                db.taskDao().updateProgress(taskId, steps, promptTokens, completionTokens)
                if (call.name == "finish") {
                    db.taskDao().finish(taskId, result.summary, System.currentTimeMillis())
                    notifier.notify("info", "Garuda task done", result.summary.take(120))
                    break
                }
                if (!result.ok) {
                    // Failed actions don't immediately fail the task — the LLM sees
                    // the failure in the next turn; hard failures come from budgets.
                    recentActionSignatures.clear()
                }
                kotlinx.coroutines.delay(150)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            val status = db.taskDao().byId(taskId)?.status
            if (status == "RUNNING") db.taskDao().setStatus(taskId, "PAUSED", System.currentTimeMillis())
        } catch (e: Exception) {
            db.taskDao().fail(taskId, "crash: ${e.message}".take(300), "FAILED", System.currentTimeMillis())
        } finally {
            runningTasks.remove(taskId)
            latestState.remove(taskId)
        }
    }

    // ---------------------------------------------------------------- helpers

    private val latestState = ConcurrentHashMap<String, PageState>()

    private fun renderTurn(state: PageState, goal: String): String =
        "TASK: $goal\n\nCURRENT PAGE:\n${state.serialize()}"

    /** Rebuilds short-term history from the audit log (post-compaction kept short). */
    private suspend fun buildHistory(taskId: String, task: TaskEntity): List<LlmMessage> {
        val steps = db.stepDao().forTask(taskId)
            .filter { it.kind == "action" }
            .takeLast(HISTORY_WINDOW_STEPS)
        return steps.map { step ->
            if (step.ok) {
                LlmMessage("assistant", "Tool call: ${step.label} → ${step.detail.take(150)}")
            } else {
                LlmMessage("user", "Previous tool ${step.label} FAILED: ${step.detail.take(150)}. Choose another approach.")
            }
        }
    }

    private suspend fun historyChars(taskId: String): Int =
        db.stepDao().forTask(taskId).sumOf { it.detail.length + it.label.length }

    /**
     * Context compaction (plan Prompt 6A): asks the LLM itself to summarize old
     * steps; the summary is checkpointed on the task, the loop continues fresh.
     */
    private suspend fun compact(taskId: String, provider: LlmProvider, model: String, apiKey: String?) {
        val steps = db.stepDao().forTask(taskId).takeLast(30)
        val digest = steps.joinToString("\n") { "${it.kind}/${it.label}: ${it.detail.take(120)}" }
        val request = LlmRequest(
            model = model, system = "Summarize agent browsing steps tersely for context reuse. Keep URLs, markIds, decisions, pending items.",
            messages = listOf(LlmMessage("user", digest.take(12_000))),
            tools = emptyList(), maxTokens = 512,
        )
        var summary = ""
        runCatching {
            provider.chat(request, apiKey).collect { ev ->
                if (ev is StreamEvent.TextDelta) summary += ev.text
            }
        }
        if (summary.isNotBlank()) {
            db.taskDao().setCompaction(taskId, summary.take(2000))
            audit(taskId, "compact", "context", "${digest.length} chars → ${summary.length} chars", true)
        }
    }

    private suspend fun audit(taskId: String, kind: String, label: String, detail: String, ok: Boolean) {
        val seq = (stepSeq[taskId]?.get() ?: 0) + 1
        db.stepDao().insert(StepEntity(
            taskId = taskId, seq = seq, kind = kind, label = label.take(80),
            detail = detail.take(900), at = System.currentTimeMillis(), ok = ok,
        ))
    }

    companion object {
        private const val HISTORY_WINDOW_STEPS = 8
        private const val COMPACTION_THRESHOLD_CHARS = 24_000
    }
}
