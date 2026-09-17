package com.motion.browser.tools.impl

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import com.motion.browser.ServiceLocator
import com.motion.browser.agent.notify.MotionNotifier
import com.motion.browser.browser.BrowserController
import com.motion.browser.data.entity.MemoryEntity
import com.motion.browser.security.ToolAction
import com.motion.browser.tools.BrowserTool
import com.motion.browser.tools.BaseTool
import com.motion.browser.tools.ToolArgs
import com.motion.browser.tools.ToolContext
import com.motion.browser.tools.ToolResult
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * System-level tools (spec §17).
 *
 * Honest capabilities & limitations:
 *  - downloadFile: real Android DownloadManager enqueue (via BrowserController).
 *  - uploadFile: NOT automated on purpose — Android requires the user to pick the file via the
 *    system file picker (§43). The tool always returns an explanatory error instead of faking it.
 *  - saveBookmark: BrowserController persistence (Room via MemoryDao scope BOOKMARK).
 *  - addHistory: writes a local history row (Room via MemoryDao, scope HISTORY) — no network.
 *  - notifyUser: local Android notification through MotionNotifier.
 *  - listDownloads: real DownloadManager query (downloads known to this app).
 */
internal class SystemTools(
    private val browser: BrowserController,
    private val notifier: MotionNotifier
) {

    fun all(): List<BrowserTool> = listOf(
        downloadFile, uploadFile, saveBookmark, addHistory, notifyUser, listDownloads
    )

    private val downloadFile = object : BaseTool(
        "downloadFile", ToolAction.DOWNLOAD,
        "Download a file from a URL using the Android DownloadManager (visible in the system " +
            "downloads; no silent downloads). Args: {\"url\": string (required)}. " +
            "Requires user approval by default (§25)."
    ) {
        override suspend fun execute(argsJson: String, ctx: ToolContext): ToolResult {
            val a = ToolArgs.parse(argsJson)
            val url = ToolArgs.str(a, "url")?.trim().orEmpty()
            if (url.isBlank()) return ToolResult.fail("downloadFile requires a non-empty 'url' argument")
            return try {
                val ok = browser.downloadFile(url)
                if (ok) ToolResult.ok(buildJsonObject { put("url", url); put("started", true) })
                else ToolResult.fail("DownloadManager refused to start the download for: $url")
            } catch (t: Throwable) {
                ToolResult.fail("downloadFile failed: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    private val uploadFile = object : BaseTool(
        "uploadFile", ToolAction.UPLOAD,
        "Website file upload is NOT automated: Android security policy (§43) requires the user to " +
            "pick the file via the system file picker. This tool always returns an error explaining " +
            "the limitation — ask the user to use the upload button on the page."
    ) {
        override suspend fun execute(argsJson: String, ctx: ToolContext): ToolResult {
            // Honest limitation (§43/§77): no fake upload. Android cannot let an app inject a file
            // into a WebView file chooser without the user actively choosing it.
            return ToolResult.fail(
                "Website file upload requires the user to pick the file — " +
                    "use the upload button on the page (Android security policy)"
            )
        }
    }

    private val saveBookmark = object : BaseTool(
        "saveBookmark", ToolAction.NAVIGATE,
        "Save a bookmark locally. Args: {\"title\": string (required), \"url\": string?} — " +
            "url defaults to the current page. Local-only, no network."
    ) {
        override suspend fun execute(argsJson: String, ctx: ToolContext): ToolResult {
            val a = ToolArgs.parse(argsJson)
            val title = ToolArgs.str(a, "title")?.trim().orEmpty()
            if (title.isBlank()) return ToolResult.fail("saveBookmark requires a non-empty 'title' argument")
            val url = ToolArgs.str(a, "url")?.trim().takeIf { !it.isNullOrBlank() } ?: browser.currentUrl()
            return try {
                browser.saveBookmark(title, url)
                ToolResult.ok(buildJsonObject { put("title", title); put("url", url); put("saved", true) })
            } catch (t: Throwable) {
                ToolResult.fail("saveBookmark failed: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    private val addHistory = object : BaseTool(
        "addHistory", ToolAction.NAVIGATE,
        "Record a visited URL in the local browsing history (no network, Room storage). " +
            "Args: {\"url\": string (required), \"title\": string?}."
    ) {
        override suspend fun execute(argsJson: String, ctx: ToolContext): ToolResult {
            val a = ToolArgs.parse(argsJson)
            val url = ToolArgs.str(a, "url")?.trim().orEmpty()
            if (url.isBlank()) return ToolResult.fail("addHistory requires a non-empty 'url' argument")
            val title = ToolArgs.str(a, "title")?.trim().takeIf { !it.isNullOrBlank() } ?: url
            val now = System.currentTimeMillis()
            return try {
                val dao = ServiceLocator.database.memoryDao()
                dao.upsert(
                    MemoryEntity(
                        id = UUID.randomUUID().toString(),
                        scope = HISTORY_SCOPE,
                        key = "visit_$now",
                        value = buildJsonObject {
                            put("url", url)
                            put("title", title)
                            put("at", now)
                        }.toString(),
                        goalId = ctx.goalId,
                        domain = android.net.Uri.parse(url).host,
                        updatedAt = now
                    )
                )
                ToolResult.ok(buildJsonObject { put("url", url); put("recorded", true) })
            } catch (t: Throwable) {
                ToolResult.fail("addHistory failed: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    private val notifyUser = object : BaseTool(
        "notifyUser", ToolAction.NOTIFY,
        "Show a local Android notification to the user (no network). " +
            "Args: {\"title\": string?, \"text\": string (required)}."
    ) {
        override suspend fun execute(argsJson: String, ctx: ToolContext): ToolResult {
            val a = ToolArgs.parse(argsJson)
            val text = ToolArgs.str(a, "text")?.trim().orEmpty()
            if (text.isBlank()) return ToolResult.fail("notifyUser requires a non-empty 'text' argument")
            val title = ToolArgs.str(a, "title")?.trim().takeIf { !it.isNullOrBlank() } ?: "Motion Agent"
            return try {
                notifier.notifyResult(title, text, ctx.runId)
                ToolResult.ok(buildJsonObject { put("notified", true); put("title", title) })
            } catch (t: Throwable) {
                ToolResult.fail("notifyUser failed: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    private val listDownloads = object : BaseTool(
        "listDownloads", ToolAction.READ,
        "List recent downloads known to the Android DownloadManager (those started by Motion) with " +
            "status, size and local URI. Returns {\"downloads\": [...], \"count\": number}. Read-only."
    ) {
        override suspend fun execute(argsJson: String, ctx: ToolContext): ToolResult {
            val dm = ServiceLocator.appContext.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
                ?: return ToolResult.fail("DownloadManager is unavailable on this device")
            val arr = buildJsonArraySafe(dm)
            return ToolResult.ok(buildJsonObject { put("downloads", arr); put("count", arr.size) })
        }
    }

    private fun buildJsonArraySafe(dm: DownloadManager): JsonArray {
        var cursor: Cursor? = null
        return try {
            cursor = dm.query(DownloadManager.Query())
            val c = cursor
            if (c == null) {
                JsonArray(emptyList())
            } else {
                val iId = c.getColumnIndex(DownloadManager.COLUMN_ID)
                val iTitle = c.getColumnIndex(DownloadManager.COLUMN_TITLE)
                val iStatus = c.getColumnIndex(DownloadManager.COLUMN_STATUS)
                val iTotal = c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                val iDone = c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val iLocal = c.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                val rows = mutableListOf<JsonObject>()
                while (c.moveToNext() && rows.size < MAX_ROWS) {
                    rows.add(
                        buildJsonObject {
                            if (iId >= 0) put("id", c.getLong(iId))
                            if (iTitle >= 0) put("title", c.getString(iTitle))
                            if (iStatus >= 0) put("status", statusName(c.getInt(iStatus)))
                            if (iTotal >= 0) put("totalBytes", c.getLong(iTotal))
                            if (iDone >= 0) put("downloadedBytes", c.getLong(iDone))
                            if (iLocal >= 0) put("localUri", c.getString(iLocal))
                        }
                    )
                }
                JsonArray(rows)
            }
        } catch (t: Throwable) {
            JsonArray(emptyList())
        } finally {
            runCatching { cursor?.close() }
        }
    }

    private fun statusName(status: Int): String = when (status) {
        DownloadManager.STATUS_SUCCESSFUL -> "successful"
        DownloadManager.STATUS_FAILED -> "failed"
        DownloadManager.STATUS_RUNNING -> "running"
        DownloadManager.STATUS_PAUSED -> "paused"
        DownloadManager.STATUS_PENDING -> "pending"
        else -> "unknown"
    }

    companion object {
        /** MemoryDao scope used for local visit history (extends the GLOBAL|GOAL|SITE|RUN|TEMP set; see worklog). */
        const val HISTORY_SCOPE = "HISTORY"
        private const val MAX_ROWS = 50
    }
}
