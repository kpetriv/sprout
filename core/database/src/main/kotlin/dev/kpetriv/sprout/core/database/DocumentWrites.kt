package dev.kpetriv.sprout.core.database

import androidx.room.withTransaction
import dev.kpetriv.sprout.core.database.entity.AuditAction
import dev.kpetriv.sprout.core.database.entity.DeviceStateEntity
import dev.kpetriv.sprout.core.database.entity.DocumentJson
import dev.kpetriv.sprout.core.database.entity.OutboxEntity
import dev.kpetriv.sprout.core.database.entity.OutboxKind
import dev.kpetriv.sprout.core.domainledger.EventAuthor
import dev.kpetriv.sprout.core.domainledger.LamportClock
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.time.Clock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put
import java.util.WeakHashMap

/** The synced document collections, as written to [OutboxEntity.docType]. */
object DocTypes {
    const val CHORE: String = "chore"
    const val CHORE_SUBMISSION: String = "chore_submission"
    const val REWARD: String = "reward"
    const val ALLOWANCE_SCHEDULE: String = "allowance_schedule"
    const val SAVINGS_GOAL: String = "savings_goal"
    const val INTEREST_POLICY: String = "interest_policy"
}

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
 * value, queue the document in the outbox (`DOCUMENT`, `docType`, `docId`, `lamport`) and append an audit row.
 *
 * Documents share the device Lamport clock ([DeviceClock], `device_state`) with ledger events, so a document write
 * always sorts after everything this device has seen, and never reuses a lamport a ledger event took. The clock only
 * moves forward ([advanceDeviceLamport]).
 */
internal class DocumentWrites(private val database: SproutDatabase, private val clock: Clock) {
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
     * Queues [payload] as the latest state of document [docType]/[docId] written at [lamport], and audits it as
     * [action] on [docId]. [details] must hold ids and codes only, never free text. Must run inside a transaction.
     */
    @Suppress("LongParameterList") // The document's identity, content and audit row; bundling would only rename them.
    suspend fun record(
        author: EventAuthor,
        docType: String,
        docId: String,
        lamport: Long,
        payload: JsonElement,
        action: AuditAction,
        details: JsonObjectBuilder.() -> Unit = {},
    ) {
        val now = clock.now().toEpochMilliseconds()
        outbox.upsertDocument(
            OutboxEntity(
                kind = OutboxKind.DOCUMENT,
                refId = "$docType/$docId",
                payload = DocumentJson.json.encodeToString(JsonElement.serializer(), payload),
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
                details()
            },
        )
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

/** Stores [lamport] as this device's clock unless the stored value is already higher. Inside a transaction. */
internal suspend fun SproutDatabase.advanceDeviceLamport(deviceId: DeviceId, lamport: Long) {
    val stored = deviceStateDao().get()?.lamport ?: 0
    deviceStateDao().upsert(DeviceStateEntity(deviceId = deviceId, lamport = maxOf(stored, lamport)))
}
