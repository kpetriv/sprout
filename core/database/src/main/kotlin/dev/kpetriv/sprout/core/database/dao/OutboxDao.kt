package dev.kpetriv.sprout.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.kpetriv.sprout.core.database.entity.OutboxEntity

/** The sync outbox. Written with every local change; drained by the sync engine (M3). */
@Dao
interface OutboxDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entry: OutboxEntity): Long

    /** Rows due at [nowEpochMs], oldest first. */
    @Query("SELECT * FROM outbox WHERE nextAttemptAtEpochMs <= :nowEpochMs ORDER BY seq LIMIT :limit")
    suspend fun due(nowEpochMs: Long, limit: Int): List<OutboxEntity>

    /** Records a failed attempt and when to try again. */
    @Query(
        """
        UPDATE outbox SET attempts = attempts + 1, lastError = :error, nextAttemptAtEpochMs = :nextAttemptAtEpochMs
        WHERE seq = :seq
        """,
    )
    suspend fun markFailed(seq: Long, error: String, nextAttemptAtEpochMs: Long)

    /** Removes an acknowledged row. */
    @Query("DELETE FROM outbox WHERE seq = :seq")
    suspend fun delete(seq: Long)

    @Query("SELECT * FROM outbox ORDER BY seq")
    suspend fun all(): List<OutboxEntity>
}
