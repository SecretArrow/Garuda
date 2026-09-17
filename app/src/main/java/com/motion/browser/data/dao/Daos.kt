package com.motion.browser.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.motion.browser.data.entity.ApprovalEntity
import com.motion.browser.data.entity.EventEntity
import com.motion.browser.data.entity.GoalEntity
import com.motion.browser.data.entity.MemoryEntity
import com.motion.browser.data.entity.PermissionEntity
import com.motion.browser.data.entity.RunEntity
import com.motion.browser.data.entity.StepEntity
import com.motion.browser.data.entity.TriggerEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAOs for Motion Browser (ARCHITECTURE.md §3.4).
 * Method names here are the cross-agent contract for agents 2-d and 2-f — do not rename.
 */

@Dao
interface GoalDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(goal: GoalEntity)

    @Update
    suspend fun update(goal: GoalEntity)

    @Delete
    suspend fun delete(goal: GoalEntity)

    @Query("DELETE FROM goals WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("SELECT * FROM goals WHERE id = :id")
    suspend fun getById(id: String): GoalEntity?

    @Query("SELECT * FROM goals WHERE id = :id")
    fun byId(id: String): Flow<GoalEntity?>

    @Query("SELECT * FROM goals ORDER BY createdAt DESC")
    fun all(): Flow<List<GoalEntity>>

    @Query("SELECT * FROM goals WHERE enabled = 1 ORDER BY createdAt DESC")
    fun enabled(): Flow<List<GoalEntity>>

    @Query("UPDATE goals SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)

    /** Goal lifecycle: IDLE | RUNNING | COMPLETED | FAILED | STOPPED */
    @Query("UPDATE goals SET status = :status WHERE id = :id")
    suspend fun setStatus(id: String, status: String)

    @Query("UPDATE goals SET nextRun = :nextRun WHERE id = :id")
    suspend fun setNextRun(id: String, nextRun: Long?)

    @Query("UPDATE goals SET lastRun = :lastRun WHERE id = :id")
    suspend fun setLastRun(id: String, lastRun: Long?)
}

@Dao
interface RunDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(run: RunEntity)

    @Query("UPDATE runs SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String)

    @Query("UPDATE runs SET resultSummary = :resultSummary WHERE id = :id")
    suspend fun setResult(id: String, resultSummary: String)

    @Query("UPDATE runs SET endedAt = :endedAt WHERE id = :id")
    suspend fun setEnded(id: String, endedAt: Long?)

    @Query("SELECT * FROM runs WHERE goalId = :goalId ORDER BY startedAt DESC")
    fun byGoal(goalId: String): Flow<List<RunEntity>>

    @Query("SELECT * FROM runs WHERE id = :id")
    suspend fun getById(id: String): RunEntity?

    @Query("SELECT * FROM runs ORDER BY startedAt DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<RunEntity>>

    @Query("SELECT COUNT(*) FROM runs WHERE status = :status")
    fun countByStatus(status: String): Flow<Int>

    /** Crash/restart recovery (spec §32): mark runs left in RUNNING by a dead process as FAILED. */
    @Query(
        "UPDATE runs SET status = 'FAILED', endedAt = :endedAt, resultSummary = :summary " +
            "WHERE status = 'RUNNING'"
    )
    suspend fun failStaleRuns(endedAt: Long, summary: String): Int
}

@Dao
interface StepDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(step: StepEntity)

    /** Ordered by step index. NOTE: `index` is a SQLite keyword and must stay backtick-quoted. */
    @Query("SELECT * FROM steps WHERE runId = :runId ORDER BY `index` ASC")
    suspend fun getByRun(runId: String): List<StepEntity>

    @Query("SELECT * FROM steps WHERE runId = :runId ORDER BY `index` ASC")
    fun observeByRun(runId: String): Flow<List<StepEntity>>

    /** Most recent steps across all runs of a goal (newest first). */
    @Query(
        "SELECT steps.* FROM steps INNER JOIN runs ON steps.runId = runs.id " +
            "WHERE runs.goalId = :goalId ORDER BY steps.at DESC LIMIT :limit"
    )
    suspend fun recentForGoal(goalId: String, limit: Int): List<StepEntity>
}

@Dao
interface TriggerDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(trigger: TriggerEntity)

    @Update
    suspend fun update(trigger: TriggerEntity)

    @Delete
    suspend fun delete(trigger: TriggerEntity)

    @Query("DELETE FROM triggers WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("SELECT * FROM triggers WHERE id = :id")
    suspend fun getById(id: String): TriggerEntity?

    @Query("SELECT * FROM triggers WHERE goalId = :goalId")
    suspend fun forGoal(goalId: String): List<TriggerEntity>

    @Query("SELECT * FROM triggers ORDER BY goalId")
    fun all(): Flow<List<TriggerEntity>>

    @Query("SELECT * FROM triggers WHERE enabled = 1")
    fun enabled(): Flow<List<TriggerEntity>>

    @Query("UPDATE triggers SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)

    @Query("UPDATE triggers SET lastFired = :lastFired WHERE id = :id")
    suspend fun setLastFired(id: String, lastFired: Long)
}

@Dao
interface MemoryDao {
    /** Idempotent by design: MemoryEntity.id is the stable hash of the composite key. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(memory: MemoryEntity)

    @Query("SELECT * FROM memory WHERE id = :id")
    suspend fun getById(id: String): MemoryEntity?

    /** NULL-safe exact lookup by composite key (scope, key, goalId?, domain?). */
    @Query(
        "SELECT * FROM memory WHERE scope = :scope AND `key` = :key " +
            "AND ((:goalId IS NULL AND goalId IS NULL) OR goalId = :goalId) " +
            "AND ((:domain IS NULL AND domain IS NULL) OR domain = :domain) LIMIT 1"
    )
    suspend fun getExact(scope: String, key: String, goalId: String?, domain: String?): MemoryEntity?

    @Query("SELECT * FROM memory WHERE goalId = :goalId ORDER BY updatedAt DESC")
    fun byGoal(goalId: String): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memory WHERE scope = :scope ORDER BY updatedAt DESC")
    fun byScope(scope: String): Flow<List<MemoryEntity>>

    @Query("DELETE FROM memory WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Delete
    suspend fun delete(memory: MemoryEntity)

    @Query("DELETE FROM memory WHERE scope = :scope")
    suspend fun deleteScope(scope: String): Int
}

@Dao
interface PermissionDao {
    /** One rule per domain (unique index); REPLACE makes setRule() an upsert. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(permission: PermissionEntity)

    @Query("SELECT * FROM permissions ORDER BY domain")
    fun all(): Flow<List<PermissionEntity>>

    @Query("SELECT * FROM permissions WHERE domain = :domain LIMIT 1")
    suspend fun forDomain(domain: String): PermissionEntity?

    @Query("DELETE FROM permissions WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Delete
    suspend fun delete(permission: PermissionEntity)
}

@Dao
interface EventDao {
    @Insert
    suspend fun insert(event: EventEntity): Long

    @Query("SELECT * FROM events ORDER BY at DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<EventEntity>>

    @Query("SELECT * FROM events WHERE category = :category ORDER BY at DESC LIMIT :limit")
    fun byCategory(category: String, limit: Int): Flow<List<EventEntity>>

    @Query("SELECT COUNT(*) FROM events WHERE category = :category")
    fun countByCategory(category: String): Flow<Int>

    @Query("DELETE FROM events WHERE at < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int
}

@Dao
interface ApprovalDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(approval: ApprovalEntity)

    @Query("UPDATE approvals SET status = :status, resolvedAt = :resolvedAt WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, resolvedAt: Long?)

    /** Persists user-edited args when resolving with edits (ApprovalQueue.resolve). */
    @Query("UPDATE approvals SET argsJson = :argsJson WHERE id = :id")
    suspend fun updateArgs(id: String, argsJson: String)

    @Query("SELECT * FROM approvals WHERE status = 'PENDING' ORDER BY createdAt ASC")
    fun pending(): Flow<List<ApprovalEntity>>

    @Query("SELECT * FROM approvals WHERE id = :id")
    suspend fun getById(id: String): ApprovalEntity?

    /** Marks stale PENDING rows EXPIRED; returns number of rows expired. */
    @Query(
        "UPDATE approvals SET status = 'EXPIRED', resolvedAt = :resolvedAt " +
            "WHERE status = 'PENDING' AND createdAt < :olderThan"
    )
    suspend fun expireStale(olderThan: Long, resolvedAt: Long): Int
}
