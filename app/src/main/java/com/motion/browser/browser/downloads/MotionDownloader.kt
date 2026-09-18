package com.motion.browser.browser.downloads

import android.app.Notification
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.motion.browser.ServiceLocator
import com.motion.browser.data.entity.DownloadEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Motion's own download manager: HTTP(S) downloads with pause/resume/
 * cancel/retry, live progress in the Room `downloads` table and system
 * notifications. Files land in the public `Download/Motion` folder via
 * MediaStore (minSdk 29 → no legacy storage permission needed).
 *
 * Honest scope: download jobs live in the app process (no foreground service);
 * they run while the app is alive, and a process death leaves the row PAUSED
 * so the user can retry/resume later.
 */
class MotionDownloader(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "motion_downloads"
        const val NOTIF_BASE_ID = 47_000
        private const val PROGRESS_TICK_MS = 500L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val pauseFlags = ConcurrentHashMap.newKeySet<String>()
    private val cancelFlags = ConcurrentHashMap.newKeySet<String>()

    private val dao get() = ServiceLocator.database.downloadDao()
    private val settings get() = ServiceLocator.settingsRepository.current

    // ------------------------------------------------------------------ public API

    /** Adds a new download and starts it. Returns the record id. */
    fun enqueue(
        url: String,
        fileNameHint: String? = null,
        mimeTypeHint: String? = null,
        contentDisposition: String? = null,
    ): String {
        val id = UUID.randomUUID().toString()
        val fileName = sanitizeName(
            fileNameHint
                ?: fileNameFromDisposition(contentDisposition)
                ?: fileNameFromUrl(url)
        )
        ensureChannel()
        scope.launch {
            dao.upsert(
                DownloadEntity(
                    id = id, url = url, fileName = fileName,
                    mimeType = mimeTypeHint ?: "application/octet-stream"
                )
            )
            start(id)
        }
        return id
    }

    fun pause(id: String) {
        pauseFlags.add(id)
    }

    fun resume(id: String) {
        scope.launch {
            val d = dao.getById(id) ?: return@launch
            if (d.status != "PAUSED") return@launch
            start(id)
        }
    }

    fun cancel(id: String) {
        cancelFlags.add(id)
        jobs[id]?.cancel()
    }

    fun retry(id: String) {
        scope.launch {
            val d = dao.getById(id) ?: return@launch
            if (d.status == "RUNNING") return@launch
            pauseFlags.remove(id)
            cancelFlags.remove(id)
            // Keep the MediaStore entry only when resuming a paused job; failed /
            // cancelled jobs restart from scratch with a fresh entry.
            val keepPath = if (d.status == "PAUSED") d.filePath else ""
            val bytes = if (d.status == "PAUSED") d.bytesDownloaded else 0L
            dao.updateProgress(id, "PENDING", bytes, d.totalBytes, null, keepPath, null)
            start(id)
        }
    }

    fun delete(id: String, alsoDeleteFile: Boolean = true) {
        cancel(id)
        scope.launch {
            val d = dao.getById(id) ?: return@launch
            if (alsoDeleteFile) runCatching { deleteMedia(d.filePath) }
            dao.deleteById(id)
        }
    }

    /** Rows claiming RUNNING from a dead process become resumable (state repair). */
    fun markOrphansPaused() {
        scope.launch {
            runCatching {
                ServiceLocator.database.downloadDao().all().first().filter { it.status == "RUNNING" }.forEach {
                    ServiceLocator.database.downloadDao()
                        .updateProgress(it.id, "PAUSED", it.bytesDownloaded, it.totalBytes, "Interrupted", it.filePath, null)
                }
            }
        }
    }

    // ------------------------------------------------------------------ engine

    private suspend fun start(id: String) {
        if (jobs[id]?.isActive == true) return
        val row = dao.getById(id) ?: return
        if (settings.downloadWifiOnly && isMetered()) {
            dao.updateProgress(id, "PAUSED", row.bytesDownloaded, row.totalBytes, "Waiting for Wi-Fi", row.filePath, null)
            notifyState(id, row.fileName, 0, -1, waiting = true)
            return
        }
        jobs[id] = scope.launch {
            var bytes = if (row.status == "PAUSED") row.bytesDownloaded else 0L
            var uriString = row.filePath
            try {
                dao.updateProgress(id, "RUNNING", bytes, row.totalBytes, null, uriString, null)
                val conn = (URL(row.url).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 15_000
                    readTimeout = 20_000
                    if (bytes > 0) setRequestProperty("Range", "bytes=$bytes-")
                }
                val code = conn.responseCode
                if (code !in 200..299 && code != 206) throw IOException("HTTP $code")
                val total = if (bytes > 0) {
                    val rangeEnd = conn.getHeaderFieldLong("Content-Range", -1L)
                    if (rangeEnd > 0) rangeEnd else conn.contentLengthLong + bytes
                } else {
                    conn.contentLengthLong.takeIf { it > 0 } ?: -1L
                }

                if (uriString.isBlank()) {
                    uriString = createMediaEntry(row.fileName, row.mimeType)
                        ?: throw IOException("Cannot create download entry")
                }
                val out = openAppend(uriString) ?: throw IOException("Cannot open output stream")
                val buf = ByteArray(64 * 1024)
                var lastTick = 0L
                conn.inputStream.use { input ->
                    out.use { output ->
                        var paused = false
                        while (true) {
                            if (cancelFlags.remove(id)) throw DownloadCancelled()
                            val n = input.read(buf)
                            if (n == -1) break
                            output.write(buf, 0, n)
                            bytes += n
                            val now = System.currentTimeMillis()
                            if (now - lastTick > PROGRESS_TICK_MS) {
                                lastTick = now
                                dao.updateProgress(id, "RUNNING", bytes, total, null, uriString, null)
                                notifyState(id, row.fileName, bytes, total)
                            }
                            if (pauseFlags.remove(id)) {
                                paused = true
                                break
                            }
                        }
                        if (paused) {
                            dao.updateProgress(id, "PAUSED", bytes, total, null, uriString, null)
                            notifyState(id, row.fileName, bytes, total, paused = true)
                            return@launch
                        }
                    }
                }
                finalizeEntry(uriString)
                dao.updateProgress(id, "COMPLETED", bytes, total, null, uriString, System.currentTimeMillis())
                notifyState(id, row.fileName, bytes, total, done = true)
                if (!settings.downloadNotifications) NotificationManagerCompat.from(context).cancel(notifId(id))
            } catch (e: DownloadCancelled) {
                runCatching { deleteMedia(uriString) }
                dao.updateProgress(id, "CANCELLED", 0, row.totalBytes, "Cancelled", "", null)
                NotificationManagerCompat.from(context).cancel(notifId(id))
            } catch (e: CancellationException) {
                // Job cancelled (process teardown) → leave a resumable PAUSED row.
                dao.updateProgress(id, "PAUSED", bytes, row.totalBytes, null, uriString, null)
                throw e
            } catch (t: Throwable) {
                dao.updateProgress(id, "FAILED", bytes, row.totalBytes, t.message ?: "Download failed", uriString, null)
                notifyState(id, row.fileName, bytes, row.totalBytes, failed = true)
            }
        }
    }

    private class DownloadCancelled : Exception()

    // ------------------------------------------------------------------ MediaStore plumbing

    private fun createMediaEntry(fileName: String, mimeType: String): String? = runCatching {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Motion")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return null
        uri.toString()
    }.getOrNull()

    private fun openAppend(uriString: String): OutputStream? = runCatching {
        val uri = Uri.parse(uriString)
        val mode = if (Build.VERSION.SDK_INT >= 30) "wa" else "w"
        context.contentResolver.openOutputStream(uri, mode)
    }.getOrNull()

    private fun finalizeEntry(uriString: String) = runCatching {
        val values = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
        context.contentResolver.update(Uri.parse(uriString), values, null, null)
    }

    private fun deleteMedia(uriString: String) {
        if (uriString.isBlank()) return
        runCatching { context.contentResolver.delete(Uri.parse(uriString), null, null) }
    }

    // ------------------------------------------------------------------ misc helpers

    private fun isMetered(): Boolean =
        runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.isActiveNetworkMetered
        }.getOrDefault(false)

    private fun notifId(id: String) = NOTIF_BASE_ID + (id.hashCode() and 0x7FFFFF)

    private fun fileNameFromUrl(url: String): String {
        val path = url.substringBefore('#').substringBefore('?').substringAfterLast('/')
        return path.ifBlank { "download_${System.currentTimeMillis()}" }.take(120)
    }

    private fun fileNameFromDisposition(disposition: String?): String? {
        if (disposition.isNullOrBlank()) return null
        val utf8 = Regex("filename\\*=UTF-8''([^;]+)").find(disposition)?.groupValues?.get(1)
        val plain = Regex("filename=\"?([^\";]+)\"?").find(disposition)?.groupValues?.get(1)
        return (utf8?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: plain)?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun sanitizeName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "download_${System.currentTimeMillis()}" }

    // ------------------------------------------------------------------ notifications

    fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = android.app.NotificationChannel(
                CHANNEL_ID, "Downloads", android.app.NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Motion Browser download progress" }
            context.getSystemService(android.app.NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun notifyState(
        id: String,
        fileName: String,
        bytes: Long,
        total: Long,
        done: Boolean = false,
        paused: Boolean = false,
        waiting: Boolean = false,
        failed: Boolean = false,
    ) {
        if (!settings.downloadNotifications) return
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        runCatching {
            val indeterminate = total <= 0
            val text = when {
                done -> "Download complete"
                paused -> "Paused — ${formatBytes(bytes)}"
                waiting -> "Waiting for Wi-Fi"
                failed -> "Download failed"
                else -> "${formatBytes(bytes)}${if (total > 0) " / ${formatBytes(total)}" else ""}"
            }
            val open = PendingIntent.getActivity(
                context, 0,
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(MediaStore.Downloads.EXTERNAL_CONTENT_URI, "*/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val notif: Notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(
                    (if (done || failed) android.R.drawable.stat_sys_download_done
                    else android.R.drawable.stat_sys_download)
                )
                .setContentTitle(fileName)
                .setContentText(text)
                .setOngoing(!done && !failed)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .apply {
                    if (!done && !failed && !paused && !waiting) {
                        setProgress(
                            100,
                            if (indeterminate) 0 else ((bytes * 100) / total).toInt().coerceIn(0, 100),
                            indeterminate
                        )
                    } else {
                        setProgress(0, 0, false)
                    }
                }
                .build()
            NotificationManagerCompat.from(context).notify(notifId(id), notif)
        }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024f)
        bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1024f / 1024f)
        else -> String.format(Locale.US, "%.2f GB", bytes / 1024f / 1024f / 1024f)
    }
}
