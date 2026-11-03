package dev.kpetriv.sprout.core.sync

import dev.kpetriv.sprout.core.domainledger.RegisteredDevice
import dev.kpetriv.sprout.core.domainledger.RegisteredParentKey
import dev.kpetriv.sprout.core.model.BlobId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Moves outbox changes to the backend and remote changes back (ADR-0001, M3 §4). The sync engine only talks to this
 * interface; the Firebase implementation lives in core/sync-firebase and tests use `FakeSyncTransport`.
 *
 * Failures are reported by throwing [SyncException] (never another exception type, never free text): a whole call
 * that fails throws, and per-item outcomes of a push come back in [PushResult].
 *
 * [push], [pull], [fetchBlob] and [changes] have fail-closed defaults (nothing sent, nothing received) only so a
 * partial transport keeps compiling; every real transport overrides them.
 */
interface SyncTransport {
    /**
     * Sends ledger events (their `LedgerJson` trees), signed documents, chat messages (their `MessageJson` trees,
     * core/domain-ledger `MessageEnvelope`) and blob chunks ([BlobChunkJson]). Idempotent: an event already on the
     * server under the same `(eventId, hash)`, a document the server's copy already beats, or a message or blob chunk
     * already stored with the same content, is accepted.
     *
     * Blob chunks are written before messages, so a message never reaches the server ahead of a blob it refers to
     * that travels in the same call (the outbox queues a blob before its message; the engine keeps that order).
     */
    suspend fun push(
        events: List<JsonObject>,
        documents: List<DocumentEnvelope>,
        messages: List<JsonObject> = emptyList(),
        blobChunks: List<BlobChunkJson> = emptyList(),
    ): PushResult = throw SyncException(SyncError.Transient())

    /**
     * One page of the registry, documents, events and messages written after [cursor]. Messages are read-scoped: a KID
     * device receives only its own kid's thread. Blob chunks are never pulled: see [fetchBlob].
     */
    suspend fun pull(cursor: SyncCursor): PullPage = throw SyncException(SyncError.Transient())

    /**
     * Every stored chunk of blob [blobId], in chunk order, read on demand (M4 §2). An empty list means nothing is
     * stored under that id (not uploaded yet); a missing later chunk is left out, so callers check `chunks` and the
     * whole-blob `sha256` before decrypting. A failure is a [SyncException] inside the [Result].
     */
    suspend fun fetchBlob(blobId: BlobId): Result<List<BlobChunkJson>> =
        Result.failure(SyncException(SyncError.Transient()))

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

/** Per-item outcome of a push. An item in neither list was not processed and is retried like a transient error. */
data class PushResult(val accepted: List<PushItemId>, val rejected: List<Pair<PushItemId, SyncError>>)

/**
 * One page of a pull: registry entries, documents, events and chat messages (`MessageJson` trees, cursor key
 * `messages`) added or changed after the requested cursor. Pull again from [next] while [hasMore].
 */
class PullPage(
    val devices: List<RegisteredDevice>,
    val parentKeys: List<RegisteredParentKey>,
    val documents: List<DocumentEnvelope>,
    val events: List<JsonObject>,
    val next: SyncCursor,
    val hasMore: Boolean,
    val messages: List<JsonObject> = emptyList(),
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
class SyncException(val error: SyncError) : Exception(error.code)
