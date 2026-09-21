package com.motion.browser.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * Thin data layer over the browser tables (bookmarks / history / downloads).
 * Pure helpers ([dayStartOf], [fileNameFromResponse], [guessFileName]) are
 * JVM-testable; DB access runs on IO.
 */
object BrowserData {

    /** Start-of-day epoch millis for [ts] (device local time). */
    fun dayStartOf(ts: Long): Long = Calendar.getInstance().apply {
        timeInMillis = ts
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** URL with fragment/whitespace stripped — the history dedup key. */
    fun normalizeKey(url: String): String = url.substringBefore('#').trim()

    /**
     * Guesses a download file name from content disposition, then URL path,
     * then mime type, then a generic fallback (plan Chrome-like behavior).
     */
    fun fileNameFromResponse(url: String, contentDisposition: String?, mime: String?): String {
        contentDisposition?.let { cd ->
            Regex("filename\\s*=\\s*(\"([^\"]*)\"|[^;\\s]+)", RegexOption.IGNORE_CASE)
                .find(cd)?.let { m ->
                    val raw = m.groupValues.getOrNull(2).takeIf { it.isNotEmpty() }
                        ?: m.groupValues.getOrNull(1)?.trim('"', ' ')
                    raw?.takeIf { it.isNotBlank() }?.let { return sanitizeFileName(it) }
                }
        }
        mime?.takeIf { it.isNotBlank() }?.let { m ->
            when {
                m.equals("text/html", true) -> {}
                else -> {
                    val ext = android.webkit.MimeTypeMap.getSingleton()
                        .getExtensionFromMimeType(m.lowercase())
                    if (ext != null) {
                        val base = guessFileName(url)
                        return if (base.contains('.')) base else sanitizeFileName("$base.$ext")
                    }
                }
            }
        }
        return guessFileName(url).ifBlank { "download_${System.currentTimeMillis()}" }
    }

    /** Last path segment of [url], decoded; empty when the path ends with '/'. */
    fun guessFileName(url: String): String {
        val path = runCatching { java.net.URL(url).path }.getOrNull() ?: url
        val last = path.substringAfterLast('/').takeIf { it.isNotBlank() } ?: ""
        return sanitizeFileName(
            runCatching { java.net.URLDecoder.decode(last, "UTF-8") }.getOrDefault(last)
        )
    }

    fun sanitizeFileName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1f]"), "_").trim().take(120).ifBlank { "download" }

    /** Records a visit: one row per URL per day (re-visits bump the timestamp). */
    suspend fun recordVisit(url: String, title: String, db: MotionDatabase) = withContext(Dispatchers.IO) {
        runCatching {
            val key = normalizeKey(url)
            if (key.isBlank() || key.startsWith("data:") || key.startsWith("about:")) return@runCatching
            val now = System.currentTimeMillis()
            val dayStart = dayStartOf(now)
            val dao = db.historyDao()
            val existing = dao.sameUrlSameDay(key, dayStart)
            if (existing != null) {
                dao.update(existing.copy(title = title.ifBlank { existing.title }, visitedAt = now))
            } else {
                dao.insert(HistoryEntity(url = key, title = title.ifBlank { key }, visitedAt = now))
                dao.prune(2000)
            }
        }
    }

    suspend fun toggleBookmark(url: String, title: String, db: MotionDatabase): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val dao = db.bookmarkDao()
                if (dao.byUrlOnce(normalizeKey(url)) != null) {
                    dao.deleteByUrl(normalizeKey(url)); false
                } else {
                    dao.upsert(
                        BookmarkEntity(
                            url = normalizeKey(url),
                            title = title.ifBlank { normalizeKey(url) },
                            createdAt = System.currentTimeMillis(),
                        )
                    ); true
                }
            }.getOrDefault(false)
        }

    fun bookmarks(db: MotionDatabase, query: String): Flow<List<BookmarkEntity>> =
        if (query.isBlank()) db.bookmarkDao().all() else db.bookmarkDao().search(query.trim())

    fun history(db: MotionDatabase, query: String): Flow<List<HistoryEntity>> =
        if (query.isBlank()) db.historyDao().recent(500) else db.historyDao().search(query.trim())

    fun downloads(db: MotionDatabase): Flow<List<DownloadEntity>> = db.downloadDao().all()

    fun topSites(db: MotionDatabase): Flow<List<TopSiteRow>> = db.historyDao().topSites(8)
}
