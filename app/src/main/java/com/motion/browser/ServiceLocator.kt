package com.motion.browser

import android.app.Activity
import android.content.Context
import com.motion.browser.agent.approval.ApprovalQueue
import com.motion.browser.agent.audit.AuditLogger
import com.motion.browser.agent.exec.ActionExecutor
import com.motion.browser.agent.memory.MemoryManager
import com.motion.browser.agent.notify.MotionNotifier
import com.motion.browser.agent.observe.PageInspector
import com.motion.browser.agent.planner.Planner
import com.motion.browser.agent.runtime.MotionAgentRuntime
import com.motion.browser.agent.trigger.TriggerManager
import com.motion.browser.ai.ProviderManager
import com.motion.browser.ai.ProviderStore
import com.motion.browser.browser.BrowserController
import com.motion.browser.browser.tabs.TabManager
import com.motion.browser.data.db.MotionDatabase
import com.motion.browser.security.LoopDetector
import com.motion.browser.security.PermissionManager
import com.motion.browser.security.RateLimiter
import com.motion.browser.security.SafetyGuard
import com.motion.browser.security.SecretStore
import com.motion.browser.tools.ToolRegistry
import com.motion.browser.ui.control.GoalPlannerHolder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Service locator (ARCHITECTURE.md §3.1) — the ONLY place subsystems are wired.
 *
 * Lifecycle:
 *  - init(context) from MotionApp.onCreate: persistence, secrets, AI layer, audit,
 *    notifier, safety guard, a headless-capable browser core (WebView on the
 *    application context so scheduled goals can still observe pages when the UI is
 *    closed — honest scope: no HTTP-auth dialogs headless), the full agent runtime
 *    and the trigger scheduler.
 *  - attachBrowser/detachBrowser from MainActivity: UI-facing no-ops kept for the
 *    contract; the TabManager already exists so Compose simply hosts its views.
 *
 * Run recovery (spec §32): init marks runs left RUNNING by a dead process as FAILED
 * and re-arms persisted WorkManager triggers.
 */
object ServiceLocator {

    private val initialized = AtomicBoolean(false)
    private lateinit var appContextSafe: Context

    @Volatile
    private var browserAttached: Activity? = null

    // ------------------------------------------------------------------ lifecycle

    fun init(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        appContextSafe = context.applicationContext

        // Crash/restart recovery: stale RUNNING runs → FAILED (honest state).
        kotlinx.coroutines.runBlocking {
            runCatching { database.runDao().failStaleRuns(System.currentTimeMillis(), "Process terminated before completion") }
        }

        notifier.ensureChannels()

        // Headless-capable browser core: WebView on the application context so the
        // agent can also work when the UI is closed (honest scope: no HTTP-auth
        // dialogs headless; file picks always require the visible UI per spec §43).
        val manager = tabs
        browserRef.compareAndSet(null, BrowserController(manager))
        if (manager.openCount == 0) manager.createTab()

        triggerManager.rescheduleAll()
        GoalPlannerHolder.provider = { planner }
    }

    fun attachBrowser(activity: Activity) {
        browserAttached = activity
        // Ensure at least one tab exists so the UI is never empty.
        if (tabs.openCount == 0) tabs.createTab()
    }

    fun detachBrowser() {
        browserAttached = null
    }

    val appContext: Context get() = appContextSafe

    // ------------------------------------------------------------------ persistence + security

    val database: MotionDatabase by lazy { MotionDatabase.getInstance(appContext) }

    val secretStore: SecretStore by lazy { SecretStore(appContext) }

    /** User-facing browser settings (theme, privacy, web compat, downloads). */
    val settingsRepository: com.motion.browser.data.SettingsRepository by lazy {
        com.motion.browser.data.SettingsRepository(appContext)
    }

    // ------------------------------------------------------------------ AI layer

    val providerManager: ProviderManager by lazy {
        ProviderManager(secretStore, ProviderStore(appContext))
    }

    // ------------------------------------------------------------------ agent periphery

    val notifier: MotionNotifier by lazy { MotionNotifier(appContext) }

    val auditLogger: AuditLogger by lazy { AuditLogger(database.eventDao()) }

    val approvalQueue: ApprovalQueue by lazy { ApprovalQueue(database.approvalDao(), notifier) }

    private val permissionManager: PermissionManager by lazy { PermissionManager(database.permissionDao()) }

    val safetyGuard: SafetyGuard by lazy { SafetyGuard(permissionManager, RateLimiter(), LoopDetector()) }

    // ------------------------------------------------------------------ browser core (headless-capable)

    private val tabsRef = AtomicReference<TabManager?>(null)

    val tabs: TabManager
        get() = tabsRef.get() ?: synchronized(this) {
            tabsRef.get() ?: TabManager(appContext).also { tabsRef.set(it) }
        }

    val browser: BrowserController? get() = browserRef.get()

    fun requireBrowser(): BrowserController =
        browserRef.get() ?: error("Browser core not initialized — ServiceLocator.init failed")

    private val browserRef = AtomicReference<BrowserController?>(null)

    // ------------------------------------------------------------------ agent runtime

    val toolRegistry: ToolRegistry by lazy { ToolRegistry(requireBrowser(), notifier) }

    val planner: Planner by lazy { Planner(providerManager, auditLogger) }

    val agentRuntime: MotionAgentRuntime by lazy {
        MotionAgentRuntime(
            planner = planner,
            executor = ActionExecutor(toolRegistry, approvalQueue, auditLogger),
            memory = MemoryManager(database.memoryDao()),
            guard = safetyGuard,
            approvals = approvalQueue,
            audit = auditLogger,
            notifier = notifier,
            runs = database.runDao(),
            goals = database.goalDao(),
            steps = database.stepDao()
        )
    }

    val triggerManager: TriggerManager by lazy { TriggerManager(database.goalDao(), database.triggerDao()) }
}
