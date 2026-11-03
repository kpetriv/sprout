package dev.kpetriv.sprout.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.kpetriv.sprout.core.database.entity.OutboxEntity
import dev.kpetriv.sprout.core.database.entity.OutboxKind
import kotlinx.coroutines.flow.Flow

/** The sync outbox. Written with every local change; drained by the sync engine through `RoomOutboxSource`. */
@Dao
interface OutboxDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entry: OutboxEntity): Long

    /**
     * Queues a document write. A row for the same document (same `kind` and `refId`) that has not been sent yet is
     * replaced, so the outbox holds only the document's latest state.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDocument(entry: OutboxEntity): Long

    /**
     * The oldest [limit] rows in `seq` order, due or not: the caller stops at the first row not yet due
     * (head-of-line, D-M3-15).
     */
    @Query("SELECT * FROM outbox ORDER BY seq LIMIT :limit")
    suspend fun head(limit: Int): List<OutboxEntity>

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

    /** Rows not yet accepted by the server (unsynced changes). */
    @Query("SELECT COUNT(*) FROM outbox")
    suspend fun count(): Int

    /** The queued rows of [kind], in order (chat send states). */
    @Query("SELECT * FROM outbox WHERE kind = :kind ORDER BY seq")
    fun observeKind(kind: OutboxKind): Flow<List<OutboxEntity>>

    /** Makes the row of [kind]/[refId] due at once (a user's "retry"). */
    @Query("UPDATE outbox SET nextAttemptAtEpochMs = 0 WHERE kind = :kind AND refId = :refId")
    suspend fun retryNow(kind: OutboxKind, refId: String): Int

    /** The `seq` of every queued row of [kind], in order. */
    @Query("SELECT seq FROM outbox WHERE kind = :kind ORDER BY seq")
    fun observeSeqs(kind: OutboxKind): Flow<List<Long>>
}
