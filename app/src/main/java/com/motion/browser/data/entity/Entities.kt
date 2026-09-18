package com.motion.browser.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Room entities for Motion Browser (ARCHITECTURE.md §3.4, spec §14/§31/§39/§47/§61).
 *
 * Conventions shared across agents (2-c / 2-d / 2-f):
 *  - String primary keys are UUIDv4 strings, generated at construction time.
 *  - Timestamps are epoch milliseconds (Long).
 *  - Status/mode/policy fields are plain strings; canonical values are documented on each field.
 *  - Domain/action lists are persisted via [com.motion.browser.data.db.Converters] (JSON arrays).
 *  - No foreign keys on purpose: goal deletion is "cascade-ish" (triggers cleaned by
 *    agent 2-d TriggerManager.cancelForGoal), and orphan rows must never break inserts.
 */

@Entity(tableName = "goals")
data class GoalEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val instruction: String,
    val enabled: Boolean = false,
    /** JSON-encoded [com.motion.browser.agent.planner.ScheduleSpec]; empty string = no schedule. */
    val scheduleJson: String = "",
    val allowedDomains: List<String> = emptyList(),
    val blockedDomains: List<String> = emptyList(),
    /** Action classes: READ | NAVIGATE | FILL_FORM | SUBMIT | DOWNLOAD | UPLOAD */
    val allowedActions: List<String> = emptyList(),
    val blockedActions: List<String> = emptyList(),
    /** Confirmation policy for approval flow: ALWAYS | RISKY | NEVER (interpreted by 2-d SafetyGuard). */
    val confirmationPolicy: String = "RISKY",
    /** Notification policy: ALL | FAILURES | SILENT. */
    val notificationPolicy: String = "FAILURES",
    /** Memory policy: GLOBAL | GOAL | SITE | NONE. */
    val memoryPolicy: String = "GOAL",
    val maxSteps: Int = 25,
    val maxRuntimeMinutes: Int = 15,
    val maxDownloads: Int = 10,
    val maxPosts: Int = 5,
    val maxRetries: Int = 3,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val lastRun: Long? = null,
    val nextRun: Long? = null,
    /** Goal lifecycle: IDLE | RUNNING | COMPLETED | FAILED | STOPPED. */
    val status: String = "IDLE"
)

@Entity(tableName = "runs", indices = [Index("goalId")])
data class RunEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val goalId: String? = null,
    val instruction: String,
    /** MANUAL | COPILOT | AUTONOMOUS | INTERACTIVE */
    val mode: String,
    /** RUNNING | SUCCESS | FAILED | STOPPED */
    val status: String,
    val startedAt: Long = System.currentTimeMillis(),
    val endedAt: Long? = null,
    val resultSummary: String? = null
)

@Entity(tableName = "steps", indices = [Index("runId")])
data class StepEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val runId: String,
    /** 0-based position within the run. NOTE: "index" is a SQLite keyword — always backtick-quote it in @Query. */
    val index: Int = 0,
    val tool: String,
    val argsJson: String = "{}",
    val resultJson: String? = null,
    /** OK | FAIL */
    val status: String = "OK",
    val at: Long = System.currentTimeMillis()
)

@Entity(tableName = "triggers", indices = [Index("goalId")])
data class TriggerEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val goalId: String,
    /** ONCE | HOURLY | INTERVAL | DAILY | WEEKLY | WEEKDAYS | MONTHLY | CONTENT | STATE */
    val type: String,
    val scheduleJson: String = "{}",
    val enabled: Boolean = true,
    val lastFired: Long? = null
)

@Entity(tableName = "memory", indices = [Index("scope", "key")])
data class MemoryEntity(
    /**
     * Primary key = stable hash of the composite key "scope|key|goalId|domain"
     * (computed by [com.motion.browser.agent.memory.MemoryManager]), which makes
     * upserts idempotent for the same logical memory slot.
     */
    @PrimaryKey val id: String,
    /** GLOBAL | GOAL | SITE | RUN | TEMP (+ BOOKMARK / HISTORY helpers, spec §3.2). */
    val scope: String,
    val key: String,
    val value: String,
    val goalId: String? = null,
    val domain: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "permissions", indices = [Index(value = ["domain"], unique = true)])
data class PermissionEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val domain: String,
    // Booleans default to deny (safe default, spec §25); one rule row per domain.
    val read: Boolean = false,
    val navigate: Boolean = false,
    val fillForms: Boolean = false,
    val submit: Boolean = false,
    val download: Boolean = false,
    val upload: Boolean = false
)

@Entity(tableName = "events", indices = [Index("at")])
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** MOTION_BROWSER | MOTION_AI | AGENT | AUTOMATION | SECURITY | NETWORK | DOWNLOAD | ERROR */
    val category: String,
    val message: String,
    val detail: String? = null,
    val at: Long = System.currentTimeMillis()
)

@Entity(tableName = "approvals")
data class ApprovalEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val runId: String? = null,
    val domain: String,
    val action: String,
    val argsJson: String = "{}",
    /** PENDING | APPROVED | REJECTED | EXPIRED */
    val status: String = "PENDING",
    val createdAt: Long = System.currentTimeMillis(),
    val resolvedAt: Long? = null
)

// ============================================================================
// Browser data layer (v2): bookmarks, history, downloads, AI chat sessions.
// Conventions unchanged: string PKs, epoch-millis timestamps.
// ============================================================================

@Entity(tableName = "bookmarks", indices = [Index(value = ["url"], unique = false), Index("folder")])
data class BookmarkEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val url: String,
    val title: String,
    /** Simple folder/category name; "" = root. */
    val folder: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "history", indices = [Index(value = ["url"], unique = true), Index("visitedAt")])
data class HistoryEntity(
    /** Stable hash of the URL so re-visits upsert instead of duplicating rows. */
    @PrimaryKey val id: String,
    val url: String,
    val title: String,
    val visitedAt: Long = System.currentTimeMillis(),
    val visitCount: Int = 1,
    /** Private-tab visits are never persisted; this flag only marks legacy rows. */
    val isPrivate: Boolean = false
)

@Entity(tableName = "downloads", indices = [Index("createdAt")])
data class DownloadEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val url: String,
    val fileName: String,
    val filePath: String = "",
    val mimeType: String = "application/octet-stream",
    /** PENDING | RUNNING | PAUSED | COMPLETED | FAILED | CANCELLED */
    val status: String = "PENDING",
    val bytesDownloaded: Long = 0,
    val totalBytes: Long = -1,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null
)

@Entity(tableName = "chat_sessions", indices = [Index("updatedAt")])
data class ChatSessionEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val title: String = "New chat",
    val pinned: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/** role: "user" | "assistant" | "status". */
@Entity(tableName = "chat_messages", indices = [Index("sessionId"), Index("createdAt")])
data class ChatMessageEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val sessionId: String,
    val role: String,
    val content: String,
    val createdAt: Long = System.currentTimeMillis()
)
