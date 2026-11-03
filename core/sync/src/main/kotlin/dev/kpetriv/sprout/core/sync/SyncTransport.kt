package dev.kpetriv.sprout.core.sync

import dev.kpetriv.sprout.core.domainledger.RegisteredDevice
import dev.kpetriv.sprout.core.domainledger.RegisteredParentKey
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
 * [push], [pull] and [changes] have fail-closed defaults (nothing sent, nothing received) only so the
 * core/sync-firebase stub keeps compiling until it implements them in M3 §5; every real transport overrides them.
 */
interface SyncTransport {
    /**
     * Sends ledger events (their `LedgerJson` trees) and signed documents. Idempotent: an event already on the
     * server under the same `(eventId, hash)`, or a document the server's copy already beats, is accepted.
     */
    suspend fun push(events: List<JsonObject>, documents: List<DocumentEnvelope>): PushResult =
        throw SyncException(SyncError.Transient())

    /** One page of the registry, documents and events written after [cursor]. */
    suspend fun pull(cursor: SyncCursor): PullPage = throw SyncException(SyncError.Transient())

    /** Emits while connected whenever another device writes something this device should pull. */
    fun changes(): Flow<RemoteChange> = emptyFlow()

    /** Whether the transport can currently reach its backend. */
    suspend fun isAvailable(): Boolean
}

/**
 * Identity of one pushed item, derived from its content so both sides of [SyncTransport.push] compute it the same
 * way: `event/<eventId>:<hash>` (the outbox key of a ledger event) or `document/<docType>/<docId>`.
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
    }
}

/** Per-item outcome of a push. An item in neither list was not processed and is retried like a transient error. */
data class PushResult(val accepted: List<PushItemId>, val rejected: List<Pair<PushItemId, SyncError>>)

/**
 * One page of a pull: registry entries, documents and events added or changed after the requested cursor. Pull
 * again from [next] while [hasMore].
 */
class PullPage(
    val devices: List<RegisteredDevice>,
    val parentKeys: List<RegisteredParentKey>,
    val documents: List<DocumentEnvelope>,
    val events: List<JsonObject>,
    val next: SyncCursor,
    val hasMore: Boolean,
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

/** The fixed code of an error, as stored in the outbox's `lastError` and the audit log. */
val SyncError.code: String
    get() = when (this) {
        is SyncError.Transient -> "transient"
        is SyncError.Permanent -> code
        SyncError.Unauthenticated -> "unauthenticated"
        SyncError.HouseholdGone -> "household_gone"
    }

/** How a [SyncTransport] reports a failed call. The message is the error's code, never user data. */
class SyncException(val error: SyncError) : Exception(error.code)
