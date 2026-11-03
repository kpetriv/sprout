package dev.kpetriv.sprout.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import dev.kpetriv.sprout.core.database.entity.DeviceEntity
import dev.kpetriv.sprout.core.database.entity.DocumentInboxEntity
import dev.kpetriv.sprout.core.database.entity.LedgerInboxEntity
import dev.kpetriv.sprout.core.database.entity.ParentKeyEntity
import dev.kpetriv.sprout.core.database.entity.SessionEntity
import dev.kpetriv.sprout.core.database.entity.SyncCursorEntity
import dev.kpetriv.sprout.core.domainledger.EventId
import dev.kpetriv.sprout.core.model.DeviceId
import kotlinx.coroutines.flow.Flow

/** The device registry mirror. Written only by the sync applier. */
@Dao
interface DeviceDao {
    @Upsert
    suspend fun upsert(device: DeviceEntity)

    @Query("SELECT * FROM devices WHERE deviceId = :deviceId")
    suspend fun get(deviceId: DeviceId): DeviceEntity?

    @Query("SELECT * FROM devices ORDER BY registeredAtEpochMs, deviceId")
    suspend fun all(): List<DeviceEntity>

    @Query("SELECT * FROM devices ORDER BY registeredAtEpochMs, deviceId")
    fun observeAll(): Flow<List<DeviceEntity>>

    @Query("SELECT COUNT(*) FROM devices")
    suspend fun count(): Int
}

/** Registered parent authorisation keys. Written only by the sync applier and `registerParentKey`. */
@Dao
interface ParentKeyDao {
    @Upsert
    suspend fun upsert(key: ParentKeyEntity)

    @Query("SELECT * FROM parent_keys WHERE fingerprint = :fingerprint")
    suspend fun get(fingerprint: String): ParentKeyEntity?

    @Query("SELECT * FROM parent_keys ORDER BY validFromLamport, fingerprint")
    suspend fun all(): List<ParentKeyEntity>
}

/** Pulled events waiting for a missing predecessor (or account). */
@Dao
interface LedgerInboxDao {
    /** Keeps the first copy: a held event's content never changes under its `(eventId, hash)`. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(event: LedgerInboxEntity): Long

    @Query("SELECT * FROM ledger_inbox ORDER BY lamport, authorDeviceId, eventId")
    suspend fun all(): List<LedgerInboxEntity>

    @Query("DELETE FROM ledger_inbox WHERE eventId = :eventId AND hash = :hash")
    suspend fun delete(eventId: EventId, hash: String): Int

    @Query("SELECT COUNT(*) FROM ledger_inbox")
    suspend fun count(): Int
}

/** Pulled documents refused for a lamport too far ahead. */
@Dao
interface DocumentInboxDao {
    @Upsert
    suspend fun upsert(document: DocumentInboxEntity)

    @Query("SELECT * FROM document_inbox ORDER BY receivedAtEpochMs, docType, docId")
    suspend fun all(): List<DocumentInboxEntity>

    @Query("DELETE FROM document_inbox WHERE docType = :docType AND docId = :docId")
    suspend fun delete(docType: String, docId: String): Int
}

/** Per-collection pull positions. */
@Dao
interface SyncCursorDao {
    @Upsert
    suspend fun upsert(cursors: List<SyncCursorEntity>)

    @Query("SELECT * FROM sync_cursor")
    suspend fun all(): List<SyncCursorEntity>

    @Query("DELETE FROM sync_cursor")
    suspend fun deleteAll()
}

/** The signed-in session (at most one row). */
@Dao
interface SessionDao {
    @Upsert
    suspend fun upsert(session: SessionEntity)

    @Query("SELECT * FROM session WHERE id = ${SessionEntity.SINGLETON_ID}")
    suspend fun get(): SessionEntity?

    @Query("SELECT * FROM session WHERE id = ${SessionEntity.SINGLETON_ID}")
    fun observe(): Flow<SessionEntity?>

    @Query("DELETE FROM session")
    suspend fun deleteAll(): Int
}
