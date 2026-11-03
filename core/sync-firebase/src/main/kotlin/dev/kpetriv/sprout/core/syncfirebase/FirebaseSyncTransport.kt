package dev.kpetriv.sprout.core.syncfirebase

import dev.kpetriv.sprout.core.domainledger.RegisteredDevice
import dev.kpetriv.sprout.core.model.BlobId
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.sync.BlobChunkJson
import dev.kpetriv.sprout.core.sync.BlobIncompleteException
import dev.kpetriv.sprout.core.sync.ConflictResolver
import dev.kpetriv.sprout.core.sync.DocTypes
import dev.kpetriv.sprout.core.sync.DocumentEnvelope
import dev.kpetriv.sprout.core.sync.OutboxItem
import dev.kpetriv.sprout.core.sync.PullPage
import dev.kpetriv.sprout.core.sync.PushItem
import dev.kpetriv.sprout.core.sync.PushItemId
import dev.kpetriv.sprout.core.sync.PushResult
import dev.kpetriv.sprout.core.sync.RemoteChange
import dev.kpetriv.sprout.core.sync.StoredDocument
import dev.kpetriv.sprout.core.sync.SyncCursor
import dev.kpetriv.sprout.core.sync.SyncError
import dev.kpetriv.sprout.core.sync.SyncException
import dev.kpetriv.sprout.core.sync.SyncTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [SyncTransport] over Firestore (M3 §5), talking to the SDK only through [FirestoreFacade]. Shapes and paths are
 * [StoredShapes] and [FirestoreLayout]; `firestore/firestore.rules` enforces them.
 *
 * **Push** writes the items **in the order given** (outbox order, review M2), each stamped with `serverReceivedAt`.
 * Consecutive items of one kind share a batch; a batch is cut where the kind changes, when its estimated rules cost
 * reaches [BATCH_BUDGET] (at most [MAX_EVENTS_PER_BATCH] events, [MAX_DOCUMENTS_PER_BATCH] documents or
 * [MAX_MESSAGES_PER_BATCH] messages), or at [batchSize] writes; a thread key document (16 wraps checked one by one) and
 * a blob chunk (up to 700 KB, plus its quota counter) always go alone. A batch is atomic, so when the rules refuse one
 * item the batch is retried item by item to find it. A refused item is then classified by reading the server copy: an
 * event already stored under the same hash, or a document whose server copy beats or equals this one
 * ([ConflictResolver]), is accepted (pushes are idempotent); an event whose id is taken by a different hash is stored
 * as the conflicting copy `events/{eventId}__{hash}`; anything else is `Permanent("permission-denied")`. Parent keys
 * are never pushed: the server registers them (`registerParentKey`, D-M3-34) and the rules refuse client writes, so
 * there is no `parent_key` document type and one is `Permanent("unknown-doc-type")` unsent.
 *
 * **Head of line, per lane** (review H3, D-M4-22): the items form two lanes ([OutboxItem.Lane]: events and documents;
 * messages and blob chunks). Once an event, a message or a blob chunk is refused for good (by the rules, or before any
 * write because it is malformed), or a blob chunk is deferred (below), every later item **of its lane** is left
 * unprocessed, so nothing that depends on it (the next event of the hash chain, the next message `seq`, the message
 * naming the blob) reaches the server first; the other lane goes on. A refused document stops nothing. A failure that
 * is not about one item (network, timeout, session, an unconfirmed membership) ends the whole push.
 *
 * **Chat** (M4 §3): messages (`messages/{messageId}`, ciphertext as bytes) and blob chunks (`blobs/{blobId}.{chunk}`,
 * data as bytes) are create-only. Each blob chunk is committed with an increment of the household's daily counter
 * `quotas/{yyyymmdd}` (UTC; `{chunks, last}`), which the rules cap at [DAILY_BLOB_CHUNKS] (audit H2). The counter id is
 * this device's UTC day, recomputed at every attempt. A refused message is read back: an identical stored copy makes it
 * accepted, anything else is `Permanent("permission-denied")`. A refused **blob chunk** is never permanent (audit
 * A2-M3, D-M4-22): chunks are authored and validated on the device, so the rules refuse one only for the daily budget,
 * for a counter day that is not the server's (around UTC midnight, or a device clock that is off) or for a revoked
 * member (which the membership check catches). An identical stored copy makes it accepted; otherwise it is
 * [SyncError.Transient], retried at the next UTC midnight (by this device's clock) when today's counter is used up and
 * with the engine's backoff otherwise, and it stops the chat lane only.
 *
 * **Household gone** needs positive evidence ([membership]), because a bare `PERMISSION_DENIED` also follows a
 * revoked session (sign in again), a rules deploy or a clock skew: the account is gone ([AuthState.UserGone] from a
 * forced ID-token refresh), the `hid` claim is gone after that refresh, or this device's own `devices/{deviceId}` entry
 * reads `REVOKED`. Then [SyncError.HouseholdGone]. A refusal while `households/{hid}` is readable is the item's own
 * (permanent); an unreadable household without such evidence is [SyncError.Transient].
 *
 * **Pull** reads the registry in full (devices and parent keys are small and change in place, so their cursor is a
 * digest of the collection), then each document collection in [DocTypes.applyOrder], then events, then messages,
 * each by `(serverReceivedAt, document id)` after its cursor, [pageSize] per collection. Parent keys come back as
 * registry entries only. A device row that does not parse, or whose id is not the SHA-256 of its public key (audit H1),
 * is dropped and counted in [PullPage.malformed], as is a document or an event that does not decode (audit I3). A
 * KID session reads only its own kid's messages (`where threadId == kidId`, the read scope the rules enforce; the kid
 * comes from [HouseholdScope.currentKidId], else this device's registry entry); a session whose kid cannot be
 * determined and that is not known to be a parent skips the messages lane and its listener instead of issuing a query
 * the rules refuse. Blob chunks are never pulled:
 * [fetchBlob] reads one blob's chunks by id.
 *
 * **Changes** come from snapshot listeners (messages included, scoped like the pull), open only while [foreground]
 * is true.
 */
@Singleton
@Suppress("TooManyFunctions") // Push, pull, changes and the membership check.
class FirebaseSyncTransport
@Suppress("LongParameterList")
internal constructor(
    private val firestore: FirestoreFacade,
    private val household: HouseholdScope,
    private val foreground: Flow<Boolean>,
    private val authProbe: AuthProbe,
    private val pageSize: Int,
    private val batchSize: Int,
    private val callTimeoutMs: Long,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
) : SyncTransport {

    @Inject
    constructor(
        firestore: FirestoreFacade,
        household: HouseholdScope,
        @AppForeground foreground: Flow<Boolean>,
        authProbe: AuthProbe,
    ) : this(firestore, household, foreground, authProbe, PAGE_SIZE, MAX_BATCH_WRITES, CALL_TIMEOUT_MS)

    init {
        require(pageSize > 0) { "pageSize must be positive" }
        require(batchSize in 1..MAX_BATCH_WRITES) { "batchSize must be 1..$MAX_BATCH_WRITES" }
    }

    override suspend fun isAvailable(): Boolean = household.currentHouseholdId() != null

    // ---- push ----

    /**
     * One item ready to commit: its [SetWrite] at [path], plus [extra] writes that must land in the same batch (a blob
     * chunk's daily quota counter).
     */
    private sealed class PendingWrite(val item: PushItem, val path: String, val data: Map<String, Any?>) {
        val id: PushItemId get() = item.id

        class Event(item: PushItem, path: String, data: Map<String, Any?>, val eventId: String, val hash: String) :
            PendingWrite(item, path, data)

        class Document(item: PushItem, path: String, data: Map<String, Any?>, val envelope: DocumentEnvelope) :
            PendingWrite(item, path, data)

        /** A create-only message: a refused rewrite is compared with the stored copy. */
        class Message(item: PushItem, path: String, data: Map<String, Any?>) : PendingWrite(item, path, data)

        /** A create-only blob chunk with the quota counter of [day] (`yyyymmdd`, UTC) it is counted against. */
        class BlobChunk(
            item: PushItem,
            path: String,
            data: Map<String, Any?>,
            val hid: HouseholdId,
            val docId: String,
        ) : PendingWrite(item, path, data) {
            fun quotaWrite(day: String): SetWrite = SetWrite(
                FirestoreLayout.quota(hid, day),
                mapOf(QUOTA_CHUNKS to FieldIncrement(1), QUOTA_LAST to docId),
                merge = true,
            )
        }

        /** What this item costs of a batch's rules evaluation budget ([BATCH_BUDGET]); [ALONE] = its own batch. */
        val cost: Int
            get() = when (this) {
                is Event -> EVENT_COST
                is Document -> if (envelope.docType == DocTypes.THREAD_KEY) ALONE else DOCUMENT_COST
                is Message -> MESSAGE_COST
                is BlobChunk -> ALONE
            }
    }

    /** What happened to one item retried on its own; [stop] says what the refusal ends (the rest stays unprocessed). */
    private sealed interface ItemOutcome {
        data object Accepted : ItemOutcome

        data class Rejected(val error: SyncError, val stop: Stop = Stop.NONE) : ItemOutcome
    }

    /** How far a refusal or a failed batch ends the push. */
    private enum class Stop {
        /** Nothing: the push goes on (a refused document, or a non-blocking refusal). */
        NONE,

        /** The item's lane: its later items stay unprocessed, the other lane goes on (D-M4-22). */
        LANE,

        /** The whole push: a failure that is not about one item (network, timeout, session). */
        PUSH,
    }

    /** The outcome lists of one push, filled in item order. */
    private class Outcomes {
        val accepted = mutableListOf<PushItemId>()
        val rejected = mutableListOf<Pair<PushItemId, SyncError>>()

        fun result() = PushResult(accepted, rejected)
    }

    /**
     * Writes [items] in order (review M2): consecutive items of one kind share a batch, a batch is cut where the kind
     * changes or the [cost] budget is spent, and a thread key or a blob chunk is always committed alone. A permanent
     * refusal of an event, a message or a blob chunk (before or after the commit), or a deferred blob chunk, ends its
     * lane: the later items of that lane are left unprocessed (review H3, D-M4-22) and the other lane goes on. A
     * failure that is not about one item leaves everything after it unprocessed.
     */
    override suspend fun push(items: List<PushItem>): PushResult {
        val run = PushRun(requireHousehold())
        for (item in items) {
            if (!run.offer(item)) return run.out.result()
        }
        run.flush()
        return run.out.result()
    }

    /** The state of one [push]: the outcomes, the lanes that stopped, and the batch being filled. */
    private inner class PushRun(private val hid: HouseholdId) {
        val out = Outcomes()
        private val stopped = mutableSetOf<OutboxItem.Lane>()
        private var batch = mutableListOf<PendingWrite>()

        /** Takes [item] in order (nothing for an item of a stopped lane); false when the whole push ends. */
        suspend fun offer(item: PushItem): Boolean {
            val lane = item.kind.lane
            if (lane in stopped) return true
            return when (val pending = pendingWrite(hid, item)) {
                is Either.Rejected -> {
                    out.rejected += pending.rejection
                    if (item.kind.blocksOnRefusal) stopped += lane
                    true
                }

                is Either.Write -> (fits(pending.write) || flush()).also { goesOn ->
                    if (goesOn && lane !in stopped) batch += pending.write
                }
            }
        }

        /** Commits the batch being filled; false when the whole push ends. */
        suspend fun flush(): Boolean {
            val full = batch
            batch = mutableListOf()
            if (full.isEmpty()) return true
            return when (pushBatch(hid, full, out)) {
                Stop.NONE -> true
                Stop.LANE -> true.also { stopped += full.first().item.kind.lane }
                Stop.PUSH -> false
            }
        }

        private fun fits(write: PendingWrite): Boolean = batch.isNotEmpty() &&
            batch.first().item.kind == write.item.kind &&
            write.cost != ALONE && batch.first().cost != ALONE &&
            batch.sumOf { it.cost } + write.cost <= BATCH_BUDGET && batch.size < batchSize
    }

    private fun pendingWrite(hid: HouseholdId, item: PushItem): Either = when (item) {
        is PushItem.Event -> eventWrite(hid, item)
        is PushItem.Document -> documentWrite(hid, item)
        is PushItem.Message -> messageWrite(hid, item)
        is PushItem.BlobChunk -> blobChunkWrite(hid, item)
    }

    /**
     * The writes of [batch]: each item's own, a blob chunk's quota counter included, under this device's UTC day at
     * the time of the call (recomputed at every attempt).
     */
    private fun writesOf(batch: List<PendingWrite>, day: String = utcDay()): List<SetWrite> = batch.flatMap { write ->
        listOfNotNull(SetWrite(write.path, write.data), (write as? PendingWrite.BlobChunk)?.quotaWrite(day))
    }

    /** Commits one batch (one kind, so one lane); says what the outcome ends ([Stop]). */
    private suspend fun pushBatch(hid: HouseholdId, batch: List<PendingWrite>, out: Outcomes): Stop {
        val failure = commitCode(writesOf(batch))
        return when {
            failure == null -> {
                out.accepted += batch.map { it.id }
                Stop.NONE
            }

            failure in ITEM_LEVEL_CODES -> {
                val denied = failure == FirestoreCode.PERMISSION_DENIED
                when (if (denied) membership(hid) else Membership.READABLE) {
                    Membership.GONE -> throw SyncException(SyncError.HouseholdGone)

                    // Nothing says which item is at fault, or that any is: retry the whole push later.
                    Membership.UNCONFIRMED -> {
                        batch.forEach { out.rejected += it.id to SyncError.Transient() }
                        Stop.PUSH
                    }

                    Membership.READABLE -> settleOneByOne(hid, batch, out)
                }
            }

            else -> {
                val error = unauthenticatedOrGone(failure)
                if (error == SyncError.HouseholdGone) throw SyncException(error)
                batch.forEach { out.rejected += it.id to error }
                Stop.PUSH
            }
        }
    }

    /**
     * Retries [batch] one item at a time; stops at an item whose refusal ends its lane (a permanent refusal of a kind
     * that blocks the outbox, a deferred blob chunk) or the whole push, leaving the items after it unprocessed.
     */
    private suspend fun settleOneByOne(hid: HouseholdId, batch: List<PendingWrite>, out: Outcomes): Stop {
        for (item in batch) {
            when (val outcome = settle(hid, item)) {
                ItemOutcome.Accepted -> out.accepted += item.id

                is ItemOutcome.Rejected -> {
                    out.rejected += item.id to outcome.error
                    val blocking = outcome.error is SyncError.Permanent && item.item.kind.blocksOnRefusal
                    val stop = if (blocking && outcome.stop == Stop.NONE) Stop.LANE else outcome.stop
                    if (stop != Stop.NONE) return stop
                }
            }
        }
        return Stop.NONE
    }

    private suspend fun settle(hid: HouseholdId, item: PendingWrite): ItemOutcome {
        val code = commitCode(writesOf(listOf(item))) ?: return ItemOutcome.Accepted
        return if (item is PendingWrite.BlobChunk) settleChunk(item, code) else settleRefused(hid, item, code)
    }

    /** A blob chunk whose commit failed with [code]: never refused for good (D-M4-22), see [resolveRefusedChunk]. */
    private suspend fun settleChunk(item: PendingWrite.BlobChunk, code: FirestoreCode): ItemOutcome = when (code) {
        FirestoreCode.PERMISSION_DENIED, FirestoreCode.ALREADY_EXISTS, FirestoreCode.FAILED_PRECONDITION ->
            resolveRefusedChunk(item)

        FirestoreCode.INVALID_ARGUMENT -> chunkDeferred()

        else -> ItemOutcome.Rejected(syncErrorOf(code), Stop.PUSH)
    }

    /** An event, a document or a message whose commit failed with [code] (chunks go through [settleChunk]). */
    private suspend fun settleRefused(hid: HouseholdId, item: PendingWrite, code: FirestoreCode): ItemOutcome =
        when (code) {
            FirestoreCode.PERMISSION_DENIED -> when (item) {
                is PendingWrite.Event -> resolveRefusedEvent(hid, item)
                is PendingWrite.Document -> resolveRefusedDocument(item)
                is PendingWrite.Message -> resolveRefusedCreate(item)
                is PendingWrite.BlobChunk -> resolveRefusedChunk(item)
            }

            // Not produced by sets today; an event that "already exists" is the idempotent case.
            FirestoreCode.ALREADY_EXISTS, FirestoreCode.FAILED_PRECONDITION -> when (item) {
                is PendingWrite.Event -> ItemOutcome.Accepted
                is PendingWrite.Message -> resolveRefusedCreate(item)
                is PendingWrite.BlobChunk -> resolveRefusedChunk(item)
                is PendingWrite.Document -> ItemOutcome.Rejected(permanentOf(code))
            }

            FirestoreCode.INVALID_ARGUMENT -> ItemOutcome.Rejected(permanentOf(code))

            else -> ItemOutcome.Rejected(syncErrorOf(code), Stop.PUSH)
        }

    @Suppress("ReturnCount")
    private suspend fun resolveRefusedEvent(hid: HouseholdId, item: PendingWrite.Event): ItemOutcome {
        val stored = readOrOutcome(item.path) { return it } ?: return ItemOutcome.Rejected(PERMISSION_DENIED)
        if (StoredShapes.eventHashOf(stored.data) == item.hash) return ItemOutcome.Accepted
        // The id is taken by a different copy: keep this one as evidence under eventId__hash (M3 §5).
        val conflictPath = FirestoreLayout.conflictingEvent(hid, item.eventId, item.hash)
        return when (val code = commitCode(listOf(SetWrite(conflictPath, item.data)))) {
            null -> ItemOutcome.Accepted

            FirestoreCode.PERMISSION_DENIED -> {
                val copy = readOrOutcome(conflictPath) { return it }
                if (copy != null && StoredShapes.eventHashOf(copy.data) == item.hash) {
                    ItemOutcome.Accepted
                } else {
                    ItemOutcome.Rejected(PERMISSION_DENIED)
                }
            }

            FirestoreCode.INVALID_ARGUMENT -> ItemOutcome.Rejected(permanentOf(code))

            else -> ItemOutcome.Rejected(syncErrorOf(code), Stop.PUSH)
        }
    }

    @Suppress("ReturnCount")
    private suspend fun resolveRefusedDocument(item: PendingWrite.Document): ItemOutcome {
        val stored = readOrOutcome(item.path) { return it } ?: return ItemOutcome.Rejected(PERMISSION_DENIED)
        val server = StoredShapes.documentOf(stored.data)
        val serverWins = server != null &&
            ConflictResolver.resolve(StoredDocument.of(server), item.envelope) == ConflictResolver.Decision.Ignore
        return if (serverWins) ItemOutcome.Accepted else ItemOutcome.Rejected(PERMISSION_DENIED)
    }

    /**
     * A refused blob chunk (audit A2-M3, D-M4-22): an identical stored copy (an earlier push landed) is accepted.
     * Otherwise it is [SyncError.Transient], never permanent, and stops the chat lane only: the chunk was authored and
     * validated on this device, so the rules refused it for the daily budget, for a counter day that is not the
     * server's (around UTC midnight or with a device clock that is off: the next attempt recomputes the day), or for a
     * revoked member (caught by the membership check before this). When today's counter (by this device's clock) is
     * used up, the retry waits for the next UTC midnight; otherwise the engine's backoff applies.
     */
    @Suppress("ReturnCount")
    private suspend fun resolveRefusedChunk(item: PendingWrite.BlobChunk): ItemOutcome {
        val stored = readChunkOr(item.path) { return it }
        if (stored != null && sameCreate(stored, item)) return ItemOutcome.Accepted
        val now = nowEpochMs()
        val quota = readChunkOr(FirestoreLayout.quota(item.hid, utcDay(now))) { return it }
        val used = quota?.data?.get(QUOTA_CHUNKS) as? Long ?: 0
        val untilMidnight = DAY_MS - Math.floorMod(now, DAY_MS)
        return chunkDeferred(retryAfterMs = untilMidnight.takeIf { used >= DAILY_BLOB_CHUNKS })
    }

    /** A deferred blob chunk: transient, and it ends the chat lane (its message must not go first). */
    private fun chunkDeferred(retryAfterMs: Long? = null) =
        ItemOutcome.Rejected(SyncError.Transient(retryAfterMs), Stop.LANE)

    /**
     * Reads [path] while resolving a refused chunk; a refused read defers the chunk ([chunkDeferred]), any other
     * failure ends the push. Both go through [onFailure] (which returns from the caller).
     */
    private suspend inline fun readChunkOr(path: String, onFailure: (ItemOutcome) -> Nothing): FacadeDocument? = try {
        call { get(path) }
    } catch (failure: FirestoreFailure) {
        onFailure(
            if (failure.code == FirestoreCode.PERMISSION_DENIED) {
                chunkDeferred()
            } else {
                ItemOutcome.Rejected(syncErrorOf(failure.code), Stop.PUSH)
            },
        )
    }

    /** A refused create-only write is accepted only when the stored copy is the same (an earlier push landed). */
    private suspend fun resolveRefusedCreate(item: PendingWrite): ItemOutcome {
        val stored = readOrOutcome(item.path) { return it }
        return if (stored != null && sameCreate(stored, item)) {
            ItemOutcome.Accepted
        } else {
            ItemOutcome.Rejected(PERMISSION_DENIED)
        }
    }

    /** Whether [stored] is [item]'s create (all but the server's timestamp equal). */
    private fun sameCreate(stored: FacadeDocument, item: PendingWrite): Boolean =
        stored.data - FirestoreFacade.SERVER_RECEIVED_AT == item.data - FirestoreFacade.SERVER_RECEIVED_AT

    /** Reads [path]; a failure becomes the item's outcome through [onFailure] (which returns from the caller). */
    private suspend inline fun readOrOutcome(path: String, onFailure: (ItemOutcome) -> Nothing): FacadeDocument? = try {
        call { get(path) }
    } catch (failure: FirestoreFailure) {
        onFailure(
            if (failure.code == FirestoreCode.PERMISSION_DENIED) {
                ItemOutcome.Rejected(PERMISSION_DENIED)
            } else {
                ItemOutcome.Rejected(syncErrorOf(failure.code), Stop.PUSH)
            },
        )
    }

    @Suppress("ReturnCount")
    private fun eventWrite(hid: HouseholdId, item: PushItem.Event): Either {
        val id = item.id
        val flat = StoredShapes.flattenEvent(item.json) ?: return Either.Rejected(id to SyncError.Permanent(MALFORMED))
        val householdId = (flat.fields["householdId"] as? JsonPrimitive)?.content
        if (householdId != hid.value) return Either.Rejected(id to SyncError.Permanent(WRONG_HOUSEHOLD))
        val data = PlainValues.toPlainMap(flat.fields) + (FirestoreFacade.SERVER_RECEIVED_AT to ServerTimestamp)
        return Either.Write(
            PendingWrite.Event(item, FirestoreLayout.event(hid, flat.eventId), data, flat.eventId, flat.hash),
        )
    }

    @Suppress("ReturnCount")
    private fun documentWrite(hid: HouseholdId, item: PushItem.Document): Either {
        val envelope = item.envelope
        val id = item.id
        val collection = FirestoreLayout.collectionOf(envelope.docType)
        val fields = when {
            collection == null -> return Either.Rejected(id to SyncError.Permanent(UNKNOWN_DOC_TYPE))
            envelope.householdId != hid -> return Either.Rejected(id to SyncError.Permanent(WRONG_HOUSEHOLD))
            envelope.signature == null -> return Either.Rejected(id to SyncError.Permanent(UNSIGNED))
            else -> StoredShapes.documentFields(envelope)
        }
        val data = fields + (FirestoreFacade.SERVER_RECEIVED_AT to ServerTimestamp)
        return Either.Write(
            PendingWrite.Document(item, FirestoreLayout.document(hid, collection, envelope.docId), data, envelope),
        )
    }

    @Suppress("ReturnCount")
    private fun messageWrite(hid: HouseholdId, item: PushItem.Message): Either {
        val message = item.json
        val id = item.id
        val stored = StoredShapes.messageFields(message) ?: return Either.Rejected(id to SyncError.Permanent(MALFORMED))
        when {
            message.string("householdId") != hid.value ->
                return Either.Rejected(id to SyncError.Permanent(WRONG_HOUSEHOLD))

            message.string("signature") == null -> return Either.Rejected(id to SyncError.Permanent(UNSIGNED))
        }
        val data = stored.fields + (FirestoreFacade.SERVER_RECEIVED_AT to ServerTimestamp)
        return Either.Write(PendingWrite.Message(item, FirestoreLayout.message(hid, stored.messageId), data))
    }

    @Suppress("ReturnCount")
    private fun blobChunkWrite(hid: HouseholdId, item: PushItem.BlobChunk): Either {
        val chunk = item.chunk
        val id = item.id
        val stored = StoredShapes.blobChunkFields(chunk)
            ?: return Either.Rejected(id to SyncError.Permanent(MALFORMED))
        when {
            chunk.header.string("householdId") != hid.value ->
                return Either.Rejected(id to SyncError.Permanent(WRONG_HOUSEHOLD))

            chunk.header.string("signature") == null -> return Either.Rejected(id to SyncError.Permanent(UNSIGNED))
        }
        val data = stored.fields + (FirestoreFacade.SERVER_RECEIVED_AT to ServerTimestamp)
        val path = FirestoreLayout.blobChunk(hid, stored.blobId, stored.chunk)
        return Either.Write(PendingWrite.BlobChunk(item, path, data, hid, "${stored.blobId}.${stored.chunk}"))
    }

    /** `yyyymmdd` of the UTC day of [epochMs], the id of the blob quota counter the rules check. */
    private fun utcDay(epochMs: Long = nowEpochMs()): String =
        java.time.Instant.ofEpochMilli(epochMs).atZone(java.time.ZoneOffset.UTC).toLocalDate()
            .format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)

    private sealed interface Either {
        class Write(val write: PendingWrite) : Either

        class Rejected(val rejection: Pair<PushItemId, SyncError>) : Either
    }

    // ---- pull ----

    // SyncException carries the code only, never the SDK failure (codes only, no free text).
    @Suppress("SwallowedException")
    override suspend fun pull(cursor: SyncCursor): PullPage {
        val hid = requireHousehold()
        return try {
            pullPage(hid, cursor)
        } catch (failure: FirestoreFailure) {
            throw SyncException(readErrorOf(hid, failure.code))
        }
    }

    private suspend fun pullPage(hid: HouseholdId, cursor: SyncCursor): PullPage {
        val next = cursor.perCollection.toMutableMap()
        val documents = mutableListOf<DocumentEnvelope>()

        val deviceDocs = call { getAll(FirestoreLayout.collection(hid, FirestoreLayout.DEVICES)) }
        val devicesDigest = StoredShapes.digestOf(deviceDocs)
        var malformed = 0
        val devices = if (devicesDigest == cursor.perCollection[FirestoreLayout.DEVICES]) {
            emptyList()
        } else {
            deviceDocs.mapNotNull { StoredShapes.deviceOf(it, hid) }.also { malformed += deviceDocs.size - it.size }
        }
        next[FirestoreLayout.DEVICES] = devicesDigest

        val keyDocs = call { getAll(FirestoreLayout.collection(hid, FirestoreLayout.PARENT_KEYS)) }
        val keysDigest = StoredShapes.digestOf(keyDocs)
        val parentKeys = if (keysDigest == cursor.perCollection[FirestoreLayout.PARENT_KEYS]) {
            emptyList()
        } else {
            keyDocs.mapNotNull(StoredShapes::parentKeyOf)
        }
        next[FirestoreLayout.PARENT_KEYS] = keysDigest

        var hasMore = false
        for ((docType, collection) in FirestoreLayout.documentCollections) {
            val page = page(hid, collection, cursor, next)
            hasMore = hasMore || page.size >= pageSize
            val decoded = page.mapNotNull { doc ->
                StoredShapes.documentOf(doc.data)?.takeIf { it.docType == docType && it.docId == doc.id }
            }
            malformed += page.size - decoded.size
            documents += decoded
        }
        val eventPage = page(hid, FirestoreLayout.EVENTS, cursor, next)
        hasMore = hasMore || eventPage.size >= pageSize
        val events = eventPage.mapNotNull { StoredShapes.nestEvent(it.data) }
        malformed += eventPage.size - events.size

        val messages = when (val scope = messageScope(hid, deviceDocs)) {
            MessageScope.Skip -> emptyList()

            is MessageScope.Read -> {
                val messagePage = page(hid, FirestoreLayout.MESSAGES, cursor, next, scope.where)
                hasMore = hasMore || messagePage.size >= pageSize
                messagePage.map { StoredShapes.messageOf(it.data) }
            }
        }

        return PullPage(devices, parentKeys, documents, events, SyncCursor(next), hasMore, messages, malformed)
    }

    @Suppress("LongParameterList")
    private suspend fun page(
        hid: HouseholdId,
        collection: String,
        cursor: SyncCursor,
        next: MutableMap<String, String>,
        where: FieldEquals? = null,
    ): List<FacadeDocument> {
        val after = cursor.perCollection[collection]?.let(CursorCodec::decode)
        val page = call { queryByServerTime(FirestoreLayout.collection(hid, collection), after, pageSize, where) }
        page.lastOrNull()?.let { last ->
            last.serverReceivedAt?.let { next[collection] = CursorCodec.encode(QueryPosition(it, last.id)) }
        }
        return page
    }

    /** How this session may read `messages`. */
    private sealed interface MessageScope {
        /** Read with [where] (`threadId == kidId` for a KID session, null for a parent). */
        data class Read(val where: FieldEquals?) : MessageScope

        /** A session whose kid is unknown and that is not known to be a parent: no messages lane (review Low). */
        data object Skip : MessageScope
    }

    /**
     * The `messages` read scope of this session: `threadId == kidId` for a KID session, everything for a parent. The
     * kid comes from [HouseholdScope.currentKidId]; otherwise a session known to be a parent
     * ([HouseholdScope.currentRole]) reads everything; otherwise this device's own registry entry ([deviceDocs] when
     * already read, otherwise one read of it) decides. With none of these the lane is skipped: an unscoped query would
     * be refused for a kid and end the whole pull.
     */
    @Suppress("ReturnCount")
    private suspend fun messageScope(hid: HouseholdId, deviceDocs: List<FacadeDocument>? = null): MessageScope {
        household.currentKidId()?.let { return MessageScope.Read(FieldEquals(THREAD_ID, it.value)) }
        if (household.currentRole() == Role.PARENT) return MessageScope.Read(null)
        val own = ownDevice(hid, deviceDocs) ?: return MessageScope.Skip
        return when (own.role) {
            Role.PARENT -> MessageScope.Read(null)
            Role.KID -> own.kidId?.let { MessageScope.Read(FieldEquals(THREAD_ID, it.value)) } ?: MessageScope.Skip
        }
    }

    @Suppress("ReturnCount")
    private suspend fun ownDevice(hid: HouseholdId, deviceDocs: List<FacadeDocument>?): RegisteredDevice? {
        val deviceId = household.currentDeviceId() ?: return null
        val own = deviceDocs?.firstOrNull { it.id == deviceId.value }
            ?: call { get(FirestoreLayout.document(hid, FirestoreLayout.DEVICES, deviceId.value)) }
            ?: return null
        return StoredShapes.deviceOf(own, hid)
    }

    // ---- blobs ----

    // SyncException carries the code only, never the SDK failure (codes only, no free text).
    @Suppress("SwallowedException", "ReturnCount")
    override suspend fun fetchBlob(blobId: BlobId): Result<List<BlobChunkJson>> {
        val hid = household.currentHouseholdId()
            ?: return Result.failure(SyncException(SyncError.Unauthenticated))
        return try {
            val first = call { get(FirestoreLayout.blobChunk(hid, blobId.value, 0)) }
                ?: return Result.success(emptyList())
            val expected = ((first.data["chunks"] as? Long) ?: 1L).coerceIn(1, MAX_BLOB_CHUNKS.toLong()).toInt()
            val stored = listOf(first) + (1 until expected).mapNotNull { index ->
                call { get(FirestoreLayout.blobChunk(hid, blobId.value, index)) }
            }
            // The uploader has not pushed every chunk yet: a transient outcome, so the caller retries (review M2).
            if (stored.size < expected) return Result.failure(BlobIncompleteException(stored.size, expected))
            Result.success(stored.mapNotNull { StoredShapes.blobChunkOf(it.data) })
        } catch (failure: FirestoreFailure) {
            Result.failure(SyncException(readErrorOf(hid, failure.code)))
        }
    }

    // ---- changes ----

    // SyncException carries the code only, never the SDK failure (codes only, no free text).
    @Suppress("SwallowedException")
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun changes(): Flow<RemoteChange> = foreground.distinctUntilChanged().flatMapLatest { inForeground ->
        if (!inForeground) {
            emptyFlow()
        } else {
            flow {
                val hid = household.currentHouseholdId() ?: return@flow
                val scope = try {
                    messageScope(hid)
                } catch (failure: FirestoreFailure) {
                    throw SyncException(syncErrorOf(failure.code))
                }
                emitAll(listeners(hid, scope))
            }
        }
    }

    private fun listeners(hid: HouseholdId, messageScope: MessageScope): Flow<RemoteChange> = watched
        .filter { (collection, _) -> collection != FirestoreLayout.MESSAGES || messageScope is MessageScope.Read }
        .map { (collection, latestOnly) ->
            val where = (messageScope as? MessageScope.Read)?.where.takeIf { collection == FirestoreLayout.MESSAGES }
            firestore.listen(FirestoreLayout.collection(hid, collection), latestOnly, where)
                .map { RemoteChange(collection) }
        }
        .merge()
        .catch { failure ->
            throw if (failure is FirestoreFailure) SyncException(syncErrorOf(failure.code)) else failure
        }

    // ---- shared ----

    private suspend fun requireHousehold(): HouseholdId =
        household.currentHouseholdId() ?: throw SyncException(SyncError.Unauthenticated)

    /** Runs one facade call within [callTimeoutMs]; a call that does not finish fails with [FirestoreCode.TIMEOUT]. */
    private suspend fun <T> call(block: suspend FirestoreFacade.() -> T): T {
        val result = withTimeoutOrNull(callTimeoutMs) { Box(firestore.block()) }
            ?: throw FirestoreFailure(FirestoreCode.TIMEOUT)
        return result.value
    }

    private class Box<T>(val value: T)

    private suspend fun commitCode(writes: List<SetWrite>): FirestoreCode? = try {
        call { commit(writes) }
        null
    } catch (failure: FirestoreFailure) {
        failure.code
    }

    /** What a refusal says about this member's place in household [HouseholdId] ([membership]). */
    private enum class Membership {
        /** Positive evidence that the household or this member is gone: wipe. */
        GONE,

        /** `households/{hid}` is readable: the member is fine and the refusal is about the item. */
        READABLE,

        /** Unreadable, but nothing proves the member is gone (a revoked session, a rules change, offline). */
        UNCONFIRMED,
    }

    /**
     * Decides what a `PERMISSION_DENIED` means, from positive evidence only:
     * 1. a forced ID-token refresh ([AuthProbe]) says the account was deleted or disabled ([AuthState.UserGone]), or
     *    the refreshed claims no longer name [hid] (the `hid` claim was removed);
     * 2. this device's own `devices/{deviceId}` entry ([HouseholdScope.currentDeviceId]) reads `REVOKED`;
     * then [Membership.GONE]. Otherwise [Membership.READABLE] when `households/{hid}` can be read, else
     * [Membership.UNCONFIRMED]. A check that cannot be made (offline, refresh failed) is not evidence.
     *
     * The refreshed account must be the session's ([HouseholdScope.currentUserId]): when another account is signed in
     * (or the session's account is unknown), nothing read with its credentials is evidence about this session, and
     * the answer is [Membership.UNCONFIRMED] (audit M-2).
     */
    private suspend fun membership(hid: HouseholdId): Membership {
        val auth = authProbe.refreshedState()
        if (auth is AuthState.SignedIn && auth.uid != household.currentUserId()) return Membership.UNCONFIRMED
        val gone = when (auth) {
            AuthState.UserGone -> true
            is AuthState.SignedIn -> auth.claims.hid != hid
            AuthState.SignedOut, null -> false
        }
        return when {
            gone || ownDeviceRevoked(hid) -> Membership.GONE
            householdReadable(hid) -> Membership.READABLE
            else -> Membership.UNCONFIRMED
        }
    }

    private suspend fun ownDeviceRevoked(hid: HouseholdId): Boolean {
        val deviceId = household.currentDeviceId() ?: return false
        return try {
            call { get(FirestoreLayout.document(hid, FirestoreLayout.DEVICES, deviceId.value)) }
                ?.data?.get("status") == REVOKED
        } catch (expected: FirestoreFailure) {
            false
        }
    }

    private suspend fun householdReadable(hid: HouseholdId): Boolean = try {
        call { get(FirestoreLayout.household(hid)) } != null
    } catch (expected: FirestoreFailure) {
        false
    }

    private suspend fun readErrorOf(hid: HouseholdId, code: FirestoreCode): SyncError = when (code) {
        FirestoreCode.PERMISSION_DENIED, FirestoreCode.NOT_FOUND -> when (membership(hid)) {
            Membership.GONE -> SyncError.HouseholdGone
            Membership.READABLE -> syncErrorOf(code)
            Membership.UNCONFIRMED -> SyncError.Transient()
        }

        else -> unauthenticatedOrGone(code)
    }

    /**
     * [syncErrorOf], except that `UNAUTHENTICATED` from an account that a forced refresh finds deleted or disabled
     * ([AuthState.UserGone]) is [SyncError.HouseholdGone]: a deleted household deletes its members' accounts, so
     * their tokens stop refreshing before any rule is evaluated.
     */
    private suspend fun unauthenticatedOrGone(code: FirestoreCode): SyncError =
        if (code == FirestoreCode.UNAUTHENTICATED && authProbe.refreshedState() == AuthState.UserGone) {
            SyncError.HouseholdGone
        } else {
            syncErrorOf(code)
        }

    companion object {
        /** Firestore's limit on writes per batch; the cost budget below keeps batches far smaller. */
        const val MAX_BATCH_WRITES: Int = 500

        /**
         * The rules evaluation budget of one request (1,000 expressions), spent per batch by an estimate per item
         * ([EVENT_COST], [DOCUMENT_COST], [MESSAGE_COST]). Firebase documents the limit per request; the emulator
         * applies it per written document (it commits 500 maximal events in one batch;
         * `firestore/test/batches.test.ts`),
         * so whether production charges a whole batch against one budget cannot be tested locally. The estimate assumes
         * it does: a batch it refuses would be retried item by item anyway, at the cost of one more commit per item.
         */
        const val BATCH_BUDGET: Int = 1_000
        private const val EVENT_COST = 100
        private const val DOCUMENT_COST = 125
        private const val MESSAGE_COST = 50
        private const val ALONE = Int.MAX_VALUE

        const val MAX_EVENTS_PER_BATCH: Int = BATCH_BUDGET / EVENT_COST
        const val MAX_DOCUMENTS_PER_BATCH: Int = BATCH_BUDGET / DOCUMENT_COST
        const val MAX_MESSAGES_PER_BATCH: Int = BATCH_BUDGET / MESSAGE_COST

        /** New blob chunks a household may store per UTC day (`quotas/{yyyymmdd}.chunks` in the rules, audit H2). */
        const val DAILY_BLOB_CHUNKS: Int = 60
        private const val QUOTA_CHUNKS = "chunks"
        private const val QUOTA_LAST = "last"
        private const val DAY_MS = 86_400_000L

        /** Documents per collection per pull page. */
        const val PAGE_SIZE: Int = 500

        /** How long one Firestore call may take before it counts as a transient failure. */
        const val CALL_TIMEOUT_MS: Long = 30_000

        const val MALFORMED: String = "malformed"
        const val WRONG_HOUSEHOLD: String = "wrong-household"
        const val UNKNOWN_DOC_TYPE: String = "unknown-doc-type"
        const val UNSIGNED: String = "unsigned"

        private const val REVOKED = "REVOKED"
        private const val THREAD_ID = "threadId"

        /** Most chunks of one blob (core/domain-ledger `ChatLimits.MAX_BLOB_CHUNKS`). */
        private const val MAX_BLOB_CHUNKS = 2

        private fun JsonObject.string(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        private val PERMISSION_DENIED = SyncError.Permanent("permission-denied")

        /** Batch failures that may be caused by a single item, so the batch is retried item by item. */
        private val ITEM_LEVEL_CODES = setOf(
            FirestoreCode.PERMISSION_DENIED,
            FirestoreCode.INVALID_ARGUMENT,
            FirestoreCode.ALREADY_EXISTS,
            FirestoreCode.FAILED_PRECONDITION,
        )

        /** Collections watched by [changes]; the registry is small and changes in place, so it is watched in full. */
        private val watched: List<Pair<String, Boolean>> =
            listOf(FirestoreLayout.DEVICES to false, FirestoreLayout.PARENT_KEYS to false) +
                FirestoreLayout.documentCollections.map { (_, collection) -> collection to true } +
                (FirestoreLayout.EVENTS to true) +
                (FirestoreLayout.MESSAGES to true)

        private const val RESOURCE_EXHAUSTED_RETRY_MS = 60_000L

        private fun permanentOf(code: FirestoreCode) = SyncError.Permanent(code.name.lowercase().replace('_', '-'))

        /** The [SyncError] of a Firestore status that is not about the household being gone. */
        internal fun syncErrorOf(code: FirestoreCode): SyncError = when (code) {
            FirestoreCode.UNAUTHENTICATED -> SyncError.Unauthenticated
            FirestoreCode.PERMISSION_DENIED, FirestoreCode.INVALID_ARGUMENT -> permanentOf(code)
            FirestoreCode.RESOURCE_EXHAUSTED -> SyncError.Transient(RESOURCE_EXHAUSTED_RETRY_MS)
            else -> SyncError.Transient()
        }
    }
}

/** Pull cursor of one collection: `<ISO-8601 serverReceivedAt>|<document id>`. */
internal object CursorCodec {
    private const val SEPARATOR = '|'

    fun encode(position: QueryPosition): String =
        "${position.serverReceivedAt.toIsoString()}$SEPARATOR${position.documentId}"

    /** The position in [cursor], or null (start over) when it is not one this transport wrote. */
    @Suppress("ReturnCount")
    fun decode(cursor: String): QueryPosition? {
        val split = cursor.indexOf(SEPARATOR)
        if (split <= 0 || split == cursor.length - 1) return null
        val time = ServerTime.parseOrNull(cursor.substring(0, split)) ?: return null
        return QueryPosition(time, cursor.substring(split + 1))
    }
}
