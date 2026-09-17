package com.motion.browser.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.motion.browser.data.dao.ApprovalDao
import com.motion.browser.data.dao.EventDao
import com.motion.browser.data.dao.GoalDao
import com.motion.browser.data.dao.MemoryDao
import com.motion.browser.data.dao.PermissionDao
import com.motion.browser.data.dao.RunDao
import com.motion.browser.data.dao.StepDao
import com.motion.browser.data.dao.TriggerDao
import com.motion.browser.data.entity.ApprovalEntity
import com.motion.browser.data.entity.EventEntity
import com.motion.browser.data.entity.GoalEntity
import com.motion.browser.data.entity.MemoryEntity
import com.motion.browser.data.entity.PermissionEntity
import com.motion.browser.data.entity.RunEntity
import com.motion.browser.data.entity.StepEntity
import com.motion.browser.data.entity.TriggerEntity

/**
 * Single Room database for Motion Browser (ARCHITECTURE.md §3.4).
 * Accessor names (goalDao/runDao/stepDao/triggerDao/memoryDao/permissionDao/eventDao/approvalDao)
 * are the cross-agent contract for ServiceLocator.database consumers (agents 2-d / 2-f).
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
        ApprovalEntity::class
    ],
    version = 1,
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

    companion object {
        @Volatile
        private var INSTANCE: MotionDatabase? = null

        fun getInstance(context: Context): MotionDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    MotionDatabase::class.java,
                    "motion_browser.db"
                )
                    // v1 only — proper migrations become mandatory from v2 (spec §62).
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
