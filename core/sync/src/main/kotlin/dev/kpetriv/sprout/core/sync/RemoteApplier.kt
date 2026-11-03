package dev.kpetriv.sprout.core.sync

import dev.kpetriv.sprout.core.domainledger.RegisteredDevice
import dev.kpetriv.sprout.core.domainledger.RegisteredParentKey
import kotlinx.serialization.json.JsonObject

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
     * missing predecessor or an author device not yet known or approved.
     */
    suspend fun applyEvents(events: List<JsonObject>): EventApplyReport

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
