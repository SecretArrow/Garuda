package com.garuda.browser.agent.runtime

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.garuda.browser.MainActivity
import com.garuda.browser.R
import com.garuda.browser.ServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground runner (plan Prompt 6C): keeps queued tasks executing with a
 * persistent notification (current task, step N, Pause/Stop actions), survives
 * backgrounding, and auto-resumes interrupted tasks (also after reboot).
 */
class GarudaAgentService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pumpJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIF_ID, buildNotification("Agent idle", "Waiting for tasks…"))
        pumpJob = scope.launch { pump() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> ServiceLocator.orchestrator?.let { o ->
                ServiceLocator.activeTaskId()?.let { o.pause(it) }
            }
            ACTION_STOP -> ServiceLocator.orchestrator?.let { o ->
                ServiceLocator.activeTaskId()?.let { o.stop(it) }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        pumpJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Task pump: pulls QUEUED tasks (max parallel = maxParallelTabs) and runs
     * them through the orchestrator; re-enqueues after reboot via BOOT receiver.
     */
    private suspend fun pump() {
        while (kotlin.coroutines.coroutineContext.isActive) {
            runCatching {
                val orchestrator = ServiceLocator.orchestrator ?: return@runCatching
                val tasks = ServiceLocator.database.taskDao().unfinished()
                val queued = tasks.filter { it.status == "QUEUED" }
                val running = tasks.filter { it.status == "RUNNING" || it.status == "PLANNING" }
                val settings = ServiceLocator.agentSettings.first()
                val capacity = (settings.maxParallelTabs - running.size).coerceAtLeast(0)
                queued.take(capacity).forEach { task ->
                    ServiceLocator.setActiveTaskId(task.id)
                    orchestrator.launch(task.id, scope)
                }
                val active = running.firstOrNull() ?: queued.firstOrNull()
                updateNotification(
                    when {
                        active == null -> "Agent idle"
                        else -> "Task: ${active.goal.take(40)}… (step ${active.stepCount}/${active.maxSteps})"
                    },
                    when {
                        active?.status == "WAITING_HUMAN" -> "Waiting for your answer — tap to open"
                        else -> "Garuda runs in the background"
                    },
                )
            }
            delay(2000)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = android.app.NotificationChannel(
                CHANNEL_ID, getString(R.string.notif_channel_agent),
                android.app.NotificationManager.IMPORTANCE_LOW,
            ).apply { description = getString(R.string.notif_channel_agent_desc) }
            getSystemService(android.app.NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val pause = PendingIntent.getService(
            this, 1, Intent(this, GarudaAgentService::class.java).setAction(ACTION_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 2, Intent(this, GarudaAgentService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Pause", pause)
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun updateNotification(title: String, text: String) {
        runCatching {
            getSystemService(android.app.NotificationManager::class.java)
                .notify(NOTIF_ID, buildNotification(title, text))
        }
    }

    companion object {
        private const val CHANNEL_ID = "garuda_agent"
        private const val NOTIF_ID = 41
        private const val ACTION_PAUSE = "com.garuda.browser.PAUSE_TASK"
        private const val ACTION_STOP = "com.garuda.browser.STOP_TASK"

        fun start(context: Context) {
            val intent = Intent(context, GarudaAgentService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
            else context.startService(intent)
        }
    }
}

/** Auto-resume after reboot (plan Prompt 6C: resume setelah crash/reboot). */
class BootResumeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            runCatching {
                ServiceLocator.resumeUnfinishedTasks()
                GarudaAgentService.start(context)
            }
        }
    }
}

/**
 * Cron-ish schedule parser (plan Prompt 6C TaskScheduler): "daily 08:00",
 * "every 6h", "weekly mon 09:30". Kept dependency-free on purpose.
 */
object ScheduleSpec {
    fun nextRunAt(spec: String, from: Long = System.currentTimeMillis()): Long {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = from }
        val parts = spec.trim().lowercase().split(Regex("\\s+"))
        return when {
            parts.firstOrNull() == "every" && parts.getOrNull(1)?.endsWith("h") == true -> {
                val hours = parts[1].removeSuffix("h").toLongOrNull() ?: 1
                from + hours * 3_600_000
            }
            parts.firstOrNull() == "every" && parts.getOrNull(1)?.endsWith("m") == true -> {
                val minutes = parts[1].removeSuffix("m").toLongOrNull() ?: 30
                from + minutes * 60_000
            }
            parts.firstOrNull() == "daily" -> {
                val (h, m) = parts.getOrNull(1)?.split(":")?.map { it.toIntOrNull() ?: 0 } ?: listOf(8, 0)
                cal.set(java.util.Calendar.HOUR_OF_DAY, h)
                cal.set(java.util.Calendar.MINUTE, m)
                cal.set(java.util.Calendar.SECOND, 0)
                if (cal.timeInMillis <= from) cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
                cal.timeInMillis
            }
            parts.firstOrNull() == "weekly" -> {
                val dayName = parts.getOrNull(1)?.take(3) ?: "mon"
                val days = listOf("sun", "mon", "tue", "wed", "thu", "fri", "sat")
                val target = days.indexOf(dayName).takeIf { it >= 0 } ?: 1
                val (h, m) = parts.getOrNull(2)?.split(":")?.map { it.toIntOrNull() ?: 0 } ?: listOf(9, 0)
                cal.set(java.util.Calendar.HOUR_OF_DAY, h)
                cal.set(java.util.Calendar.MINUTE, m)
                cal.set(java.util.Calendar.SECOND, 0)
                while (cal.get(java.util.Calendar.DAY_OF_WEEK) - 1 != target || cal.timeInMillis <= from) {
                    cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
                }
                cal.timeInMillis
            }
            else -> from + 3_600_000
        }
    }
}
