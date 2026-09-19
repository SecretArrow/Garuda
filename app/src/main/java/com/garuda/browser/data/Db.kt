package com.garuda.browser.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Persistence for the agent system (plan §2 bottom row + Prompt 6A):
 * tasks survive crash/reboot, every step is an audit log entry, providers and
 * schedules are user configuration. All entities are Room, schema v1.
 */
@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey val id: String,
    val goal: String,
    /** QUEUED, PLANNING, RUNNING, WAITING_HUMAN, PAUSED, DONE, FAILED, STOPPED */
    val status: String,
    val createdAt: Long,
    val updatedAt: Long,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    /** Final summary produced by finish(). */
    val summary: String? = null,
    val error: String? = null,
    /** Tab key the agent runs in (Garuda tab id, maps to a CDP target). */
    val tabKey: String? = null,
    val maxSteps: Int = 50,
    val stepCount: Int = 0,
    /** Token usage accounting (dashboard cost view). */
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    /** Compact summary of earlier steps (context compaction). */
    val compactionSummary: String? = null,
    val recurringCron: String? = null,
    val lastRunAt: Long? = null,
)

@Entity(tableName = "steps")
data class StepEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: String,
    val seq: Int,
    /** perceive | llm | action | verify | compact | human */
    val kind: String,
    /** tool name for actions, model name for llm, page url for perceive. */
    val label: String,
    /** JSON detail: tool args, result, error. Large payloads truncated. */
    val detail: String,
    val at: Long,
    val ok: Boolean,
)

@Entity(tableName = "providers")
data class ProviderEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** openai | anthropic | gemini | ollama */
    val protocol: String,
    val baseUrl: String,
    val model: String,
    /** Whether this is the default task-solving provider. */
    val isDefault: Boolean = false,
    /** Fallback order when the default fails (comma separated provider ids). */
    val fallbackIds: String = "",
    /** Last detection info: model list, capabilities, latency. */
    val detectedInfo: String? = null,
    val visionCapable: Boolean = false,
    val toolCapable: Boolean = true,
)

@Entity(tableName = "schedules")
data class ScheduleEntity(
    @PrimaryKey val id: String,
    val goal: String,
    /** Human-readable spec: "daily 08:00", "every 6h", "weekly mon 09:30". */
    val spec: String,
    val enabled: Boolean = true,
    val createdAt: Long,
    val lastRunAt: Long? = null,
    val nextRunAt: Long,
)

@Dao
interface TaskDao {
    @Insert suspend fun insert(task: TaskEntity)
    @Update suspend fun update(task: TaskEntity)

    @Query("SELECT * FROM tasks ORDER BY createdAt DESC")
    fun all(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun byId(id: String): TaskEntity?

    @Query("SELECT * FROM tasks WHERE status IN ('QUEUED','PLANNING','RUNNING','WAITING_HUMAN','PAUSED') ORDER BY createdAt ASC")
    suspend fun unfinished(): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE status = 'WAITING_HUMAN' ORDER BY updatedAt ASC LIMIT 1")
    suspend fun firstWaitingHuman(): TaskEntity?

    @Query("UPDATE tasks SET status = :status, updatedAt = :now WHERE id = :id")
    suspend fun setStatus(id: String, status: String, now: Long)

    @Query("UPDATE tasks SET stepCount = :steps, promptTokens = :prompt, completionTokens = :completion, updatedAt = :now WHERE id = :id")
    suspend fun updateProgress(id: String, steps: Int, prompt: Long, completion: Long, now: Long)

    @Query("UPDATE tasks SET compactionSummary = :summary, updatedAt = :now WHERE id = :id")
    suspend fun setCompaction(id: String, summary: String, now: Long)

    @Query("UPDATE tasks SET summary = :summary, status = 'DONE', finishedAt = :now, updatedAt = :now WHERE id = :id")
    suspend fun finish(id: String, summary: String, now: Long)

    @Query("UPDATE tasks SET error = :error, status = :status, finishedAt = :now, updatedAt = :now WHERE id = :id")
    suspend fun fail(id: String, error: String, status: String, now: Long)

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface StepDao {
    @Insert suspend fun insert(step: StepEntity)

    @Query("SELECT * FROM steps WHERE taskId = :taskId ORDER BY seq ASC")
    suspend fun forTask(taskId: String): List<StepEntity>

    @Query("SELECT * FROM steps WHERE taskId = :taskId ORDER BY seq ASC")
    fun forTaskLive(taskId: String): Flow<List<StepEntity>>

    @Query("SELECT * FROM steps WHERE taskId = :taskId ORDER BY seq DESC LIMIT 1")
    suspend fun lastForTask(taskId: String): StepEntity?

    @Query("DELETE FROM steps WHERE taskId = :taskId")
    suspend fun deleteForTask(taskId: String)
}

@Dao
interface ProviderDao {
    @Insert suspend fun insert(provider: ProviderEntity)
    @Update suspend fun update(provider: ProviderEntity)

    @Query("SELECT * FROM providers ORDER BY name ASC")
    fun all(): Flow<List<ProviderEntity>>

    @Query("SELECT * FROM providers ORDER BY name ASC")
    suspend fun allOnce(): List<ProviderEntity>

    @Query("SELECT * FROM providers WHERE isDefault = 1 LIMIT 1")
    suspend fun defaultProvider(): ProviderEntity?

    @Query("UPDATE providers SET isDefault = CASE WHEN id = :id THEN 1 ELSE 0 END")
    suspend fun setDefault(id: String)

    @Query("DELETE FROM providers WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface ScheduleDao {
    @Insert suspend fun insert(schedule: ScheduleEntity)
    @Update suspend fun update(schedule: ScheduleEntity)

    @Query("SELECT * FROM schedules ORDER BY nextRunAt ASC")
    fun all(): Flow<List<ScheduleEntity>>

    @Query("SELECT * FROM schedules WHERE enabled = 1 AND nextRunAt <= :now LIMIT 5")
    suspend fun due(now: Long): List<ScheduleEntity>

    @Query("DELETE FROM schedules WHERE id = :id")
    suspend fun delete(id: String)
}

@Database(
    entities = [TaskEntity::class, StepEntity::class, ProviderEntity::class, ScheduleEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class GarudaDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun stepDao(): StepDao
    abstract fun providerDao(): ProviderDao
    abstract fun scheduleDao(): ScheduleDao
}
