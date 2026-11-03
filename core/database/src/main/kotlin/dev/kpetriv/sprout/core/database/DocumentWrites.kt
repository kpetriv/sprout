package dev.kpetriv.sprout.core.database

import androidx.room.withTransaction
import dev.kpetriv.sprout.core.crypto.DeviceSigner
import dev.kpetriv.sprout.core.database.entity.AuditAction
import dev.kpetriv.sprout.core.database.entity.DeviceStateEntity
import dev.kpetriv.sprout.core.database.entity.DocumentStamp
import dev.kpetriv.sprout.core.database.entity.OutboxEntity
import dev.kpetriv.sprout.core.database.entity.OutboxKind
import dev.kpetriv.sprout.core.domainledger.EventAuthor
import dev.kpetriv.sprout.core.domainledger.LamportClock
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.sync.DocTypes
import dev.kpetriv.sprout.core.sync.DocumentEnvelope
import dev.kpetriv.sprout.core.sync.DocumentJsonCodec
import dev.kpetriv.sprout.core.time.Clock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put
import java.util.WeakHashMap
import kotlin.io.encoding.Base64

/**
 * This device's persisted Lamport clock (`device_state`), shared by ledger events and document writes, and the lock
 * that serialises everyone who takes a value from it.
 *
 * Every lamport this device hands out is allocated under [withLock]: [RoomLedgerWriter] holds it from reading the
 * next value until the event is stored (signing happens in between, outside any transaction), and every document
 * write holds it around its transaction ([DocumentWrites.transaction]). So a document can never take the lamport an
 * event being signed already took, and every value is used once. One lock per database (process-wide), always taken
 * **before** a Room transaction starts and never inside one, so it cannot deadlock against Room's transaction thread.
 */
internal class DeviceClock private constructor(private val database: SproutDatabase) {
    private val mutex = Mutex()

    /** Runs [block] holding this device's clock lock. Not reentrant: never call it from inside [block]. */
    suspend fun <R> withLock(block: suspend () -> R): R = mutex.withLock { block() }

    /**
     * The value the next local event or document gets: `max(stored, highest stored event) + 1`. Not persisted. Call
     * inside a transaction, under [withLock].
     */
    suspend fun peekNext(): Long {
        val lamport = LamportClock(database.deviceStateDao().get()?.lamport ?: 0)
        lamport.observe(database.ledgerEventDao().maxLamport())
        return lamport.next()
    }

    /** Takes [peekNext] and persists it as this device's clock. Inside a transaction, under [withLock]. */
    suspend fun takeNext(deviceId: DeviceId): Long = peekNext().also { database.advanceDeviceLamport(deviceId, it) }

    /**
     * The highest lamport this device has seen: its stored clock or the highest verified event, whichever is higher.
     * The base of the remote lamport-jump bound. Inside a transaction.
     */
    suspend fun current(): Long =
        maxOf(database.deviceStateDao().get()?.lamport ?: 0, database.ledgerEventDao().maxLamport())

    companion object {
        private val clocks = WeakHashMap<SproutDatabase, DeviceClock>()

        /** The clock of [database]; the same instance for every caller in the process. */
        fun of(database: SproutDatabase): DeviceClock = synchronized(clocks) {
            clocks.getOrPut(database) { DeviceClock(database) }
        }
    }
}

/**
 * What every document write does besides the row itself, in the caller's transaction: take the next device Lamport
 * value, build the document's `DocumentEnvelope` (household, uid and device from the author, `docType` from
 * [DocTypes], `docId`, `lamport`, `deleted`, `payload`), sign it with this device's key ([DeviceSigner]), queue the
 * signed envelope in the outbox (`DOCUMENT`, `docType`, `docId`, `lamport`) and append an audit row. [record] returns
 * the [DocumentStamp] the caller stores on the row, so the row and its queued envelope always agree.
 *
 * Signing is a Keystore call made inside the caller's transaction: the envelope covers the lamport, which is only
 * taken inside it. It holds the write lock for one signature; nothing in the signer touches the database.
 *
 * Documents share the device Lamport clock ([DeviceClock], `device_state`) with ledger events, so a document write
 * always sorts after everything this device has seen, and never reuses a lamport a ledger event took. The clock only
 * moves forward ([advanceDeviceLamport]).
 *
 * The payload must be the canonical form of the row's state (built from the stored entity's domain object), so a
 * receiver that rebuilds the payload from its row (the reward catalogue check) gets the same bytes.
 */
internal class DocumentWrites(
    private val database: SproutDatabase,
    private val clock: Clock,
    private val signer: DeviceSigner,
) {
    private val outbox = database.outboxDao()
    private val audit = database.auditLogDao()
    private val deviceClock = DeviceClock.of(database)

    /** A Room transaction under the device clock's lock; every document write that takes a lamport runs in one. */
    suspend fun <R> transaction(block: suspend () -> R): R = deviceClock.withLock { database.withTransaction(block) }

    /** The next device Lamport value, persisted. Must run inside [transaction]. */
    suspend fun nextLamport(deviceId: DeviceId): Long = deviceClock.takeNext(deviceId)

    /** The lamport the next local event or document will get at the earliest; not persisted. Inside [transaction]. */
    suspend fun peekLamport(): Long = deviceClock.peekNext()

    /**
     * Signs [payload] as the latest state of document [docType]/[docId] written at [lamport] by [author] (a tombstone
     * when [deleted]), queues the signed envelope, and audits it as [action] on [docId]. [details] must hold ids and
     * codes only, never free text. Must run inside [transaction]. Returns the stamp to store on the row.
     */
    @Suppress("LongParameterList") // The document's identity, content and audit row; bundling would only rename them.
    suspend fun record(
        author: EventAuthor,
        docType: String,
        docId: String,
        lamport: Long,
        payload: JsonObject,
        action: AuditAction,
        deleted: Boolean = false,
        details: JsonObjectBuilder.() -> Unit = {},
    ): DocumentStamp {
        val now = clock.now().toEpochMilliseconds()
        val unsigned = DocumentEnvelope(
            householdId = author.householdId,
            docType = docType,
            docId = docId,
            authorUid = author.uid,
            authorDeviceId = author.deviceId,
            lamport = lamport,
            deleted = deleted,
            payload = payload,
        )
        val signature = Base64.Default.encode(signer.sign(unsigned.signedPayload()))
        val envelope = unsigned.copy(signature = signature)
        outbox.upsertDocument(
            OutboxEntity(
                kind = OutboxKind.DOCUMENT,
                refId = "$docType/$docId",
                payload = DocumentJsonCodec.encode(envelope),
                createdAtEpochMs = now,
                docType = docType,
                docId = docId,
                lamport = lamport,
            ),
        )
        audit.insert(
            auditEntry(author, now, action, docId) {
                put("docType", docType)
                put("lamport", lamport)
                if (deleted) put("deleted", true)
                details()
            },
        )
        return DocumentStamp(author.uid, author.deviceId, signature, deleted)
    }

    /** An audit row for a local, unsynced change (no outbox row). Must run inside a transaction. */
    suspend fun auditOnly(
        author: EventAuthor,
        action: AuditAction,
        target: String,
        details: JsonObjectBuilder.() -> Unit = {},
    ) {
        audit.insert(auditEntry(author, clock.now().toEpochMilliseconds(), action, target, details))
    }
}

/** Audit rows for local, unsynced changes (no outbox row, no lamport). */
internal class LocalAudit(private val database: SproutDatabase, private val clock: Clock) {
    /** Appends an audit row for [author] on [target]. [details] holds ids and codes only. Inside a transaction. */
    suspend fun auditOnly(
        author: EventAuthor,
        action: AuditAction,
        target: String,
        details: JsonObjectBuilder.() -> Unit = {},
    ) {
        database.auditLogDao().insert(auditEntry(author, clock.now().toEpochMilliseconds(), action, target, details))
    }
}

/** Stores [lamport] as this device's clock unless the stored value is already higher. Inside a transaction. */
internal suspend fun SproutDatabase.advanceDeviceLamport(deviceId: DeviceId, lamport: Long) {
    val stored = deviceStateDao().get()?.lamport ?: 0
    deviceStateDao().upsert(DeviceStateEntity(deviceId = deviceId, lamport = maxOf(stored, lamport)))
}

/**
 * Stores [lamport] as this device's clock unless the stored value is already higher, keeping the stored device id
 * (or the session's when there is none yet): the clock observing a remote lamport (`LamportClock.observe`). Callers
 * bound [lamport] by `maxLamportJump` first. Inside a transaction.
 */
internal suspend fun SproutDatabase.observeDeviceLamport(lamport: Long) {
    val stored = deviceStateDao().get()
    if (stored != null && stored.lamport >= lamport) return
    val deviceId = stored?.deviceId ?: sessionDao().get()?.deviceId ?: UNREGISTERED_DEVICE
    deviceStateDao().upsert(DeviceStateEntity(deviceId = deviceId, lamport = lamport))
}

/** `device_state.deviceId` before this device wrote anything or signed in (informational only). */
private val UNREGISTERED_DEVICE = DeviceId("unregistered")
