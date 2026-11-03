package dev.kpetriv.sprout.core.domainledger

import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.Role
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Decides whether a pulled event document may enter this device's ledger (M3 §2, ADR-0004 §1, §2, §4). Works on the
 * document's own JSON tree, so fields a newer schema added are still covered by the hash and both signatures.
 *
 * Checks run in this order and the first failure wins ([QuarantineReason]):
 * 1. the tree decodes ([LedgerJson.decodeOrNull]) and its `schemaVersion` is one this client reads (1 or
 *    [Envelope.CURRENT_SCHEMA_VERSION]);
 * 2. the stored `hash` equals the hash recomputed over the stored tree ([HashChain.hashOf]);
 * 3. the author device is registered ([DeviceRegistry.device]);
 * 4. the device accepts the event's lamport ([RegisteredDevice.acceptsLamport]: pending devices never do, revoked
 *    devices only below their revocation lamport);
 * 5. `authorUid` is the device owner's uid;
 * 6. the role is allowed: a PARENT-role envelope needs a PARENT device; a KID-role envelope is accepted from any
 *    device that passed 4 (kid mode on a parent device, D-M3-12);
 * 7. the device signature verifies over [signedPayloadOf] with the registered device key;
 * 8. a parent-only event ([isParentOnly]) carries a parent signature that verifies over [parentSignedPayloadOf]
 *    against at least one registered parent key that [covers][RegisteredParentKey.coversLamport] its lamport.
 *    There is **no from-lamport exemption** and [LedgerPolicy.requireParentSignature] is not consulted: pulled
 *    parent-only events always need it (ADR-0004, M2-review amendment);
 * 9. the lamport is at most `localMaxLamport + policy.maxLamportJump` (ADR-0004 §4).
 *
 * The chain link (`prevHash` against the stored head) is not checked here; [IngestOrdering] does that per batch.
 *
 * @param policy only [LedgerPolicy.maxLamportJump] is used.
 */
class RemoteEventVerifier(
    private val registry: DeviceRegistry,
    private val check: SignatureCheck,
    private val policy: LedgerPolicy,
    private val hashChain: HashChain = HashChain(),
) {
    /**
     * Verifies one pulled event document. Never throws for any [json]: malformed input, unencodable strings and a
     * [SignatureCheck] that throws all become [VerifiedRemote.Quarantined]. Exceptions thrown by [registry] itself
     * (storage failures, cancellation) do propagate, because they say nothing about the event and must not
     * quarantine it permanently.
     *
     * @param localMaxLamport the highest lamport this device has seen ([LedgerRepository.latestLamport]).
     */
    suspend fun verify(json: JsonObject, localMaxLamport: Long): VerifiedRemote {
        val event = LedgerJson.decodeOrNull(json) ?: return quarantine(json, undecodableReason(json))
        val reason = firstFailure(event, json, localMaxLamport)
        return if (reason == null) {
            VerifiedRemote.Ok(event, json)
        } else {
            with(event.envelope) { VerifiedRemote.Quarantined(eventId, hash, authorDeviceId, reason) }
        }
    }

    /** Checks 1 (schema version) to 9 for a decoded [event]; null when all pass. */
    private suspend fun firstFailure(event: LedgerEvent, json: JsonObject, localMaxLamport: Long): QuarantineReason? {
        val envelope = event.envelope
        return when {
            envelope.schemaVersion !in SUPPORTED_SCHEMA_VERSIONS -> QuarantineReason.SCHEMA_UNSUPPORTED

            !hashMatches(json, envelope) -> QuarantineReason.HASH_MISMATCH

            else -> when (val device = registry.device(envelope.authorDeviceId)) {
                null -> QuarantineReason.UNKNOWN_DEVICE

                else -> deviceFailure(device, event, json)
                    ?: parentSignatureFailure(event, json)
                    ?: QuarantineReason.LAMPORT_TOO_FAR_AHEAD.takeUnless {
                        withinLamportBound(envelope.lamport, localMaxLamport)
                    }
            }
        }
    }

    /** Checks 4 to 7: what the registered [device] allows and whether it signed [json]. */
    private fun deviceFailure(device: RegisteredDevice, event: LedgerEvent, json: JsonObject): QuarantineReason? {
        val envelope = event.envelope
        return when {
            !device.acceptsLamport(envelope.lamport) -> QuarantineReason.DEVICE_NOT_ACTIVE

            envelope.authorUid != device.uid -> QuarantineReason.UID_MISMATCH

            envelope.authorRole == Role.PARENT && device.role != Role.PARENT -> QuarantineReason.ROLE_NOT_ALLOWED

            !verifies(device.publicKey, json, event.signatureBytes(), ::signedPayloadOf) ->
                QuarantineReason.BAD_DEVICE_SIGNATURE

            else -> null
        }
    }

    /** Check 8: a parent-only event needs a parent signature by a registered key valid at its lamport. */
    private suspend fun parentSignatureFailure(event: LedgerEvent, json: JsonObject): QuarantineReason? {
        val signature = event.parentSignatureBytes()?.takeIf { it.isNotEmpty() }
        return when {
            !event.isParentOnly() -> null

            signature == null -> QuarantineReason.PARENT_SIGNATURE_MISSING

            registry.parentKeys().any { key ->
                key.coversLamport(event.lamport) && verifies(key.publicKey, json, signature, ::parentSignedPayloadOf)
            } -> null

            else -> QuarantineReason.PARENT_SIGNATURE_INVALID
        }
    }

    private fun hashMatches(json: JsonObject, envelope: Envelope): Boolean = try {
        hashChain.hashOf(json, envelope.prevHash) == envelope.hash
    } catch (expected: IllegalArgumentException) {
        false
    } catch (expected: CharacterCodingException) {
        false
    }

    @Suppress("TooGenericExceptionCaught") // A third-party SignatureCheck must not be able to crash ingest.
    private fun verifies(
        publicKey: ByteArray,
        json: JsonObject,
        signature: ByteArray?,
        payloadOf: (JsonObject) -> ByteArray,
    ): Boolean {
        if (signature == null || signature.isEmpty()) return false
        return try {
            check.verify(publicKey, payloadOf(json), signature)
        } catch (expected: Exception) {
            false
        }
    }

    private fun withinLamportBound(lamport: Long, localMaxLamport: Long): Boolean {
        val jump = policy.maxLamportJump
        return localMaxLamport > Long.MAX_VALUE - jump || lamport <= localMaxLamport + jump
    }

    private companion object {
        val SUPPORTED_SCHEMA_VERSIONS = 1..Envelope.CURRENT_SCHEMA_VERSION

        /** A tree that does not decode: from a newer schema we cannot read yet, or simply broken. */
        fun undecodableReason(json: JsonObject): QuarantineReason {
            val version = (envelopeField(json, "schemaVersion") as? JsonPrimitive)?.longOrNull
            return if (version != null && version > Envelope.CURRENT_SCHEMA_VERSION) {
                QuarantineReason.SCHEMA_UNSUPPORTED
            } else {
                QuarantineReason.UNDECODABLE
            }
        }

        /** Best-effort ids of an undecodable document, for the audit view. */
        fun quarantine(json: JsonObject, reason: QuarantineReason) = VerifiedRemote.Quarantined(
            eventId = envelopeString(json, "eventId")?.let { runCatching { EventId(it) }.getOrNull() },
            hash = envelopeString(json, "hash"),
            authorDeviceId = envelopeString(json, "authorDeviceId")?.let { runCatching { DeviceId(it) }.getOrNull() },
            reason = reason,
        )

        fun envelopeField(json: JsonObject, key: String) = (json["envelope"] as? JsonObject)?.get(key)

        fun envelopeString(json: JsonObject, key: String): String? =
            (envelopeField(json, key) as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}

/** Outcome of [RemoteEventVerifier.verify]. */
sealed interface VerifiedRemote {
    /** Passed every check. [json] is the tree that was verified; store that, not a re-encoding of [event]. */
    data class Ok(val event: LedgerEvent, val json: JsonObject) : VerifiedRemote

    /**
     * Refused for [reason]; kept for the parent audit view and never projected. The ids are null only when the
     * document was too broken to read them.
     */
    data class Quarantined(
        val eventId: EventId?,
        val hash: String?,
        val authorDeviceId: DeviceId?,
        val reason: QuarantineReason,
    ) : VerifiedRemote
}

/** Why [RemoteEventVerifier] refused a pulled event, in the order the checks run. */
enum class QuarantineReason {
    /** Not a ledger event this client can decode (malformed, unknown type, or an old shape that no longer decodes). */
    UNDECODABLE,

    /** Written with a schema version this client does not read. */
    SCHEMA_UNSUPPORTED,

    /** The stored hash is not the hash of the stored content. */
    HASH_MISMATCH,

    /** The author device is not in the household registry. */
    UNKNOWN_DEVICE,

    /** The device is pending, or was revoked at or before this event's lamport. */
    DEVICE_NOT_ACTIVE,

    /** `authorUid` is not the uid the device is registered to. */
    UID_MISMATCH,

    /** A PARENT-role envelope from a device that is not registered as a parent device. */
    ROLE_NOT_ALLOWED,

    /** The device signature is missing or does not verify against the registered device key. */
    BAD_DEVICE_SIGNATURE,

    /** A parent-only event without a parent signature. */
    PARENT_SIGNATURE_MISSING,

    /** A parent signature that no registered parent key valid at the event's lamport verifies. */
    PARENT_SIGNATURE_INVALID,

    /** The lamport is further above the local maximum than [LedgerPolicy.maxLamportJump]. */
    LAMPORT_TOO_FAR_AHEAD,
}
