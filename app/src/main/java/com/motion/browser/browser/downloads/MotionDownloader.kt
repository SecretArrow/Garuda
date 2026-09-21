package com.motion.browser.browser.downloads

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import com.motion.browser.MainActivity
import com.motion.browser.R
import com.motion.browser.ServiceLocator
import com.motion.browser.data.BrowserData
import com.motion.browser.data.DownloadEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Streaming download manager (Chrome parity): OkHttp GET → MediaStore
 * Downloads/Motion, live progress notification with Cancel, status persisted
 * in Room so the Downloads screen can show progress/history.
 */
object MotionDownloader {

    private const val CHANNEL_ID = "motion_downloads"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    /** Starts a download; returns its tracking id (also the Room PK). */
    fun enqueue(
        url: String,
        contentDisposition: String? = null,
        mime: String? = null,
        userFileName: String? = null,
    ): String {
        val app = runCatching { ServiceLocator.appCtx }.getOrNull() ?: return ""
        val id = "dl_${System.currentTimeMillis()}_${(url.hashCode() and 0xFFFF)}"
        val fileName = userFileName
            ?: BrowserData.fileNameFromResponse(url, contentDisposition, mime)
        scope.launch {
            runCatching {
                ServiceLocator.database.downloadDao().upsert(
                    DownloadEntity(
                        id = id, url = url, fileName = fileName, mime = mime.orEmpty(),
                        status = "RUNNING", createdAt = System.currentTimeMillis(),
                    )
                )
            }
            jobs[id] = scope.launch { run(app, id, url, fileName, mime) }
        }
        return id
    }

    fun cancel(id: String) {
        jobs[id]?.cancel()
        val app = runCatching { ServiceLocator.appCtx }.getOrNull() ?: return
        scope.launch {
            runCatching {
                ServiceLocator.database.downloadDao().byId(id)?.let {
                    if (it.status == "RUNNING") {
                        ServiceLocator.database.downloadDao().upsert(it.copy(status = "CANCELLED"))
                    }
                }
            }
        }
    }

    suspend fun delete(id: String) {
        jobs[id]?.cancel()
        runCatching { ServiceLocator.database.downloadDao().delete(id) }
    }

    private suspend fun run(app: Context, id: String, url: String, fileName: String, mime: String?) {
        val dao = ServiceLocator.database.downloadDao()
        val notifId = 10_000 + (id.hashCode() and 0x0FFFFFFF)
        try {
            createChannel(app)
            notifyProgress(app, notifId, fileName, 0, null)

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android) Motion Browser")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                val body = response.body ?: error("Empty response body")
                val total = body.contentLength()

                val resolver = app.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, mime ?: "application/octet-stream")
                    if (Build.VERSION.SDK_INT >= 29) {
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Motion")
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("MediaStore rejected $fileName")

                var copied = 0L
                resolver.openOutputStream(uri)?.use { out ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var lastNotify = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            copied += read
                            val now = System.currentTimeMillis()
                            if (now - lastNotify > 500) {
                                lastNotify = now
                                notifyProgress(app, notifId, fileName, copied, total.takeIf { it > 0 })
                            }
                        }
                    }
                } ?: error("Cannot open output stream")

                if (Build.VERSION.SDK_INT >= 29) {
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }
                val existing = dao.byId(id) ?: return
                dao.upsert(existing.copy(status = "DONE", uri = uri.toString(), sizeBytes = copied))
                notifyDone(app, notifId, fileName)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            runCatching { dao.byId(id)?.let { dao.upsert(it.copy(status = "CANCELLED")) } }
        } catch (e: Exception) {
            runCatching {
                dao.byId(id)?.let { dao.upsert(it.copy(status = "FAILED", error = e.message?.take(300))) }
            }
            notifyFailed(app, notifId, fileName, e.message.orEmpty())
        }
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, context.getString(R.string.notif_channel_downloads),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = context.getString(R.string.notif_channel_downloads_desc) }
            )
        }
    }

    private fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun notifyProgress(context: Context, id: Int, name: String, done: Long, total: Long?) {
        val builder = base(context, name)
            .setOngoing(true)
            .setProgress(100, if (total != null && total > 0) ((done * 100) / total).toInt() else 0, total == null)
        total?.let { builder.setContentText("${formatBytes(done)} / ${formatBytes(it)}") }
        notify(context, id, builder.build())
    }

    private fun notifyDone(context: Context, id: Int, name: String) {
        notify(context, id, base(context, name).setContentText("Completed").setAutoCancel(true).build())
    }

    private fun notifyFailed(context: Context, id: Int, name: String, error: String) {
        notify(
            context, id,
            base(context, name).setContentText("Failed: ${error.take(80)}").setAutoCancel(true).build()
        )
    }

    private fun base(context: Context, name: String): NotificationCompat.Builder =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_motion)
            .setContentTitle(name)
            .setContentIntent(openAppIntent(context))
            .addAction(0, "Cancel", cancelAllIntent(context))
            .setSilent(true)

    private fun cancelAllIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, DownloadCancelReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun notify(context: Context, id: Int, notification: Notification) {
        runCatching {
            context.getSystemService(NotificationManager::class.java).notify(id, notification)
        }
    }

    fun formatBytes(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(bytes.toDouble() / (1L shl 30))
        bytes >= 1L shl 20 -> "%.1f MB".format(bytes.toDouble() / (1L shl 20))
        bytes >= 1L shl 10 -> "%.1f KB".format(bytes.toDouble() / (1L shl 10))
        else -> "$bytes B"
    }
}

/** Notification "Cancel" target — cancels the newest running download. */
class DownloadCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        MotionDownloader.jobs.keys.maxByOrNull { it }?.let { MotionDownloader.cancel(it) }
    }
}
