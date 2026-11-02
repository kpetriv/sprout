package dev.kpetriv.sprout.core.domainledger

import dev.kpetriv.sprout.core.model.DeviceId
import kotlinx.serialization.json.JsonObject

/**
 * The per-author hash chain (ADR-0002). For each author device:
 *
 * `hash = hex(SHA-256(canonicalBytes || UTF-8(prevHash ?: GENESIS)))`
 *
 * where `canonicalBytes` is [CanonicalEncoder.encode] (which already contains `prevHash`) and `prevHash` is the
 * previous event's [Envelope.hash] from the same device. Any edit, gap, reorder or replay within one author's
 * history breaks the chain.
 */
class HashChain(private val hasher: Hasher = JvmSha256Hasher) {

    /** The chain hash for an event whose canonical encoding is [canonicalBytes]. */
    fun computeHash(prevHash: String?, canonicalBytes: ByteArray): String =
        hasher.sha256(canonicalBytes + (prevHash ?: GENESIS).encodeToByteArray()).toHex()

    /**
     * The hash [event] should carry, recomputed from its content.
     *
     * @throws CharacterCodingException if a string field holds an unpaired surrogate.
     */
    fun hashOf(event: LedgerEvent): String = computeHash(event.envelope.prevHash, CanonicalEncoder.encode(event))

    /**
     * The hash a stored or pulled event document should carry, computed from its own fields (including any this
     * client does not understand). Use this, not [hashOf] on a decoded event, to verify what was stored or pulled.
     *
     * @param prevHash the document's `envelope.prevHash`.
     * @throws IllegalArgumentException if [json] is not canonicalisable (see [CanonicalEncoder.encode]).
     * @throws CharacterCodingException if a string holds an unpaired surrogate.
     */
    fun hashOf(json: JsonObject, prevHash: String?): String = computeHash(prevHash, CanonicalEncoder.encode(json))

    /**
     * Verifies one author's stream, given sorted by lamport. Never throws. Reports the first break only; checks per
     * event, in order: same author, duplicate eventId, lamport strictly above the previous event's (or above
     * [anchorLamport] for the first), `prevHash` links to the previous event (or to [anchorHash] for the first),
     * and the stored hash matches the recomputed one.
     *
     * @param anchorHash the hash the first event must link to; null means the stream starts at genesis (the
     *   first event's `prevHash` must be null). Pass the last verified head to check a continuation.
     * @param anchorLamport the lamport of the event [anchorHash] names; every event must be strictly above it.
     *   Null when there is no anchor.
     */
    fun verifyChain(
        events: List<LedgerEvent>,
        anchorHash: String? = null,
        anchorLamport: Long? = null,
    ): ChainVerification {
        val author = events.firstOrNull()?.envelope?.authorDeviceId
        val seen = HashSet<EventId>()
        var expectedPrev = anchorHash
        var previousLamport: Long? = anchorLamport
        events.forEachIndexed { index, event ->
            val envelope = event.envelope
            val failure = when {
                envelope.authorDeviceId != author ->
                    ChainBreak.ForeignAuthor(requireNotNull(author), envelope.authorDeviceId)

                !seen.add(envelope.eventId) -> ChainBreak.DuplicateEventId(envelope.eventId)

                previousLamport != null && envelope.lamport <= previousLamport ->
                    ChainBreak.NonMonotonicLamport(requireNotNull(previousLamport), envelope.lamport)

                envelope.prevHash != expectedPrev -> ChainBreak.MissingPrev(expectedPrev, envelope.prevHash)

                else -> hashBreak(event)
            }
            if (failure != null) return ChainVerification.Broken(index, envelope.eventId, failure)
            expectedPrev = envelope.hash
            previousLamport = envelope.lamport
        }
        return ChainVerification.Valid(headHash = expectedPrev, length = events.size)
    }

    private fun hashBreak(event: LedgerEvent): ChainBreak? {
        val computed = try {
            hashOf(event)
        } catch (expected: CharacterCodingException) {
            return ChainBreak.Unencodable
        }
        return if (computed != event.envelope.hash) ChainBreak.HashMismatch(computed, event.envelope.hash) else null
    }

    companion object {
        /** The value hashed in place of `prevHash` for an author's first event: 64 zeros. */
        val GENESIS: String = "0".repeat(64)
    }
}

/** Outcome of [HashChain.verifyChain]. */
sealed interface ChainVerification {
    /** The whole stream verified. [headHash] is the last event's hash (the anchor if the stream was empty). */
    data class Valid(val headHash: String?, val length: Int) : ChainVerification

    /** The first event that failed, at [index] in the input. Everything from here on is untrusted. */
    data class Broken(val index: Int, val eventId: EventId, val reason: ChainBreak) : ChainVerification
}

/** Why a chain is broken. */
sealed interface ChainBreak {
    /** `prevHash` does not link to the previous event (a gap, reorder, or a forged first event). */
    data class MissingPrev(val expected: String?, val actual: String?) : ChainBreak

    /** The stored hash is not the hash of the content: the event was edited after hashing. */
    data class HashMismatch(val computed: String, val stored: String) : ChainBreak

    /** The same eventId appears twice in one author stream (a replay). */
    data class DuplicateEventId(val eventId: EventId) : ChainBreak

    /** Lamport did not strictly increase (from the previous event, or from the anchor for the first). */
    data class NonMonotonicLamport(val previous: Long, val actual: Long) : ChainBreak

    /** An event from a different author device was mixed into the stream. */
    data class ForeignAuthor(val expected: DeviceId, val actual: DeviceId) : ChainBreak

    /** The event has no canonical encoding (a string with an unpaired surrogate), so it cannot have a valid hash. */
    data object Unencodable : ChainBreak
}
