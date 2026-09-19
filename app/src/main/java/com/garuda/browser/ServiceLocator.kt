package com.garuda.browser

import android.app.NotificationManager
import android.content.Context
import androidx.room.Room
import com.garuda.browser.agent.action.CaptchaPipeline
import com.garuda.browser.agent.action.HumanHandoffCaptchaPipeline
import com.garuda.browser.agent.action.Notifier
import com.garuda.browser.agent.llm.Presets
import com.garuda.browser.agent.llm.ProviderChain
import com.garuda.browser.agent.llm.ProviderChainEntry
import com.garuda.browser.agent.runtime.AgentOrchestrator
import com.garuda.browser.browser.BrowserEngine
import com.garuda.browser.data.AgentSettingsRepository
import com.garuda.browser.data.GarudaDatabase
import com.garuda.browser.data.ScheduleEntity
import com.garuda.browser.data.TaskEntity
import com.garuda.browser.agent.runtime.ScheduleSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Composition root (Fase 2-6 wiring). Everything real: Room, DataStore,
 * Keystore-backed key vault, WebView engine with live DevTools socket, and the
 * orchestrator with its fallback chain.
 */
object ServiceLocator {

    @Volatile private var attached = false
    private lateinit var appContext: Context

    lateinit var database: GarudaDatabase
        private set
    lateinit var agentSettings: AgentSettingsRepository
        private set
    lateinit var keyVault: com.garuda.browser.agent.llm.KeyVault
        private set
    lateinit var browser: BrowserEngine
        private set
    var orchestrator: AgentOrchestrator? = null
        private set

    @Volatile private var currentTaskId: String? = null

    fun attach(context: Context) {
        if (attached) return
        synchronized(this) {
            if (attached) return
            appContext = context.applicationContext
            database = Room.databaseBuilder(appContext, GarudaDatabase::class.java, "garuda.db")
                .fallbackToDestructiveMigrationOnDowngrade()
                .build()
            agentSettings = AgentSettingsRepository(appContext)
            keyVault = com.garuda.browser.agent.llm.KeyVault(appContext)
            browser = BrowserEngine(appContext)
            orchestrator = buildOrchestrator()
            attached = true
        }
    }

    private val systemNotifier = Notifier { level, title, message ->
        runCatching {
            val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val importance = if (level == "warn") android.app.NotificationManager.IMPORTANCE_HIGH
            else android.app.NotificationManager.IMPORTANCE_DEFAULT
            val channel = android.app.NotificationChannel("garuda_alerts", "Garuda alerts", importance)
            nm.createNotificationChannel(channel)
            val notification = androidx.core.app.NotificationCompat.Builder(appContext, "garuda_alerts")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(message)
                .setAutoCancel(true)
                .build()
            nm.notify(message.hashCode(), notification)
        }
    }

    private val captchaPipeline: CaptchaPipeline = HumanHandoffCaptchaPipeline(systemNotifier)

    private fun buildOrchestrator(): AgentOrchestrator = AgentOrchestrator(
        db = database,
        settingsProvider = { agentSettings.settings.first() },
        sessionFor = { tabKey -> browser.agentSessionFor(tabKey) },
        chainFor = { providerChain() },
        approvals = { _, _, _ -> false }, // real approvals flow through the orchestrator's per-task gateway
        notifier = systemNotifier,
        captcha = captchaPipeline,
    )

    /** Default provider + fallback chain (plan Prompt 5 §7). */
    suspend fun providerChain(): ProviderChain {
        val dao = database.providerDao()
        val def = dao.default() ?: return ProviderChain(emptyList())
        val entries = mutableListOf(
            ProviderChainEntry(
                provider = Presets.adapterFor(def.protocol) { def.baseUrl },
                model = def.model,
                apiKey = keyVault.getKey(def.id),
            )
        )
        def.fallbackIds.split(',').map { it.trim() }.filter { it.isNotBlank() }.forEach { fid ->
            dao.allOnce().firstOrNull { it.id == fid }?.let { fb ->
                entries.add(
                    ProviderChainEntry(
                        provider = Presets.adapterFor(fb.protocol) { fb.baseUrl },
                        model = fb.model,
                        apiKey = keyVault.getKey(fb.id),
                    )
                )
            }
        }
        return ProviderChain(entries)
    }

    fun activeTaskId(): String? = currentTaskId

    fun setActiveTaskId(id: String) { currentTaskId = id }

    /** Crash/reboot resume: re-queue interrupted tasks and start the pump. */
    fun resumeUnfinishedTasks() {
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val now = System.currentTimeMillis()
                database.taskDao().unfinished().forEach { task ->
                    if (task.status != "QUEUED") {
                        database.taskDao().setStatus(task.id, "QUEUED", now)
                    }
                }
                // Due schedules → enqueue tasks (plan Prompt 6C TaskScheduler).
                database.scheduleDao().due(now).forEach { schedule ->
                    enqueueTask(schedule.goal)
                    database.scheduleDao().update(
                        schedule.copy(lastRunAt = now, nextRunAt = ScheduleSpec.nextRunAt(schedule.spec, now))
                    )
                }
            }
        }
    }

    /** Creates + queues a task; returns its id. */
    fun enqueueTask(goal: String, tabKey: String? = null): String {
        val id = "task_${System.currentTimeMillis()}_${(goal.hashCode() and 0xFFFF)}"
        val now = System.currentTimeMillis()
        val maxSteps = runBlockingIO { agentSettings.settings.first().maxStepsPerTask }
        CoroutineScope(Dispatchers.IO).launch {
            database.taskDao().insert(
                TaskEntity(id = id, goal = goal, status = "QUEUED", createdAt = now, updatedAt = now,
                    tabKey = tabKey, maxSteps = maxSteps)
            )
        }
        return id
    }

    /** Creates a task bound to the active tab (chat drawer "otomatiskan halaman ini"). */
    fun enqueueTaskOnActiveTab(goal: String): String {
        val tab = browser.activeTab
        return enqueueTask(goal, tabKey = tab?.id)
    }

    /** runBlocking on IO that never crashes the caller thread. */
    private fun <T> runBlockingIO(block: suspend () -> T): T =
        kotlinx.coroutines.runBlocking(Dispatchers.IO) { block() }
}
