package dev.kpetriv.sprout.core.sync

import dev.kpetriv.sprout.core.domainledger.RegisteredDevice
import dev.kpetriv.sprout.core.domainledger.RegisteredParentKey
import dev.kpetriv.sprout.core.model.BlobId
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.HouseholdId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Instant

/**
 * Moves outbox changes to the backend and remote changes back (ADR-0001, M3 §4). The sync engine only talks to this
 * interface; the Firebase implementation lives in core/sync-firebase and tests use `FakeSyncTransport`.
 *
 * Failures are reported by throwing [SyncException] (never another exception type, never free text): a whole call
 * that fails throws, and per-item outcomes of a push come back in [PushResult].
 *
 * [push], [pull], [fetchBlob], [registeredDevice] and [changes] have fail-closed defaults (nothing sent, nothing
 * received) only so a partial transport keeps compiling; every real transport overrides them.
 */
interface SyncTransport {
    /**
     * Sends [items] (ledger events as `LedgerJson` trees, signed documents, chat messages as `MessageJson` trees of
     * core/domain-ledger `MessageEnvelope`, blob chunks as [BlobChunkJson]) **in list order** within each outbox lane
     * ([OutboxItem.Lane], D-M4-22), which is outbox `seq` order: a transport never reorders the items of one lane, so
     * a blob chunk queued before the message that names its blob reaches the server first (M4 §3, review M2).
     * Idempotent: an event already on the server under the same `(eventId, hash)`, a document the server's copy
     * already beats, or a message or blob chunk already stored with the same content, is accepted.
     *
     * **Head of line, per lane.** Once an item whose kind [blocks on refusal][OutboxItem.Kind.blocksOnRefusal] (an
     * event, a message, a blob chunk) is refused with [SyncError.Permanent], or an item is refused with
     * [SyncError.Transient] (a blob chunk the server refused: over the daily budget, or counted against another day),
     * every later item **of its lane** is left unprocessed: it appears in neither list of the [PushResult], and the
     * engine leaves it queued behind the refused one ([SyncEngine]). Items of the other lane go on. A refused document
     * does not stop anything. A failure that is not about one item (the network, the session) ends the call and leaves
     * the rest of both lanes unprocessed.
     */
    suspend fun push(items: List<PushItem>): PushResult = throw SyncException(SyncError.Transient())

    /**
     * [push] of the given kinds as one ordered list: events, then documents, then blob chunks, then messages. For
     * callers that hold no outbox order (tests, one-off pushes); the engine always uses the ordered [push].
     */
    suspend fun push(
        events: List<JsonObject>,
        documents: List<DocumentEnvelope>,
        messages: List<JsonObject> = emptyList(),
        blobChunks: List<BlobChunkJson> = emptyList(),
    ): PushResult = push(
        events.map(PushItem::Event) + documents.map(PushItem::Document) + blobChunks.map(PushItem::BlobChunk) +
            messages.map(PushItem::Message),
    )

    /**
     * One page of the registry, documents, events and messages written after [cursor]. Messages are read-scoped: a KID
     * device receives only its own kid's thread. Blob chunks are never pulled: see [fetchBlob]. A page with messages
     * also carries the household's chat prune watermark ([PullPage.chatPrunedBefore]) when one exists.
     */
    suspend fun pull(cursor: SyncCursor): PullPage = throw SyncException(SyncError.Transient())

    /**
     * Every stored chunk of blob [blobId], in chunk order, read on demand (M4 §2). An empty list means nothing is
     * stored under that id (not uploaded yet). When chunk 0 is stored but fewer than its `chunks` count are (the
     * uploader is still pushing the rest), the result is a failure with [BlobIncompleteException], a transient
     * [SyncException]: callers retry later rather than treating the blob as broken. Callers still check the
     * whole-blob `sha256` before decrypting. Any other failure is a [SyncException] inside the [Result].
     */
    suspend fun fetchBlob(blobId: BlobId): Result<List<BlobChunkJson>> =
        Result.failure(SyncException(SyncError.Transient()))

    /**
     * The registry row of [deviceId] in [householdId] as the server holds it, read with the signed-in account's
     * credentials (a member of [householdId]) whether or not a session is stored yet; null when there is no such row,
     * or it does not parse. Onboarding reads it before it restores a device (D-REL-22): a device the registry knows is
     * restored only for the account and role that registered it. Any failure is a [SyncException] (never "no row").
     */
    suspend fun registeredDevice(householdId: HouseholdId, deviceId: DeviceId): RegisteredDevice? =
        throw SyncException(SyncError.Transient())

    /** Emits while connected whenever another device writes something this device should pull (messages included). */
    fun changes(): Flow<RemoteChange> = emptyFlow()

    /** Whether the transport can currently reach its backend. */
    suspend fun isAvailable(): Boolean
}

/**
 * Identity of one pushed item, derived from its content so both sides of [SyncTransport.push] compute it the same
 * way: `event/<eventId>:<hash>` (the outbox key of a ledger event), `document/<docType>/<docId>`,
 * `message/<messageId>` or `blob/<blobId>.<chunk>`.
 */
@JvmInline
value class PushItemId(val value: String) {
    override fun toString(): String = value

    companion object {
        fun ofEvent(event: JsonObject): PushItemId {
            val envelope = event["envelope"] as? JsonObject
            fun field(name: String) = (envelope?.get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
            return PushItemId("event/${field("eventId")}:${field("hash")}")
        }

        fun ofDocument(document: DocumentEnvelope): PushItemId =
            PushItemId("document/${document.docType}/${document.docId}")

        /** `message/<messageId>` of a `MessageJson` tree (empty id when it has none). */
        fun ofMessage(message: JsonObject): PushItemId {
            val messageId = (message["messageId"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
            return PushItemId("message/$messageId")
        }

        /** `blob/<blobId>.<chunk>`: the chunk's document id. */
        fun ofBlobChunk(chunk: BlobChunkJson): PushItemId = PushItemId("blob/${chunk.docId}")
    }
}

/**
 * One outbox item handed to [SyncTransport.push], in outbox order. [id] is its [PushItemId] and [kind] its outbox
 * kind ([OutboxItem.Kind.blocksOnRefusal] says whether a permanent refusal stops the push).
 */
sealed interface PushItem {
    val id: PushItemId
    val kind: OutboxItem.Kind

    /** A ledger event's `LedgerJson` tree. */
    data class Event(val json: JsonObject) : PushItem {
        override val id: PushItemId get() = PushItemId.ofEvent(json)
        override val kind: OutboxItem.Kind get() = OutboxItem.Kind.EVENT
    }

    /** A signed document. */
    data class Document(val envelope: DocumentEnvelope) : PushItem {
        override val id: PushItemId get() = PushItemId.ofDocument(envelope)
        override val kind: OutboxItem.Kind get() = OutboxItem.Kind.DOCUMENT
    }

    /** A chat message's `MessageJson` tree. */
    data class Message(val json: JsonObject) : PushItem {
        override val id: PushItemId get() = PushItemId.ofMessage(json)
        override val kind: OutboxItem.Kind get() = OutboxItem.Kind.MESSAGE
    }

    /** One blob chunk, header and bytes. */
    data class BlobChunk(val chunk: BlobChunkJson) : PushItem {
        override val id: PushItemId get() = PushItemId.ofBlobChunk(chunk)
        override val kind: OutboxItem.Kind get() = OutboxItem.Kind.BLOB_CHUNK
    }
}

/**
 * Per-item outcome of a push. An item in neither list was not processed: the engine retries it like a transient
 * error, or, behind an item refused for good whose kind blocks the outbox, leaves it untouched ([SyncEngine]).
 */
data class PushResult(val accepted: List<PushItemId>, val rejected: List<Pair<PushItemId, SyncError>>)

/**
 * One pulled ledger event: its `LedgerJson` tree as stored (without the server stamp, which no hash or signature
 * covers) and the backend's [serverReceivedAt] stamp. The stamp is set by the server (the rules require
 * `request.time`), so every device sees the same one for the same stored document; receivers judge a game's
 * `playedOn` against its UTC date (D-M5-5, `Ledger.project`'s `receivedAt`). Null means "never date-checked", so
 * a real transport never passes one without a stamp: the Firebase transport drops such a document as malformed
 * ([PullPage.malformed]). Null is left for test transports and the data layer's own tests.
 */
data class PulledEvent(val json: JsonObject, val serverReceivedAt: Instant? = null)

/**
 * One page of a pull: registry entries, documents, events and chat messages (`MessageJson` trees, cursor key
 * `messages`) added or changed after the requested cursor. Pull again from [next] while [hasMore].
 */
class PullPage(
    val devices: List<RegisteredDevice>,
    val parentKeys: List<RegisteredParentKey>,
    val documents: List<DocumentEnvelope>,
    val events: List<PulledEvent>,
    val next: SyncCursor,
    val hasMore: Boolean,
    val messages: List<JsonObject> = emptyList(),
    /**
     * Stored rows of this page the transport could not read into the lists above and dropped: a registry row that
     * does not parse or whose id is not its key's fingerprint, a document or an event that does not decode (or a
     * document stored under another type's collection or another id), an event without a readable server stamp.
     * Counted for the sync report only.
     */
    val malformed: Int = 0,
    /**
     * The household's chat prune watermark (D-M7-53): every message the server stored before this instant was deleted
     * by a retention prune or a purge (`households/{hid}.chatPrune.prunedBefore`, written by the server only). Null
     * when no prune ever ran, or when the transport did not read it for this page (it reads it with messages only).
     * [RemoteApplier.applyChatPrune] receives it before the page's messages.
     */
    val chatPrunedBefore: Instant? = null,
    /**
     * The backend's `serverReceivedAt` stamp of each of [messages], by `messageId` (audit 2 N3, D-M7-80): kept with
     * the message so a `seq` gap is judged against the prune watermark ([chatPrunedBefore]) on the server's clock,
     * never this device's. A message missing here is stored without a stamp (test transports, older data).
     */
    val messageStamps: Map<String, Instant> = emptyMap(),
)

/**
 * Where a pull resumes: an opaque per-collection position chosen by the transport (a Firestore snapshot token, a
 * server sequence number). Persisted by the [RemoteApplier] after each applied page.
 */
@Serializable
data class SyncCursor(val perCollection: Map<String, String> = emptyMap()) {
    companion object {
        val START: SyncCursor = SyncCursor()
    }
}

/** A hint that [collection] changed remotely; the engine reacts by pulling. */
data class RemoteChange(val collection: String)

/** Why a transport call or a pushed item failed. Codes only: never free text, so it can be stored and audited. */
sealed interface SyncError {
    /** Network, quota or server trouble; retry later, no sooner than [retryAfterMs] if the server said so. */
    data class Transient(val retryAfterMs: Long? = null) : SyncError

    /** The server refused the item for good (rules, validation); retrying cannot help. [code] is a fixed code. */
    data class Permanent(val code: String) : SyncError

    /** The session is gone or the token was refused; sync waits for sign-in. */
    data object Unauthenticated : SyncError

    /** The household was deleted or this member removed; the local data must be wiped (M3 §6). */
    data object HouseholdGone : SyncError
}

/** The fixed kebab-case code of an error, as stored in the outbox's `lastError` and the audit log. */
val SyncError.code: String
    get() = when (this) {
        is SyncError.Transient -> "transient"
        is SyncError.Permanent -> code
        SyncError.Unauthenticated -> "unauthenticated"
        SyncError.HouseholdGone -> "household-gone"
    }

/** How a [SyncTransport] reports a failed call. The message is the error's code, never user data. */
open class SyncException(val error: SyncError) : Exception(error.code)

/**
 * [SyncTransport.fetchBlob] found chunk 0 of a blob but only [stored] of its [expected] chunks: the uploader has not
 * pushed the rest yet. A [SyncError.Transient] failure, so callers retry later instead of reporting the blob invalid.
 */
class BlobIncompleteException(val stored: Int, val expected: Int) : SyncException(SyncError.Transient())
