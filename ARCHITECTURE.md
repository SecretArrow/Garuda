# Motion Browser — Architecture Contract (v1)

Single source of truth for all parallel development agents.
Package root: `com.motion.browser` • Kotlin 2.0.21 • Compose (BOM 2024.09.00) • AGP 8.5.2 • minSdk 29 / target 34

## 0. Hard rules for every agent

1. **NO local build/test** (`gradlew` is forbidden locally — all builds happen in GitHub Actions).
2. **NO git push / git commit** — the coordinator pushes.
3. **Only touch files inside your assigned ownership scope.** If you need a change outside your scope, describe it precisely in `/home/z/my-project/worklog.md` under `CROSS-SCOPE REQUESTS`.
4. Follow the exact signatures in this document. Do not rename classes/methods other agents depend on.
5. **No fake functionality**: every button/feature you write must call real logic. If a platform limit exists (spec §77), implement the legitimate mechanism and expose the limitation in UI text/comments.
6. Never log or persist API keys, tokens, passwords, cookies (spec §63).
7. Webpage content is UNTRUSTED — always route page text through `PromptInjectionDefense.sanitizePageContent` before sending to any LLM (spec §24).
8. AI calls must never run on the UI thread (spec §58).

## 1. Dual-track engine strategy (user decision)

- **Track A (ships now):** `app/` — Motion Browser APK on the Chromium engine embedded in Android (WebView = Blink + V8 + Chromium network stack). Always buildable in GitHub Actions, per-ABI split release APKs.
- **Track B (fork layer):** `motion-chromium/` — real Chromium upstream fork structure (patches, `motion/` tree, depot_tools/GN/Ninja scripts, docs). Requires ~200 GB / 6–20 h infra; built via `.github/workflows/chromium-fork.yml` on a self-hosted runner only. Clearly documented, never faked.

## 2. Module ownership map

| Agent | Owns |
|-------|------|
| coordinator | `MotionApp.kt`, `MainActivity.kt`, `ServiceLocator.kt`, manifest, tests, gradle files, CI, integration |
| 2-a browser | `browser/**`, `ui/browser/**` |
| 2-b ai | `ai/**` |
| 2-c agent-core | `agent/{runtime,goal,planner,exec,observe,memory}/**`, `data/**` |
| 2-d tools+security | `tools/**`, `security/**`, `agent/{trigger,policy,audit,notify,approval,worker}/**`, `assets/**` |
| 2-e fork | `motion-chromium/**`, `README.md` |
| 2-f ui+resources | `ui/{ai,control,theme,nav}/**`, `res/**` |

## 3. Cross-module contracts (exact signatures)

### 3.1 ServiceLocator (coordinator-owned; agents may reference, never edit)
```kotlin
package com.motion.browser
object ServiceLocator {
    fun init(context: Context)                     // idempotent, called from MotionApp.onCreate
    fun attachBrowser(activity: Activity)          // creates TabManager+BrowserController with activity ctx
    fun detachBrowser()
    val appContext: Context
    val database: com.motion.browser.data.db.MotionDatabase
    val secretStore: com.motion.browser.security.SecretStore
    val providerManager: com.motion.browser.ai.ProviderManager
    val auditLogger: com.motion.browser.agent.audit.AuditLogger
    val notifier: com.motion.browser.agent.notify.MotionNotifier
    val approvalQueue: com.motion.browser.agent.approval.ApprovalQueue
    val safetyGuard: com.motion.browser.security.SafetyGuard
    val toolRegistry: com.motion.browser.tools.ToolRegistry
    val agentRuntime: com.motion.browser.agent.runtime.MotionAgentRuntime
    val triggerManager: com.motion.browser.agent.trigger.TriggerManager
    val browser: com.motion.browser.browser.BrowserController?      // null before attachBrowser
    fun requireBrowser(): com.motion.browser.browser.BrowserController
    val tabs: com.motion.browser.browser.tabs.TabManager?
}
```

### 3.2 browser/ (agent 2-a)
```kotlin
package com.motion.browser.browser
data class Rect(val x: Int, val y: Int, val width: Int, val height: Int)
data class LinkInfo(val text: String, val href: String)
data class ElementInfo(val id: String, val role: String, val text: String, val ariaLabel: String?,
                       val visible: Boolean, val enabled: Boolean, val bounds: Rect, val confidence: Double)
data class PageObservation(val url: String, val title: String, val visibleText: String,
                           val links: List<LinkInfo>, val elements: List<ElementInfo>,
                           val metadata: Map<String, String>, val domSummary: String)
data class Tab(val id: String, val url: String, val title: String, val isPrivate: Boolean,
               val canGoBack: Boolean = false, val canGoForward: Boolean = false,
               val isLoading: Boolean = false, val progress: Int = 0)

package com.motion.browser.browser.tabs
class TabManager(activity: Activity) {          // owns one WebView per tab (AndroidView-hosted)
    val tabs: kotlinx.coroutines.flow.StateFlow<List<com.motion.browser.browser.Tab>>
    val activeTabId: kotlinx.coroutines.flow.StateFlow<String?>
    fun createTab(url: String = "about:newtab", isPrivate: Boolean = false): String
    fun closeTab(id: String); fun switchTab(id: String); fun duplicateTab(id: String)
    fun reopenClosed(): Boolean                  // restore last closed tab
    fun getWebView(id: String): android.webkit.WebView?
    val openCount: Int
}

package com.motion.browser.browser
class BrowserController(private val tabs: tabs.TabManager) {
    val userInteracted: kotlinx.coroutines.flow.SharedFlow<String>   // tabId when human touches agent-controlled tab (§28)
    suspend fun openUrl(url: String, newTab: Boolean = false, waitForLoad: Boolean = true): Boolean
    fun goBack(); fun goForward(); fun reload(); fun stopLoading()
    suspend fun evaluateJs(script: String): String          // JSON-encoded result
    suspend fun clickElement(selector: String? = null, text: String? = null): Boolean
    suspend fun clickCoordinates(x: Int, y: Int): Boolean
    suspend fun typeText(selector: String, text: String): Boolean
    suspend fun clearInput(selector: String): Boolean
    suspend fun selectOption(selector: String, value: String): Boolean
    suspend fun setCheckbox(selector: String, checked: Boolean): Boolean
    suspend fun scroll(dy: Int): Boolean
    suspend fun scrollToText(text: String): Boolean
    suspend fun findText(text: String): Boolean
    suspend fun extractText(): String
    suspend fun extractLinks(): List<LinkInfo>
    suspend fun extractImages(): List<String>               // absolute URLs
    suspend fun extractTables(): String                      // JSON array of tables
    suspend fun inspectDom(): String                         // summarized DOM JSON
    suspend fun observePage(): PageObservation
    suspend fun currentUrl(): String
    suspend fun currentTitle(): String
    suspend fun findInPage(text: String): Boolean
    suspend fun waitForElement(selector: String, timeoutMs: Long = 10_000): Boolean
    suspend fun waitForLoadFinished(timeoutMs: Long = 30_000): Boolean
    suspend fun takeScreenshot(): android.graphics.Bitmap?
    suspend fun downloadFile(url: String): Boolean           // via DownloadManager (real)
    suspend fun saveBookmark(title: String, url: String)     // into Room bookmarks table? -> use MemoryManager scope SITE; v1: EventBus + bookmarks in Room via data.dao.MemoryDao scope "BOOKMARK"
    // Internal: engine interface browser/engine/BrowserEngine.kt + WebViewEngine.kt
}
```
UI (package `com.motion.browser.ui.browser`): `BrowserScreen(onOpenControl: () -> Unit, onOpenAi: () -> Unit)` — top tab strip (horizontal, Chrome-style), omnibox row (back/fwd/reload + editable URL field), `AndroidView` hosting active WebView, NewTabPage composable when `url == "about:newtab"`, tab-switcher sheet, bottom slim bar with [+ tabs] [🤖 Motion AI]. Must honor Manual/Copilot/Autonomous mode indicator.

### 3.3 ai/ (agent 2-b)
```kotlin
package com.motion.browser.ai.core
enum class ProviderType { OPENAI_COMPATIBLE, ANTHROPIC, GEMINI, OPENROUTER, OLLAMA, CUSTOM }
enum class RoleType { PLANNER, ACTION, VISION, SUMMARIZATION, BACKGROUND }
data class LlmMessage(val role: String, val content: String)          // system|user|assistant
data class LlmOptions(val maxTokens: Int = 2048, val temperature: Double = 0.2,
                      val jsonMode: Boolean = false, val imageBase64: String? = null)
data class LlmRequest(val messages: List<LlmMessage>, val model: String, val options: LlmOptions)
data class LlmResponse(val ok: Boolean, val text: String = "", val error: String? = null,
                       val promptTokens: Long = 0, val completionTokens: Long = 0)
data class ProviderConfig(val id: String, val type: ProviderType, val name: String,
                          val baseUrl: String, val model: String, val enabled: Boolean = false,
                          val keyId: String = "")                      // keyId -> SecretStore; NEVER the key itself
interface AiProvider { val type: ProviderType
    suspend fun complete(cfg: ProviderConfig, req: LlmRequest): LlmResponse }

package com.motion.browser.ai
class ProviderManager(private val secretStore: com.motion.browser.security.SecretStore,
                      private val store: ProviderStore) {
    fun providers(): List<core.AiProvider>            // OpenAiCompatibleProvider covers OPENAI_COMPATIBLE/OPENROUTER/OLLAMA/CUSTOM
    val configs: kotlinx.coroutines.flow.StateFlow<List<core.ProviderConfig>>
    suspend fun saveConfig(cfg: core.ProviderConfig, apiKey: String?)   // key -> SecretStore under keyId
    suspend fun deleteConfig(id: String)
    suspend fun setRoleModel(role: core.RoleType, configId: String?)
    fun roleModel(role: core.RoleType): String?
    suspend fun chat(role: core.RoleType, messages: List<core.LlmMessage>, opts: core.LlmOptions = core.LlmOptions()): core.LlmResponse
    suspend fun chatWith(configId: String, messages: List<core.LlmMessage>, opts: core.LlmOptions = core.LlmOptions()): core.LlmResponse
}
class ProviderStore(context: Context)  // DataStore<Preferences> JSON persistence of configs + role map (no keys)
```
Adapters live in `ai/providers/`: `OpenAiCompatibleProvider`, `AnthropicProvider`, `GeminiProvider`. OkHttp, timeouts (connect 15 s / read 120 s), one retry with backoff on 429/5xx, error text never contains the key.

### 3.4 data/ (agent 2-c)
Room entities in `com.motion.browser.data.entity` (tables): `goals`, `runs`, `steps`, `triggers`, `memory`, `permissions`, `events`, `approvals` — fields per spec §14/§31/§39/§47/§61:
```kotlin
GoalEntity(id PK, name, instruction, enabled, scheduleJson, allowedDomains, blockedDomains,
           allowedActions, blockedActions, confirmationPolicy, notificationPolicy, memoryPolicy,
           maxSteps, maxRuntimeMinutes, maxDownloads, maxPosts, maxRetries,
           createdAt, updatedAt, lastRun, nextRun, status)
RunEntity(id PK, goalId?, instruction, mode /*MANUAL|COPILOT|AUTONOMOUS|INTERACTIVE*/, status,
          startedAt, endedAt?, resultSummary?)
StepEntity(id PK, runId, index, tool, argsJson, resultJson, status, at)
TriggerEntity(id PK, goalId, type /*ONCE|HOURLY|INTERVAL|DAILY|WEEKLY|WEEKDAYS|MONTHLY|CONTENT|STATE*/, scheduleJson, enabled, lastFired)
MemoryEntity(id PK, scope /*GLOBAL|GOAL|SITE|RUN|TEMP*/, key, value, goalId?, domain?, updatedAt)
PermissionEntity(id PK, domain, read, navigate, fillForms, submit, download, upload)
EventEntity(id auto PK, category /*MOTION_BROWSER|MOTION_AI|AGENT|AUTOMATION|SECURITY|NETWORK|DOWNLOAD|ERROR*/, message, detail?, at)
ApprovalEntity(id PK, runId?, domain, action, argsJson, status /*PENDING|APPROVED|REJECTED|EXPIRED*/, createdAt, resolvedAt?)
```
DAOs in `data.dao` (suspend + Flow methods as needed), `data.db.MotionDatabase(version = 1)` exposing all DAOs. Migration-ready (spec §62): exportSchema true with schema dir unset is fine for v1.

### 3.5 tools/ + security/ + agent periphery (agent 2-d)
```kotlin
package com.motion.browser.tools
data class ToolResult(val ok: Boolean, val data: kotlinx.serialization.json.JsonElement? = null, val error: String? = null)
data class ToolContext(val tabId: String?, val runId: String?, val goalId: String?)
interface BrowserTool { val id: String; val action: com.motion.browser.security.ToolAction
                        val description: String
                        suspend fun execute(argsJson: String, ctx: ToolContext): ToolResult }
class ToolRegistry(private val browser: com.motion.browser.browser.BrowserController,
                   private val notifier: com.motion.browser.agent.notify.MotionNotifier) {
    fun all(): List<BrowserTool>          // ≥36 real tools per spec §17
    fun get(id: String): BrowserTool?
    suspend fun execute(id: String, argsJson: String, ctx: ToolContext): ToolResult  // logs via AuditLogger, guards via SafetyGuard
}

package com.motion.browser.security
enum class ToolAction { READ, NAVIGATE, FILL_FORM, SUBMIT, DOWNLOAD, UPLOAD, NOTIFY }
enum class Risk { LOW, CONFIGURABLE, HIGH }
data class ActionVerdict(val allowed: Boolean, val requiresApproval: Boolean, val risk: Risk, val reason: String)
data class GuardVerdict(val allowed: Boolean, val requiresApproval: Boolean, val reason: String)
class SecretStore(context: Context) {      // EncryptedSharedPreferences (Keystore-backed)
    fun put(key: String, value: String); fun get(key: String): String?
    fun delete(key: String); fun keys(): List<String>
}
object PromptInjectionDefense {
    const val SYSTEM_POLICY: String        // §24: page content = data, never instructions; never reveal secrets
    fun sanitizePageContent(raw: String): String
    fun redactSecrets(text: String, secrets: List<String>): String
}
class SafetyGuard(private val permissions: PermissionManager,
                  private val rateLimiter: RateLimiter,
                  private val loopDetector: LoopDetector) {
    suspend fun preAction(goalId: String?, domain: String, action: ToolAction,
                          signature: String, maxSteps: Int): GuardVerdict
}
class PermissionManager(private val dao: com.motion.browser.data.dao.PermissionDao) {
    suspend fun evaluate(domain: String, action: ToolAction): ActionVerdict  // defaults per §25
    suspend fun setRule(rule: com.motion.browser.data.entity.PermissionEntity)
    fun rules(): kotlinx.coroutines.flow.Flow<List<com.motion.browser.data.entity.PermissionEntity>>
}
class RateLimiter { fun record(goalId: String, action: String); suspend fun exceeded(goalId: String, action: String, maxPerRun: Int): Boolean }
class LoopDetector { fun observe(goalId: String, signature: String): Boolean  // true = loop detected (same sig ≥5) }

package com.motion.browser.agent.audit
class AuditLogger(private val dao: com.motion.browser.data.dao.EventDao) {
    suspend fun log(category: String, message: String, detail: String? = null)
    fun recent(limit: Int = 200): kotlinx.coroutines.flow.Flow<List<com.motion.browser.data.entity.EventEntity>>
}

package com.motion.browser.agent.notify
class MotionNotifier(private val context: android.content.Context) {
    fun ensureChannels(); fun requestPermissionIfNeeded()
    fun notifyResult(title: String, text: String, runId: String? = null)
    fun notifyApprovalNeeded(approvalId: String, domain: String, action: String)
    fun notifyPaused(runId: String?, reason: String)
}

package com.motion.browser.agent.approval
class ApprovalQueue(private val dao: com.motion.browser.data.dao.ApprovalDao,
                    private val notifier: com.motion.browser.agent.notify.MotionNotifier) {
    suspend fun request(runId: String?, domain: String, action: String, argsJson: String): String
    fun pending(): kotlinx.coroutines.flow.Flow<List<com.motion.browser.data.entity.ApprovalEntity>>
    suspend fun resolve(id: String, approved: Boolean, editedArgsJson: String? = null)
    suspend fun awaitResolution(id: String, timeoutMs: Long = 3_600_000): com.motion.browser.data.entity.ApprovalEntity?
}

package com.motion.browser.agent.trigger
class TriggerManager(private val goalDao: com.motion.browser.data.dao.GoalDao,
                     private val triggerDao: com.motion.browser.data.dao.TriggerDao) {
    suspend fun scheduleForGoal(goal: com.motion.browser.data.entity.GoalEntity)   // creates TriggerEntity + WorkManager jobs
    suspend fun cancelForGoal(goalId: String)
    fun rescheduleAll()   // app start; WorkManager persists across reboots
}
// GoalTriggerWorker : CoroutineWorker — reads goalId, calls ServiceLocator.agentRuntime.runGoal
package com.motion.browser.agent.worker
class GoalTriggerWorker(context: Context, params: WorkerParameters) : androidx.work.CoroutineWorker
```

### 3.6 agent core (agent 2-c)
```kotlin
package com.motion.browser.agent.runtime
sealed class RuntimeStatus {
    object Idle : RuntimeStatus()
    data class Running(val runId: String, val goalId: String?, val step: Int, val lastAction: String) : RuntimeStatus()
    data class WaitingApproval(val approvalId: String, val runId: String) : RuntimeStatus()
    data class Paused(val runId: String?, val reason: String) : RuntimeStatus()
    data class Failed(val runId: String?, val reason: String) : RuntimeStatus()
}
class MotionAgentRuntime(
    private val planner: com.motion.browser.agent.planner.Planner,
    private val executor: com.motion.browser.agent.exec.ActionExecutor,
    private val memory: com.motion.browser.agent.memory.MemoryManager,
    private val guard: com.motion.browser.security.SafetyGuard,
    private val approvals: com.motion.browser.agent.approval.ApprovalQueue,
    private val audit: com.motion.browser.agent.audit.AuditLogger,
    private val notifier: com.motion.browser.agent.notify.MotionNotifier,
    private val runs: com.motion.browser.data.dao.RunDao,
    private val goals: com.motion.browser.data.dao.GoalDao,
    private val steps: com.motion.browser.data.dao.StepDao
) {
    val status: kotlinx.coroutines.flow.StateFlow<RuntimeStatus>
    suspend fun runInteractive(instruction: String, tabId: String?): String   // runId; observe→plan→execute loop
    suspend fun runGoal(goalId: String, triggerId: String? = null)
    fun pause(); fun resume(); fun stopAll()   // §27 emergency stop, state kept for resume
    val emergencyStopActive: kotlinx.coroutines.flow.StateFlow<Boolean>
}

package com.motion.browser.agent.planner
data class PlanStep(val tool: String, val argsJson: String, val reason: String)
data class Plan(val steps: List<PlanStep>, val done: Boolean, val summary: String)
data class ScheduleSpec(val kind: String, val timeOfDay: String?, val daysOfWeek: List<Int>?, val intervalMinutes: Int?)
data class GoalDraft(val name: String, val instruction: String, val schedule: ScheduleSpec?,
                     val domains: List<String>, val actions: List<String>, val notify: Boolean, val explanation: String)
class Planner(private val ai: com.motion.browser.ai.ProviderManager, private val audit: com.motion.browser.agent.audit.AuditLogger) {
    suspend fun plan(instruction: String, observation: com.motion.browser.browser.PageObservation,
                     recentSteps: List<com.motion.browser.data.entity.StepEntity>,
                     goal: com.motion.browser.data.entity.GoalEntity?, screenshotBase64: String? = null): Plan
    suspend fun draftGoalFromText(text: String): GoalDraft     // §37 NL → structured preview
}
package com.motion.browser.agent.exec
class ActionExecutor(private val registry: com.motion.browser.tools.ToolRegistry,
                     private val approvals: com.motion.browser.agent.approval.ApprovalQueue,
                     private val audit: com.motion.browser.agent.audit.AuditLogger) {
    suspend fun execute(runId: String, goalId: String?, plan: com.motion.browser.agent.planner.Plan,
                        tabId: String?, goal: com.motion.browser.data.entity.GoalEntity?): String  // terminal status
}
package com.motion.browser.agent.observe
class PageInspector(private val browser: com.motion.browser.browser.BrowserController) {
    suspend fun observe(tabId: String? = null): com.motion.browser.browser.PageObservation
    suspend fun screenshotBase64(tabId: String? = null): String?
}
package com.motion.browser.agent.memory
class MemoryManager(private val dao: com.motion.browser.data.dao.MemoryDao) {
    suspend fun put(scope: String, key: String, value: String, goalId: String? = null, domain: String? = null)
    suspend fun get(scope: String, key: String, goalId: String? = null, domain: String? = null): String?
    fun byGoal(goalId: String): kotlinx.coroutines.flow.Flow<List<com.motion.browser.data.entity.MemoryEntity>>
}
```

### 3.7 ui/ (agents 2-a, 2-f) — routes wired by coordinator
```kotlin
ui.browser.BrowserScreen(onOpenControl: () -> Unit, onOpenAi: () -> Unit, onOpenTabSwitcher: () -> Unit)
ui.ai.AiPanel(onDismiss: () -> Unit)                       // chat + §73 quick actions + STOP ALL AGENTS
ui.control.ControlCenterScreen(onBack: () -> Unit)          // §36 overview counters + sections
ui.control.GoalEditorScreen(goalId: String?, onBack: () -> Unit)   // §37 draft preview [Edit][Activate]
ui.control.ProvidersScreen(onBack: () -> Unit)              // §9 configs + role→model mapping
ui.control.LogsScreen(onBack: () -> Unit)                   // §63 structured log viewer
ui.theme.MotionTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit)
```

## 4. Threading & lifecycle rules
- All WebView calls on `Dispatchers.Main`; LLM/Room on IO via suspend.
- Agent loop = observe → plan → execute → observe… with per-goal caps (maxSteps, maxRuntimeMinutes) enforced by ActionExecutor; loop detection via LoopDetector (§49); exponential backoff retries (§40).
- WorkManager: network Connected constraint, battery-not-low; no unlimited background promises (§33/§34).
- State survives process death via Room (§32); resume = re-observe, never blind replay.

## 5. Definition of done for this repo push
- `./gradlew :app:lintDebug :app:testDebugUnitTest` green in CI
- `:app:connectedDebugAndroidTest` green on emulator (install → launch → asset page load, no FATAL)
- `:app:assembleRelease :app:bundleRelease` produce signed per-ABI APKs + AAB artifacts
- motion-chromium/ documents real fork infrastructure (never claims built status)
