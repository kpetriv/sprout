package dev.kpetriv.sprout.core.sync

import dev.kpetriv.sprout.core.domainledger.RegisteredDevice
import dev.kpetriv.sprout.core.domainledger.RegisteredParentKey
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.ThreadId
import kotlinx.serialization.json.JsonObject
import kotlin.time.Instant

/**
 * Applies what a pull brought into the local store (core/database implements it with Room, the device registry
 * mirror, [RemoteDocumentVerifier], [ConflictResolver] and the ledger ingest).
 */
interface RemoteApplier {
    /** Replaces the local registry mirror's entries for these devices and parent keys. */
    suspend fun applyRegistry(devices: List<RegisteredDevice>, parentKeys: List<RegisteredParentKey>)

    /** Verifies each document and applies the ones [ConflictResolver] lets through. */
    suspend fun applyDocuments(documents: List<DocumentEnvelope>): DocumentApplyReport

    /**
     * Verifies and ingests ledger events: applied, duplicates, quarantined (including forked chains), or held for a
     * missing predecessor or an author device not yet known or approved. Each event's
     * [PulledEvent.serverReceivedAt] is kept with it (held ones included), for the played-on window (D-M5-5).
     */
    suspend fun applyEvents(events: List<PulledEvent>): EventApplyReport

    /**
     * Verifies and stores chat messages (`MessageJson` trees; core/domain-ledger `MessageVerifier`): applied (stored,
     * decrypted when the epoch key is known, otherwise kept locked), duplicates, quarantined, or held for a `seq` gap
     * or an author device not yet known. Called after [applyDocuments] of the same page, so `thread_key` documents
     * ([DocTypes.THREAD_KEY]) pulled with them are already applied. [stamps] are the backend's `serverReceivedAt`
     * stamps by `messageId` ([PullPage.messageStamps], D-M7-80), kept for the prune watermark's gap check; empty from
     * transports without them.
     */
    suspend fun applyMessages(
        messages: List<JsonObject>,
        stamps: Map<String, Instant> = emptyMap(),
    ): MessageApplyReport

    /**
     * Remembers the household's chat prune watermark ([PullPage.chatPrunedBefore], D-M7-53), so a `seq` gap left by
     * messages a prune deleted before this device pulled them is skipped at once instead of after the held-gap
     * timeout. Called before [applyMessages] of the same page; never moves an earlier-known watermark back. The
     * default ignores it (gaps then wait for the timeout, as before M7).
     */
    suspend fun applyChatPrune(prunedBefore: Instant) {
        // Optional: an applier that does not hold messages has nothing to skip.
    }

    /**
     * The `seq` the next message of [deviceId] in [threadId] must carry to apply now (1 when none was applied): the
     * `expectedSeq` of `MessageVerifier`.
     */
    suspend fun expectedSeq(deviceId: DeviceId, threadId: ThreadId): Long

    /**
     * Retries held events (a gap that may now be filled, an author device the registry now knows) and, where the
     * implementation keeps them, held documents.
     */
    suspend fun retryHeld(): EventApplyReport

    suspend fun cursor(): SyncCursor

    suspend fun saveCursor(cursor: SyncCursor)

    /** The highest lamport this device has seen; the base of the remote lamport-jump bound. */
    suspend fun localMaxLamport(): Long
}

/**
 * @property held documents [RemoteDocumentVerifier] could not decide yet ([VerifiedDocument.Held]: unknown or
 *   pending author device), kept for a later retry.
 */
data class DocumentApplyReport(
    val applied: Int = 0,
    val ignored: Int = 0,
    val rejected: Map<DocumentRejection, Int> = emptyMap(),
    val held: Int = 0,
) {
    val rejectedTotal: Int get() = rejected.values.sum()

    operator fun plus(other: DocumentApplyReport): DocumentApplyReport = DocumentApplyReport(
        applied = applied + other.applied,
        ignored = ignored + other.ignored,
        rejected = (rejected.keys + other.rejected.keys).associateWith {
            rejected.getOrDefault(it, 0) + other.rejected.getOrDefault(it, 0)
        },
        held = held + other.held,
    )
}

data class EventApplyReport(
    val applied: Int = 0,
    val duplicates: Int = 0,
    val quarantined: Int = 0,
    val held: Int = 0,
) {
    operator fun plus(other: EventApplyReport): EventApplyReport = EventApplyReport(
        applied = applied + other.applied,
        duplicates = duplicates + other.duplicates,
        quarantined = quarantined + other.quarantined,
        held = held + other.held,
    )
}

/** What [RemoteApplier.applyMessages] did with a batch of pulled messages. */
data class MessageApplyReport(
    val applied: Int = 0,
    val duplicates: Int = 0,
    val quarantined: Int = 0,
    val held: Int = 0,
) {
    operator fun plus(other: MessageApplyReport): MessageApplyReport = MessageApplyReport(
        applied = applied + other.applied,
        duplicates = duplicates + other.duplicates,
        quarantined = quarantined + other.quarantined,
        held = held + other.held,
    )
}
