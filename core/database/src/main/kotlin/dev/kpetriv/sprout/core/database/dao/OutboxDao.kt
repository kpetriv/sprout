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
@Suppress("TooManyFunctions") // One query per access path of the outbox.
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
     * The oldest [limit] rows in `seq` order, due or not, **without** their `data` (a blob chunk is up to 700 KB,
     * review M5): the caller stops at the first row not yet due (head-of-line, D-M3-15) and loads a chunk's bytes
     * with [dataOf] when it pushes it.
     */
    @Query(
        """
        SELECT seq, kind, refId, payload, createdAtEpochMs, attempts, lastError, nextAttemptAtEpochMs, docType, docId,
        lamport FROM outbox ORDER BY seq LIMIT :limit
        """,
    )
    suspend fun head(limit: Int): List<OutboxHead>

    /**
     * The oldest [limit] rows of [kinds] (one outbox lane, D-M4-22) in `seq` order, due or not, without their `data`,
     * like [head]: the caller stops at the first row of the lane that is not yet due.
     */
    @Query(
        """
        SELECT seq, kind, refId, payload, createdAtEpochMs, attempts, lastError, nextAttemptAtEpochMs, docType, docId,
        lamport FROM outbox WHERE kind IN (:kinds) ORDER BY seq LIMIT :limit
        """,
    )
    suspend fun headOf(kinds: List<OutboxKind>, limit: Int): List<OutboxHead>

    /** The bytes of row [seq] (a blob chunk's), or null. */
    @Query("SELECT data FROM outbox WHERE seq = :seq")
    suspend fun dataOf(seq: Long): ByteArray?

    /** The queued rows of [kinds] without their bytes, in order (blocked-push lookups). */
    @Query(
        """
        SELECT seq, kind, refId, payload, createdAtEpochMs, attempts, lastError, nextAttemptAtEpochMs, docType, docId,
        lamport FROM outbox WHERE kind IN (:kinds) ORDER BY seq
        """,
    )
    fun observeHeads(kinds: List<OutboxKind>): Flow<List<OutboxHead>>

    /** The queued rows of [kinds] without their bytes, in order. */
    @Query(
        """
        SELECT seq, kind, refId, payload, createdAtEpochMs, attempts, lastError, nextAttemptAtEpochMs, docType, docId,
        lamport FROM outbox WHERE kind IN (:kinds) ORDER BY seq
        """,
    )
    suspend fun headsOf(kinds: List<OutboxKind>): List<OutboxHead>

    @Query("SELECT COUNT(*) FROM outbox WHERE kind = :kind AND refId = :refId")
    suspend fun countRef(kind: OutboxKind, refId: String): Int

    @Query("DELETE FROM outbox WHERE kind = :kind AND refId = :refId")
    suspend fun deleteRef(kind: OutboxKind, refId: String): Int

    /** Queued chunks of [blobId] (not uploaded yet). */
    @Query("SELECT COUNT(*) FROM outbox WHERE kind = 'BLOB_CHUNK' AND refId LIKE :blobId || '.%'")
    suspend fun countBlobChunks(blobId: String): Int

    /** Removes the queued chunks of blob [blobId] (their `refId`s are `"<blobId>.<chunk>"`). */
    @Query("DELETE FROM outbox WHERE kind = 'BLOB_CHUNK' AND refId LIKE :blobId || '.%'")
    suspend fun deleteBlobChunks(blobId: String): Int

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

    /** The queued rows of [kind], in order (chat send states), without their bytes. */
    @Query(
        """
        SELECT seq, kind, refId, payload, createdAtEpochMs, attempts, lastError, nextAttemptAtEpochMs, docType, docId,
        lamport FROM outbox WHERE kind = :kind ORDER BY seq
        """,
    )
    fun observeKind(kind: OutboxKind): Flow<List<OutboxHead>>

    /** Makes the row of [kind]/[refId] due at once (a user's "retry"). */
    @Query("UPDATE outbox SET nextAttemptAtEpochMs = 0 WHERE kind = :kind AND refId = :refId")
    suspend fun retryNow(kind: OutboxKind, refId: String): Int

    /** The `seq` of every queued row of [kind], in order. */
    @Query("SELECT seq FROM outbox WHERE kind = :kind ORDER BY seq")
    fun observeSeqs(kind: OutboxKind): Flow<List<Long>>
}

/** An outbox row without its `data` ([OutboxDao.head]): what the push loop and the screens read. */
data class OutboxHead(
    val seq: Long,
    val kind: OutboxKind,
    val refId: String,
    val payload: String,
    val createdAtEpochMs: Long,
    val attempts: Int,
    val lastError: String?,
    val nextAttemptAtEpochMs: Long,
    val docType: String?,
    val docId: String?,
    val lamport: Long?,
) {
    override fun toString(): String = "OutboxHead(seq=$seq, kind=$kind, refId=$refId, attempts=$attempts)"
}
