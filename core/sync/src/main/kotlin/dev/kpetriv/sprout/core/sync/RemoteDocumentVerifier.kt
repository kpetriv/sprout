package dev.kpetriv.sprout.core.sync

import dev.kpetriv.sprout.core.domainledger.DeviceRegistry
import dev.kpetriv.sprout.core.domainledger.DeviceStatus
import dev.kpetriv.sprout.core.domainledger.RegisteredDevice
import dev.kpetriv.sprout.core.domainledger.SignatureCheck
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.Role

/**
 * Why a pulled document was refused ([VerifiedDocument.Rejected]) or held ([VerifiedDocument.Held]). Codes only,
 * never free text, so they can go to the audit log.
 */
enum class DocumentRejection {
    /** No signature. */
    UNSIGNED,

    /** The author device is not in the household registry (yet): a [VerifiedDocument.Held] reason. */
    UNKNOWN_DEVICE,

    /**
     * The author device is PENDING (a [VerifiedDocument.Held] reason), or was REVOKED at or below the document's
     * lamport (a rejection).
     */
    DEVICE_NOT_ACTIVE,

    /** The claimed author uid is not the registered device's uid. */
    UID_MISMATCH,

    /** The device's registered role may not write this document (M3 §3 role gates). */
    ROLE_NOT_ALLOWED,

    /** The signature does not verify against the registered device key. */
    BAD_SIGNATURE,

    /** The lamport is more than `maxLamportJump` above everything this device has seen. */
    LAMPORT_TOO_FAR_AHEAD,

    /** Not one of [DocTypes]. */
    UNKNOWN_DOC_TYPE,

    /** The envelope names another household than the one this device belongs to. */
    WRONG_HOUSEHOLD,
}

/** Outcome of [RemoteDocumentVerifier.verify]. */
sealed interface VerifiedDocument {
    /** [envelope] verified against [device], the registered author. */
    class Ok(val envelope: DocumentEnvelope, val device: RegisteredDevice) : VerifiedDocument

    /**
     * Cannot be decided yet: the author device is unknown to the registry mirror ([DocumentRejection.UNKNOWN_DEVICE])
     * or still PENDING ([DocumentRejection.DEVICE_NOT_ACTIVE]). The data layer keeps the envelope (its
     * `document_inbox`) and verifies it again after the registry has caught up. Never applied while held.
     */
    data class Held(val reason: DocumentRejection) : VerifiedDocument

    /** Refused for good. */
    data class Rejected(val reason: DocumentRejection) : VerifiedDocument
}

/**
 * Checks a pulled document against the device registry before it can reach conflict resolution (M3 §3). Nothing
 * the envelope only claims is trusted: the author's uid, role, kid and key all come from [registry].
 *
 * Checks run cheapest and most fundamental first, and the role and lamport gates only run on an authenticated
 * envelope: doc type, household ([householdId]), signature present, device known (else held), uid matches,
 * signature valid, device active at the document's lamport (pending: held; revoked: rejected), role allowed, lamport
 * within [maxLamportJump] of [verify]'s `localMaxLamport` (the `LamportClock.observe` bound).
 */
class RemoteDocumentVerifier(
    private val registry: DeviceRegistry,
    private val check: SignatureCheck,
    private val maxLamportJump: Long,
    private val householdId: HouseholdId,
) {
    init {
        require(maxLamportJump > 0) { "maxLamportJump must be positive" }
    }

    /** Never throws for any envelope; a registry failure propagates (it is not the document's fault). */
    suspend fun verify(envelope: DocumentEnvelope, localMaxLamport: Long): VerifiedDocument {
        val malformed = when {
            !DocTypes.isKnown(envelope.docType) -> DocumentRejection.UNKNOWN_DOC_TYPE
            envelope.householdId != householdId -> DocumentRejection.WRONG_HOUSEHOLD
            envelope.signature == null -> DocumentRejection.UNSIGNED
            else -> null
        }
        val device = if (malformed == null) registry.device(envelope.authorDeviceId) else null
        val reason = malformed ?: device?.let { rejectionOf(envelope, it, localMaxLamport) }
        return when {
            malformed != null -> VerifiedDocument.Rejected(malformed)

            device == null -> VerifiedDocument.Held(DocumentRejection.UNKNOWN_DEVICE)

            reason == null -> VerifiedDocument.Ok(envelope, device)

            reason == DocumentRejection.DEVICE_NOT_ACTIVE && device.status == DeviceStatus.Pending ->
                VerifiedDocument.Held(reason)

            else -> VerifiedDocument.Rejected(reason)
        }
    }

    private fun rejectionOf(envelope: DocumentEnvelope, device: RegisteredDevice, localMaxLamport: Long) = when {
        device.uid != envelope.authorUid -> DocumentRejection.UID_MISMATCH

        !envelope.verifyWith(check, device.publicKey) -> DocumentRejection.BAD_SIGNATURE

        !device.acceptsLamport(envelope.lamport) -> DocumentRejection.DEVICE_NOT_ACTIVE

        !roleAllows(envelope, device) -> DocumentRejection.ROLE_NOT_ALLOWED

        // Lamports are never negative, so the difference cannot overflow.
        envelope.lamport - localMaxLamport.coerceAtLeast(0) > maxLamportJump ->
            DocumentRejection.LAMPORT_TOO_FAR_AHEAD

        else -> null
    }

    private fun roleAllows(envelope: DocumentEnvelope, device: RegisteredDevice): Boolean = when (device.role) {
        Role.PARENT -> true
        Role.KID -> DocTypes.writerRole(envelope.docType) == Role.KID && DocTypes.kidMayWrite(envelope, device)
    }
}
