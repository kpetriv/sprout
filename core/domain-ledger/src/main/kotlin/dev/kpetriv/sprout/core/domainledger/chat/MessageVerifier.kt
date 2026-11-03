package dev.kpetriv.sprout.core.domainledger.chat

import dev.kpetriv.sprout.core.domainledger.DeviceRegistry
import dev.kpetriv.sprout.core.domainledger.DeviceStatus
import dev.kpetriv.sprout.core.domainledger.QuarantineReason
import dev.kpetriv.sprout.core.domainledger.RegisteredDevice
import dev.kpetriv.sprout.core.domainledger.SignatureCheck
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.MessageId
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.model.ThreadId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Decides whether a pulled chat message may be stored (M4 §1.3), mirroring
 * [dev.kpetriv.sprout.core.domainledger.RemoteEventVerifier]. Nothing the envelope only claims is trusted: uid, role,
 * kid and key come from [registry].
 *
 * Checks run in this order and the first failure wins:
 * 1. the tree decodes as a [MessageEnvelope] (closed top level; a newer `schemaVersion` is
 *    [QuarantineReason.SCHEMA_UNSUPPORTED], anything else [QuarantineReason.UNDECODABLE]) and its `schemaVersion` is 1;
 * 2. `householdId` is this device's household ([QuarantineReason.WRONG_HOUSEHOLD]);
 * 3. the author device is registered (else **held**, [QuarantineReason.UNKNOWN_DEVICE]) and not PENDING (else held,
 *    [QuarantineReason.DEVICE_NOT_ACTIVE]);
 * 4. the device accepts the lamport ([RegisteredDevice.acceptsLamport]; a REVOKED device at or above its revocation
 *    lamport is quarantined as [QuarantineReason.DEVICE_NOT_ACTIVE]);
 * 5. `authorUid` is the device owner's uid ([QuarantineReason.UID_MISMATCH]);
 * 6. a PARENT-role envelope comes from a PARENT device ([QuarantineReason.ROLE_NOT_ALLOWED]); KID-role envelopes are
 *    accepted from PARENT devices too (kid mode on a parent phone, D-M3-12);
 * 7. a KID device writes only into its own kid's thread ([QuarantineReason.WRONG_THREAD]);
 * 8. the device signature verifies ([MessageEnvelope.verifyWith]; [QuarantineReason.BAD_DEVICE_SIGNATURE]);
 * 9. the lamport is at most `localMaxLamport + maxLamportJump` ([QuarantineReason.LAMPORT_TOO_FAR_AHEAD]);
 * 10. `seq` continuity per (author device, thread): `seq < expectedSeq` is [VerifiedMessage.Duplicate] (ignored),
 *     `seq > expectedSeq` is **held** as [QuarantineReason.SEQ_GAP] until the missing ones arrive, `seq == expectedSeq`
 *     is [VerifiedMessage.Ok]. Continuity is checked last, so only authenticated messages can be held for a gap.
 *
 * Decryption is not part of verification: a verified message whose epoch key is missing is stored LOCKED and
 * decrypted when the key arrives (M4 §1.3); a decryption failure is never a quarantine.
 */
class MessageVerifier(
    private val registry: DeviceRegistry,
    private val check: SignatureCheck,
    private val maxLamportJump: Long,
    private val householdId: HouseholdId,
) {
    init {
        require(maxLamportJump > 0) { "maxLamportJump must be positive" }
    }

    /**
     * Verifies a pulled envelope tree. [expectedSeq] returns the next `seq` this device expects from the author device
     * in the thread (1 when it has none); it is only called for a decoded envelope. Never throws for any [json];
     * exceptions from [registry] or [expectedSeq] (storage failures) propagate.
     *
     * @param localMaxLamport the highest lamport this device has seen.
     */
    suspend fun verify(
        json: JsonObject,
        localMaxLamport: Long,
        expectedSeq: suspend (DeviceId, ThreadId) -> Long,
    ): VerifiedMessage {
        val envelope = MessageJson.decodeOrNull(json) ?: return undecodable(json)
        return verify(envelope, localMaxLamport, expectedSeq(envelope.authorDeviceId, envelope.threadId))
    }

    /** Checks 1 (schema version) to 10 for a decoded [envelope]. */
    suspend fun verify(envelope: MessageEnvelope, localMaxLamport: Long, expectedSeq: Long): VerifiedMessage {
        val intrinsic = when {
            envelope.schemaVersion != MessageEnvelope.CURRENT_SCHEMA_VERSION -> QuarantineReason.SCHEMA_UNSUPPORTED
            envelope.householdId != householdId -> QuarantineReason.WRONG_HOUSEHOLD
            else -> null
        }
        val device = if (intrinsic == null) registry.device(envelope.authorDeviceId) else null
        val reason = intrinsic ?: device?.takeUnless { it.status == DeviceStatus.Pending }?.let {
            deviceFailure(it, envelope) ?: lamportFailure(envelope, localMaxLamport)
        }
        return when {
            reason != null -> quarantined(envelope, reason)
            device == null -> VerifiedMessage.Held(envelope, QuarantineReason.UNKNOWN_DEVICE)
            device.status == DeviceStatus.Pending -> VerifiedMessage.Held(envelope, QuarantineReason.DEVICE_NOT_ACTIVE)
            envelope.seq < expectedSeq -> VerifiedMessage.Duplicate(envelope.messageId)
            envelope.seq > expectedSeq -> VerifiedMessage.Held(envelope, QuarantineReason.SEQ_GAP)
            else -> VerifiedMessage.Ok(envelope, device)
        }
    }

    /** Checks 4 to 8. */
    private fun deviceFailure(device: RegisteredDevice, envelope: MessageEnvelope): QuarantineReason? = when {
        !device.acceptsLamport(envelope.lamport) -> QuarantineReason.DEVICE_NOT_ACTIVE
        envelope.authorUid != device.uid -> QuarantineReason.UID_MISMATCH
        envelope.authorRole == Role.PARENT && device.role != Role.PARENT -> QuarantineReason.ROLE_NOT_ALLOWED
        device.role == Role.KID && envelope.threadId.value != device.kidId?.value -> QuarantineReason.WRONG_THREAD
        !envelope.verifyWith(check, device.publicKey) -> QuarantineReason.BAD_DEVICE_SIGNATURE
        else -> null
    }

    /** Check 9. Lamports are never negative, so the difference cannot overflow. */
    private fun lamportFailure(envelope: MessageEnvelope, localMaxLamport: Long): QuarantineReason? =
        QuarantineReason.LAMPORT_TOO_FAR_AHEAD.takeIf {
            envelope.lamport - localMaxLamport.coerceAtLeast(0) > maxLamportJump
        }

    private companion object {
        fun quarantined(envelope: MessageEnvelope, reason: QuarantineReason) =
            VerifiedMessage.Quarantined(envelope.messageId, envelope.authorDeviceId, reason)

        /** A tree that does not decode: from a newer schema, or broken. Ids are best effort, for the audit view. */
        fun undecodable(json: JsonObject): VerifiedMessage.Quarantined {
            val version = (json["schemaVersion"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
            val reason = if (version != null && version > MessageEnvelope.CURRENT_SCHEMA_VERSION) {
                QuarantineReason.SCHEMA_UNSUPPORTED
            } else {
                QuarantineReason.UNDECODABLE
            }
            return VerifiedMessage.Quarantined(
                messageId = json.idOrNull("messageId", ::MessageId),
                authorDeviceId = json.idOrNull("authorDeviceId", ::DeviceId),
                reason = reason,
            )
        }

        fun <T> JsonObject.idOrNull(key: String, create: (String) -> T): T? = (this[key] as? JsonPrimitive)?.takeIf {
            it.isString
        }?.content?.let { runCatching { create(it) }.getOrNull() }
    }
}

/** Outcome of [MessageVerifier.verify]. */
sealed interface VerifiedMessage {
    /** Passed every check; [device] is the registered author. Store it (decrypted if the epoch key is held). */
    class Ok(val envelope: MessageEnvelope, val device: RegisteredDevice) : VerifiedMessage {
        override fun toString(): String = "Ok(${envelope.messageId})"
    }

    /** A `seq` this device already has from the author in the thread: ignored. */
    data class Duplicate(val messageId: MessageId) : VerifiedMessage

    /**
     * Cannot be decided yet ([QuarantineReason.UNKNOWN_DEVICE], [QuarantineReason.DEVICE_NOT_ACTIVE] for a PENDING
     * device, or [QuarantineReason.SEQ_GAP]): keep [envelope] (status HELD) and verify it again after the registry
     * caught up or the earlier messages arrived. Never shown while held.
     */
    data class Held(val envelope: MessageEnvelope, val reason: QuarantineReason) : VerifiedMessage

    /** Refused for good; kept for the audit view. The ids are null only when the envelope was too broken to read. */
    data class Quarantined(val messageId: MessageId?, val authorDeviceId: DeviceId?, val reason: QuarantineReason) :
        VerifiedMessage
}
