package dev.kpetriv.sprout.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import dev.kpetriv.sprout.core.database.entity.BlobEntity
import dev.kpetriv.sprout.core.database.entity.KidEntity
import dev.kpetriv.sprout.core.database.entity.MessageEntity
import dev.kpetriv.sprout.core.database.entity.MessageSeqEntity
import dev.kpetriv.sprout.core.database.entity.MessageStatus
import dev.kpetriv.sprout.core.database.entity.PhotoEntity
import dev.kpetriv.sprout.core.database.entity.ReadMarkEntity
import dev.kpetriv.sprout.core.database.entity.ThreadKeyEntity
import dev.kpetriv.sprout.core.model.DeviceId
import kotlinx.coroutines.flow.Flow

/** Chat messages, per-author sequence numbers and read marks (M4 §4). */
@Dao
@Suppress("TooManyFunctions") // One query per access path of the chat store.
interface MessageDao {
    @Query("SELECT * FROM messages WHERE messageId = :messageId")
    suspend fun get(messageId: String): MessageEntity?

    @Upsert
    suspend fun upsert(message: MessageEntity)

    @Query("DELETE FROM messages WHERE messageId = :messageId")
    suspend fun delete(messageId: String): Int

    /** The visible messages of [threadId], newest first (lamport, then author and seq for a stable order). */
    @Query(
        """
        SELECT * FROM messages WHERE threadId = :threadId AND status IN ('RECEIVED', 'LOCKED', 'SENT_LOCAL')
        ORDER BY lamport DESC, authorDeviceId DESC, seq DESC LIMIT :limit
        """,
    )
    fun observeNewest(threadId: String, limit: Int): Flow<List<MessageEntity>>

    /** The newest visible message of every thread that has one. */
    @Query(
        """
        SELECT m.* FROM messages m WHERE m.status IN ('RECEIVED', 'LOCKED', 'SENT_LOCAL') AND NOT EXISTS (
            SELECT 1 FROM messages n WHERE n.threadId = m.threadId AND n.status IN ('RECEIVED', 'LOCKED', 'SENT_LOCAL')
            AND (n.lamport > m.lamport OR (n.lamport = m.lamport AND (n.authorDeviceId > m.authorDeviceId OR
                (n.authorDeviceId = m.authorDeviceId AND n.seq > m.seq))))
        )
        """,
    )
    fun observeLatestPerThread(): Flow<List<MessageEntity>>

    /**
     * Unread messages per thread: visible, not written by [self], and received after the thread's read mark (all of
     * them without one).
     */
    @Query(
        """
        SELECT m.threadId AS threadId, COUNT(*) AS unread FROM messages m
        LEFT JOIN read_marks r ON r.threadId = m.threadId
        WHERE m.status IN ('RECEIVED', 'LOCKED') AND m.authorDeviceId != :self
        AND m.receivedAtEpochMs > COALESCE(r.lastReadAtEpochMs, -1)
        GROUP BY m.threadId
        """,
    )
    fun observeUnread(self: DeviceId): Flow<List<UnreadCount>>

    @Query("SELECT MAX(receivedAtEpochMs) FROM messages WHERE threadId = :threadId")
    suspend fun latestReceivedAt(threadId: String): Long?

    @Query("SELECT * FROM messages WHERE status = :status ORDER BY authorDeviceId, threadId, seq")
    suspend fun withStatus(status: MessageStatus): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE status = 'LOCKED' AND threadId = :threadId")
    suspend fun lockedIn(threadId: String): List<MessageEntity>

    @Query("SELECT COUNT(*) FROM messages WHERE status = 'HELD' AND authorDeviceId = :author")
    suspend fun heldCountFor(author: DeviceId): Int

    /** The rows the cap drops first: the earliest received, and among those the farthest ahead (highest seq). */
    @Query(
        """
        SELECT * FROM messages WHERE status = 'HELD' AND authorDeviceId = :author
        ORDER BY receivedAtEpochMs, seq DESC, messageId LIMIT :limit
        """,
    )
    suspend fun oldestHeldFor(author: DeviceId, limit: Int): List<MessageEntity>

    @Query(
        """
        SELECT COUNT(*) FROM messages WHERE status = 'HELD' AND authorDeviceId = :author AND threadId = :threadId
        AND seq = :seq
        """,
    )
    suspend fun heldAt(author: DeviceId, threadId: String, seq: Long): Int

    /** The stored (not held) message of [author] at [seq] in [threadId], quarantined ones included. */
    @Query(
        """
        SELECT * FROM messages WHERE status != 'HELD' AND authorDeviceId = :author AND threadId = :threadId
        AND seq = :seq LIMIT 1
        """,
    )
    suspend fun storedAt(author: DeviceId, threadId: String, seq: Long): MessageEntity?

    /** The visible message of [author] in [threadId] with the highest `seq` below [seq] (a late one's predecessor). */
    @Query(
        """
        SELECT * FROM messages WHERE authorDeviceId = :author AND threadId = :threadId AND seq < :seq
        AND status IN ('RECEIVED', 'LOCKED', 'SENT_LOCAL') ORDER BY seq DESC LIMIT 1
        """,
    )
    suspend fun displayedBefore(author: DeviceId, threadId: String, seq: Long): MessageEntity?

    /** The visible message of [author] in [threadId] with the lowest `seq` above [seq] (a late message's successor). */
    @Query(
        """
        SELECT * FROM messages WHERE authorDeviceId = :author AND threadId = :threadId AND seq > :seq
        AND status IN ('RECEIVED', 'LOCKED', 'SENT_LOCAL') ORDER BY seq LIMIT 1
        """,
    )
    suspend fun displayedAfter(author: DeviceId, threadId: String, seq: Long): MessageEntity?

    @Query("SELECT * FROM message_seq WHERE threadId = :threadId AND authorDeviceId = :author")
    suspend fun seq(threadId: String, author: DeviceId): MessageSeqEntity?

    @Upsert
    suspend fun upsertSeq(seq: MessageSeqEntity)

    @Upsert
    suspend fun upsertReadMark(mark: ReadMarkEntity)

    @Query("SELECT * FROM messages ORDER BY lamport")
    suspend fun all(): List<MessageEntity>

    /** Every thread that has a stored message (any status). */
    @Query("SELECT DISTINCT threadId FROM messages ORDER BY threadId")
    suspend fun threadIds(): List<String>

    /**
     * The messages of [threadId] the chat retention removes at [cut] (M7 §2, D-M7-52): any status, received by this
     * device (its own clock) before [cut]. Never by the author's signed `createdAt` (nor the time shown, which is that
     * date for an author's first message): a clock behind, or a forged date, would otherwise make a message vanish on
     * arrival; a date ahead cannot outlive the retention either. Except this device's own messages still queued for
     * upload (they are not on the server yet).
     */
    @Query(
        """
        SELECT messageId, blobId FROM messages WHERE threadId = :threadId
        AND receivedAtEpochMs < :cut
        AND messageId NOT IN (SELECT refId FROM outbox WHERE kind = 'MESSAGE')
        """,
    )
    suspend fun prunable(threadId: String, cut: Long): List<PrunableMessage>

    /**
     * This device's own messages of [threadId] still queued for upload and written before [cut] (their `receivedAt` is
     * when this device wrote them): what a purge drops with their outbox rows, so they never upload deleted history
     * (D-M7-59).
     */
    @Query(
        """
        SELECT messageId, blobId FROM messages WHERE threadId = :threadId
        AND receivedAtEpochMs < :cut
        AND messageId IN (SELECT refId FROM outbox WHERE kind = 'MESSAGE')
        """,
    )
    suspend fun queuedBefore(threadId: String, cut: Long): List<PrunableMessage>

    /** How many messages of [threadId] are dated before [cut] as [prunable] dates them, queued ones included. */
    @Query(
        """
        SELECT COUNT(*) FROM messages WHERE threadId = :threadId
        AND receivedAtEpochMs < :cut
        """,
    )
    suspend fun countOlderThan(threadId: String, cut: Long): Int

    @Query("DELETE FROM messages WHERE messageId IN (:messageIds)")
    suspend fun deleteIds(messageIds: List<String>): Int

    /** How many stored messages name [blobId]. */
    @Query("SELECT COUNT(*) FROM messages WHERE blobId = :blobId")
    suspend fun countNaming(blobId: String): Int

    /** The read mark of [threadId] when it is older than [cut]. */
    @Query("DELETE FROM read_marks WHERE threadId = :threadId AND lastReadAtEpochMs < :cut")
    suspend fun deleteReadMarkBefore(threadId: String, cut: Long): Int

    /** Threads with a visible message (RECEIVED, LOCKED, SENT_LOCAL). */
    @Query(
        """
        SELECT DISTINCT threadId FROM messages WHERE status IN ('RECEIVED', 'LOCKED', 'SENT_LOCAL') ORDER BY threadId
        """,
    )
    suspend fun visibleThreadIds(): List<String>

    /** The visible messages of [threadId], oldest first (lamport, then author device and seq). */
    @Query(
        """
        SELECT * FROM messages WHERE threadId = :threadId AND status IN ('RECEIVED', 'LOCKED', 'SENT_LOCAL')
        ORDER BY lamport, authorDeviceId, seq
        """,
    )
    suspend fun visibleIn(threadId: String): List<MessageEntity>

    @Query("DELETE FROM messages WHERE householdId = :householdId")
    suspend fun deleteFor(householdId: String): Int

    @Query("DELETE FROM messages")
    suspend fun deleteAll(): Int

    @Query("DELETE FROM message_seq")
    suspend fun deleteAllSeqs(): Int

    @Query("DELETE FROM read_marks")
    suspend fun deleteAllReadMarks(): Int
}

/** One row of [MessageDao.observeUnread]. */
data class UnreadCount(val threadId: String, val unread: Int)

/** One row of [MessageDao.prunable]: ids only. */
data class PrunableMessage(val messageId: String, val blobId: String?)

/** Thread keys this device verified (M4 §1.2). */
@Dao
interface ThreadKeyDao {
    @Query(
        "SELECT * FROM thread_keys WHERE threadId = :threadId AND epoch = :epoch AND createdByDeviceId = :creator",
    )
    suspend fun get(threadId: String, epoch: Long, creator: DeviceId): ThreadKeyEntity?

    @Upsert
    suspend fun upsert(key: ThreadKeyEntity)

    @Query("SELECT * FROM thread_keys WHERE threadId = :threadId ORDER BY epoch, createdByDeviceId")
    suspend fun forThread(threadId: String): List<ThreadKeyEntity>

    @Query("SELECT * FROM thread_keys WHERE threadId = :threadId ORDER BY epoch, createdByDeviceId")
    fun observeThread(threadId: String): Flow<List<ThreadKeyEntity>>

    @Query("SELECT * FROM thread_keys WHERE key IS NULL")
    suspend fun withoutKey(): List<ThreadKeyEntity>

    /** This device's own documents the server has not confirmed yet ([ThreadKeyEntity.confirmed]). */
    @Query("SELECT * FROM thread_keys WHERE createdByDeviceId = :self AND confirmed = 0")
    suspend fun unconfirmed(self: DeviceId): List<ThreadKeyEntity>

    @Query("DELETE FROM thread_keys WHERE threadId = :threadId AND epoch = :epoch AND createdByDeviceId = :creator")
    suspend fun delete(threadId: String, epoch: Long, creator: DeviceId): Int

    @Query("SELECT * FROM thread_keys ORDER BY threadId, epoch")
    suspend fun all(): List<ThreadKeyEntity>

    @Query("DELETE FROM thread_keys")
    suspend fun deleteAll(): Int
}

/** The blob cache index (M4 §2). */
@Dao
interface BlobDao {
    @Query("SELECT * FROM blobs WHERE blobId = :blobId")
    suspend fun get(blobId: String): BlobEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(blob: BlobEntity)

    @Query("UPDATE blobs SET lastUsedEpochMs = :at WHERE blobId = :blobId")
    suspend fun touch(blobId: String, at: Long)

    @Query("SELECT COALESCE(SUM(size), 0) FROM blobs")
    suspend fun totalSize(): Long

    /** Least recently used first. */
    @Query("SELECT * FROM blobs ORDER BY lastUsedEpochMs, blobId")
    suspend fun leastRecentlyUsed(): List<BlobEntity>

    @Query("DELETE FROM blobs WHERE blobId = :blobId")
    suspend fun delete(blobId: String): Int

    @Query("SELECT * FROM blobs")
    suspend fun all(): List<BlobEntity>

    @Query("DELETE FROM blobs")
    suspend fun deleteAll(): Int
}

/** Chat-side reads of the household tables (kids for thread lists, photos for uploads). */
@Dao
interface ChatHouseholdDao {
    /** Every kid that is not deleted, archived ones included (their threads must rotate them out). */
    @Query("SELECT * FROM kids WHERE deleted = 0 ORDER BY birthYearMonth, kidId")
    suspend fun kids(): List<KidEntity>

    @Query("SELECT * FROM kids WHERE archived = 0 AND deleted = 0 ORDER BY birthYearMonth, kidId")
    fun observeActiveKids(): Flow<List<KidEntity>>

    /** Whether kid [kidId] is archived (false while it is unknown); re-emits when that changes. */
    @Query("SELECT COUNT(*) > 0 FROM kids WHERE kidId = :kidId AND archived = 1")
    fun observeArchived(kidId: String): Flow<Boolean>

    /**
     * Photos that are the current photo of a submission still waiting for a decision and not yet queued as a blob
     * (M4 §2, D-M2-3): what the chore photo upload sends. A photo the kid replaced is never uploaded.
     */
    @Query(
        """
        SELECT p.* FROM photos p JOIN chore_submissions s ON s.submissionId = p.submissionId
        WHERE p.blobId IS NULL AND s.status = 'SUBMITTED' AND s.deleted = 0 AND s.photoFileName = p.fileName
        ORDER BY p.createdAtEpochMs, p.fileName
        """,
    )
    suspend fun pendingUploads(): List<PhotoEntity>

    @Query("UPDATE photos SET blobId = :blobId WHERE fileName = :fileName AND blobId IS NULL")
    suspend fun markUploaded(fileName: String, blobId: String): Int

    /** Photos never attached to a submission, created before [before] (D-M2-14 orphans). */
    @Query("SELECT * FROM photos WHERE submissionId IS NULL AND createdAtEpochMs < :before")
    suspend fun orphansBefore(before: Long): List<PhotoEntity>
}
