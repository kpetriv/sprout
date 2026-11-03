package dev.kpetriv.sprout.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.kpetriv.sprout.core.database.entity.LedgerEventEntity
import dev.kpetriv.sprout.core.domainledger.EventId
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.KidId
import kotlinx.coroutines.flow.Flow

/** One stored copy of an eventId, as [LedgerEventDao.copiesOf] lists it. */
data class StoredCopy(val hash: String, val quarantined: Boolean)

/** Append-only access to `ledger_events`. There is deliberately no delete; the only update is [quarantine]. */
@Dao
@Suppress("TooManyFunctions") // Inserts, chain heads, verified and quarantined reads.
interface LedgerEventDao {
    /** Fails on a duplicate `(eventId, hash)`: callers check [copiesOf] first, inside the same transaction. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(event: LedgerEventEntity)

    /** Every stored copy of [eventId] (quarantined ones included), oldest first. */
    @Query("SELECT hash, quarantined FROM ledger_events WHERE eventId = :eventId ORDER BY rowid")
    suspend fun copiesOf(eventId: EventId): List<StoredCopy>

    /** Every row not yet quarantined, in the whole household, in no particular order (the domain sorts). */
    @Query("SELECT * FROM ledger_events WHERE quarantined = 0")
    suspend fun allVerified(): List<LedgerEventEntity>

    /** Marks one copy as quarantined. The row stays (it is evidence); every read and projection ignores it. */
    @Query(
        """
        UPDATE ledger_events SET quarantined = 1, quarantineReason = :reason
        WHERE eventId = :eventId AND hash = :hash
        """,
    )
    suspend fun quarantine(eventId: EventId, hash: String, reason: String)

    /**
     * The newest [limit] verified events of [kidId], newest first by `(lamport, authorDeviceId, eventId)`.
     * SQLite compares text by UTF-8 bytes; ids are ASCII in practice, where that equals the domain's UTF-16 order.
     */
    @Query(
        """
        SELECT * FROM ledger_events WHERE kidId = :kidId AND quarantined = 0
        ORDER BY lamport DESC, authorDeviceId DESC, eventId DESC LIMIT :limit
        """,
    )
    fun observeNewestForKid(kidId: KidId, limit: Int): Flow<List<LedgerEventEntity>>

    /** Hash of [authorDeviceId]'s highest-lamport verified event: the head of its chain. */
    @Query(
        """
        SELECT hash FROM ledger_events WHERE authorDeviceId = :authorDeviceId AND quarantined = 0
        ORDER BY lamport DESC LIMIT 1
        """,
    )
    suspend fun headHash(authorDeviceId: DeviceId): String?

    /** Highest verified lamport written by [authorDeviceId], or null if it has written none. */
    @Query("SELECT MAX(lamport) FROM ledger_events WHERE authorDeviceId = :authorDeviceId AND quarantined = 0")
    suspend fun maxLamportFor(authorDeviceId: DeviceId): Long?

    /** Highest verified lamport, or 0. Quarantined events are ignored so a forged lamport cannot inflate the clock. */
    @Query("SELECT COALESCE(MAX(lamport), 0) FROM ledger_events WHERE quarantined = 0")
    suspend fun maxLamport(): Long

    @Query("SELECT COUNT(*) FROM ledger_events")
    suspend fun count(): Int

    /** Quarantined rows, newest first, for the parent's "Rejected events" card. */
    @Query("SELECT * FROM ledger_events WHERE quarantined = 1 ORDER BY receivedAtEpochMs DESC, eventId")
    fun observeQuarantined(): Flow<List<LedgerEventEntity>>

    /**
     * Hashes of [authorDeviceId]'s stored events whose `prevHash` is [prevHash] (null: genesis) and that count as
     * branches for fork detection (`IngestOrdering.plan`'s `storedSuccessors`): verified rows, and rows quarantined
     * after their device signature was checked (`FORKED_CHAIN`, or a broken ledger rule: [invariantPrefix]). Rows
     * the verifier refused (bad signature, unknown or revoked device, ...) are not proven to be the author's and are
     * never treated as a branch, exactly as such events are never planned when they arrive.
     */
    @Query(
        """
        SELECT hash FROM ledger_events WHERE authorDeviceId = :authorDeviceId AND prevHash IS :prevHash
        AND (quarantined = 0 OR quarantineReason = :forkedReason
             OR substr(quarantineReason, 1, length(:invariantPrefix)) = :invariantPrefix)
        """,
    )
    suspend fun branchHashes(
        authorDeviceId: DeviceId,
        prevHash: String?,
        forkedReason: String,
        invariantPrefix: String,
    ): List<String>

    /** [authorDeviceId]'s stored rows (quarantined ones included) with one of [hashes]. */
    @Query("SELECT * FROM ledger_events WHERE authorDeviceId = :authorDeviceId AND hash IN (:hashes)")
    suspend fun byHashes(authorDeviceId: DeviceId, hashes: List<String>): List<LedgerEventEntity>

    /** [authorDeviceId]'s stored rows (quarantined ones included) whose `prevHash` is one of [prevHashes]. */
    @Query("SELECT * FROM ledger_events WHERE authorDeviceId = :authorDeviceId AND prevHash IN (:prevHashes)")
    suspend fun childrenOf(authorDeviceId: DeviceId, prevHashes: List<String>): List<LedgerEventEntity>

    /** Hashes of [authorDeviceId]'s rows quarantined with exactly [reason]. */
    @Query(
        """
        SELECT hash FROM ledger_events
        WHERE authorDeviceId = :authorDeviceId AND quarantined = 1 AND quarantineReason = :reason
        """,
    )
    suspend fun quarantinedHashes(authorDeviceId: DeviceId, reason: String): List<String>

    /** Every row, quarantined ones included; for tests and the audit view. */
    @Query("SELECT * FROM ledger_events ORDER BY rowid")
    suspend fun all(): List<LedgerEventEntity>
}
