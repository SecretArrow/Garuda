package com.motion.browser.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.motion.browser.data.dao.ApprovalDao
import com.motion.browser.data.dao.BookmarkDao
import com.motion.browser.data.dao.ChatDao
import com.motion.browser.data.dao.DownloadDao
import com.motion.browser.data.dao.EventDao
import com.motion.browser.data.dao.GoalDao
import com.motion.browser.data.dao.HistoryDao
import com.motion.browser.data.dao.MemoryDao
import com.motion.browser.data.dao.PermissionDao
import com.motion.browser.data.dao.RunDao
import com.motion.browser.data.dao.StepDao
import com.motion.browser.data.dao.TriggerDao
import com.motion.browser.data.entity.ApprovalEntity
import com.motion.browser.data.entity.BookmarkEntity
import com.motion.browser.data.entity.ChatMessageEntity
import com.motion.browser.data.entity.ChatSessionEntity
import com.motion.browser.data.entity.DownloadEntity
import com.motion.browser.data.entity.EventEntity
import com.motion.browser.data.entity.GoalEntity
import com.motion.browser.data.entity.HistoryEntity
import com.motion.browser.data.entity.MemoryEntity
import com.motion.browser.data.entity.PermissionEntity
import com.motion.browser.data.entity.RunEntity
import com.motion.browser.data.entity.StepEntity
import com.motion.browser.data.entity.TriggerEntity

/**
 * Single Room database for Motion Browser (ARCHITECTURE.md §3.4).
 * Accessor names (goalDao/runDao/stepDao/triggerDao/memoryDao/permissionDao/eventDao/approvalDao
 * + bookmarkDao/historyDao/downloadDao/chatDao) are the cross-agent contract
 * for ServiceLocator.database consumers.
 */
@Database(
    entities = [
        GoalEntity::class,
        RunEntity::class,
        StepEntity::class,
        TriggerEntity::class,
        MemoryEntity::class,
        PermissionEntity::class,
        EventEntity::class,
        ApprovalEntity::class,
        BookmarkEntity::class,
        HistoryEntity::class,
        DownloadEntity::class,
        ChatSessionEntity::class,
        ChatMessageEntity::class
    ],
    version = 2,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class MotionDatabase : RoomDatabase() {

    abstract fun goalDao(): GoalDao
    abstract fun runDao(): RunDao
    abstract fun stepDao(): StepDao
    abstract fun triggerDao(): TriggerDao
    abstract fun memoryDao(): MemoryDao
    abstract fun permissionDao(): PermissionDao
    abstract fun eventDao(): EventDao
    abstract fun approvalDao(): ApprovalDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun historyDao(): HistoryDao
    abstract fun downloadDao(): DownloadDao
    abstract fun chatDao(): ChatDao

    companion object {
        @Volatile
        private var INSTANCE: MotionDatabase? = null

        /** v1 → v2: browser data layer (bookmarks, history, downloads, chat). Additive only. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `bookmarks` (`id` TEXT NOT NULL, `url` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, `folder` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_bookmarks_url` ON `bookmarks` (`url`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_bookmarks_folder` ON `bookmarks` (`folder`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `history` (`id` TEXT NOT NULL, `url` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, `visitedAt` INTEGER NOT NULL, `visitCount` INTEGER NOT NULL, " +
                        "`isPrivate` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_history_url` ON `history` (`url`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_history_visitedAt` ON `history` (`visitedAt`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `downloads` (`id` TEXT NOT NULL, `url` TEXT NOT NULL, " +
                        "`fileName` TEXT NOT NULL, `filePath` TEXT NOT NULL, `mimeType` TEXT NOT NULL, " +
                        "`status` TEXT NOT NULL, `bytesDownloaded` INTEGER NOT NULL, `totalBytes` INTEGER NOT NULL, " +
                        "`error` TEXT, `createdAt` INTEGER NOT NULL, `completedAt` INTEGER, PRIMARY KEY(`id`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_downloads_createdAt` ON `downloads` (`createdAt`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `chat_sessions` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                        "`pinned` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_sessions_updatedAt` ON `chat_sessions` (`updatedAt`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `chat_messages` (`id` TEXT NOT NULL, `sessionId` TEXT NOT NULL, " +
                        "`role` TEXT NOT NULL, `content` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_messages_sessionId` ON `chat_messages` (`sessionId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_messages_createdAt` ON `chat_messages` (`createdAt`)")
            }
        }

        fun getInstance(context: Context): MotionDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    MotionDatabase::class.java,
                    "motion_browser.db"
                )
                    // v2 onward: non-destructive migrations are mandatory (spec §62).
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
