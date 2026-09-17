package com.motion.browser.agent.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.motion.browser.MainActivity
import com.motion.browser.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Local Android notifications for agent status, results and approvals (spec §30/§31).
 * All notifications are LOCAL — Motion never sends anything to a server.
 *
 * Contract (ARCHITECTURE.md §3.5) — exact signatures:
 *   class MotionNotifier(private val context: Context) {
 *       fun ensureChannels(); fun requestPermissionIfNeeded()
 *       fun notifyResult(title: String, text: String, runId: String? = null)
 *       fun notifyApprovalNeeded(approvalId: String, domain: String, action: String)
 *       fun notifyPaused(runId: String?, reason: String)
 *   }
 */
class MotionNotifier(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Create the three notification channels. Idempotent; call from MotionApp.onCreate. */
    fun ensureChannels() {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_AGENT, "Motion Agent", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Agent run results and summaries"
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_APPROVAL, "Motion Approvals", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "High-risk actions waiting for user approval"
                enableLights(true)
                lightColor = ACCENT
                enableVibration(true)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, "Motion Status", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Quiet status updates: paused, resumed, background progress"
            }
        )
    }

    /**
     * No-op when permission is granted or API < 33. On API 33+ without POST_NOTIFICATIONS we
     * cannot request it from a non-Activity context — the Activity-owned runtime request belongs
     * to the coordinator (MainActivity). We surface an AUTOMATION audit entry so the gap is visible.
     */
    fun requestPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) return
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) return
        scope.launch {
            runCatching {
                com.motion.browser.ServiceLocator.auditLogger.log(
                    "AUTOMATION",
                    "Notification permission not granted — approvals and results cannot surface. " +
                        "Request POST_NOTIFICATIONS from MainActivity (runtime permission flow is Activity-owned)."
                )
            }
        }
    }

    /** Run result/summary notification (channel motion_agent). */
    fun notifyResult(title: String, text: String, runId: String? = null) {
        val notification = NotificationCompat.Builder(context, CHANNEL_AGENT)
            .setSmallIcon(R.drawable.ic_stat_motion)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(launchIntent())
            .build()
        safeNotify("result_${runId ?: "app"}", ID_RESULT, notification)
    }

    /** High-importance approval request (channel motion_approval, lights + vibration). */
    fun notifyApprovalNeeded(approvalId: String, domain: String, action: String) {
        val text = "$domain wants to run '$action'. Tap to review and approve or reject."
        val notification = NotificationCompat.Builder(context, CHANNEL_APPROVAL)
            .setSmallIcon(R.drawable.ic_stat_motion)
            .setContentTitle("Approval needed")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(launchIntent())
            .build()
        safeNotify("approval_$approvalId", approvalId.hashCode(), notification)
    }

    /** Quiet status notification, e.g. paused for approval or emergency stop (channel motion_status). */
    fun notifyPaused(runId: String?, reason: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_motion)
            .setContentTitle("Agent paused")
            .setContentText(reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(launchIntent())
            .build()
        safeNotify("paused_${runId ?: "app"}", ID_PAUSED, notification)
    }

    private fun launchIntent(): PendingIntent? {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent(context, MainActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun enabled(): Boolean =
        runCatching { NotificationManagerCompat.from(context).areNotificationsEnabled() }.getOrDefault(false)

    private fun safeNotify(tag: String, id: Int, notification: Notification) {
        if (!enabled()) {
            Log.i(TAG, "Notification skipped (permission not granted) tag=$tag")
            return
        }
        runCatching {
            NotificationManagerCompat.from(context).notify(tag, id, notification)
        }.onFailure {
            Log.w(TAG, "Notification failed tag=$tag: ${it.javaClass.simpleName}")
        }
    }

    companion object {
        private const val TAG = "MotionNotify"
        const val CHANNEL_AGENT = "motion_agent"
        const val CHANNEL_APPROVAL = "motion_approval"
        const val CHANNEL_STATUS = "motion_status"
        private const val ID_RESULT = 2001
        private const val ID_PAUSED = 2002
        private val ACCENT = android.graphics.Color.argb(255, 33, 150, 243)
    }
}
