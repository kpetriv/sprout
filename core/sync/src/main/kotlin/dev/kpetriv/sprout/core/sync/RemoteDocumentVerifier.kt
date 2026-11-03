package dev.kpetriv.sprout.core.sync

import dev.kpetriv.sprout.core.domainledger.DeviceRegistry
import dev.kpetriv.sprout.core.domainledger.RegisteredDevice
import dev.kpetriv.sprout.core.domainledger.SignatureCheck
import dev.kpetriv.sprout.core.model.Role

/** Why a pulled document was refused. Codes only, never free text, so they can go to the audit log. */
enum class DocumentRejection {
    /** No signature. */
    UNSIGNED,

    /** The author device is not in the household registry. */
    UNKNOWN_DEVICE,

    /** The author device is pending, or was revoked at or below the document's lamport. */
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
}

/** Outcome of [RemoteDocumentVerifier.verify]. */
sealed interface VerifiedDocument {
    /** [envelope] verified against [device], the registered author. */
    class Ok(val envelope: DocumentEnvelope, val device: RegisteredDevice) : VerifiedDocument

    data class Rejected(val reason: DocumentRejection) : VerifiedDocument
}

/**
 * Checks a pulled document against the device registry before it can reach conflict resolution (M3 §3). Nothing
 * the envelope only claims is trusted: the author's uid, role, kid and key all come from [registry].
 *
 * Checks run cheapest and most fundamental first, and the role and lamport gates only run on an authenticated
 * envelope: doc type, signature present, device known, uid matches, signature valid, device active at the
 * document's lamport, role allowed, lamport within [maxLamportJump] of [verify]'s `localMaxLamport` (the
 * `LamportClock.observe` bound).
 */
class RemoteDocumentVerifier(
    private val registry: DeviceRegistry,
    private val check: SignatureCheck,
    private val maxLamportJump: Long,
) {
    init {
        require(maxLamportJump > 0) { "maxLamportJump must be positive" }
    }

    /** Never throws for any envelope; a registry failure propagates (it is not the document's fault). */
    suspend fun verify(envelope: DocumentEnvelope, localMaxLamport: Long): VerifiedDocument {
        val wellFormed = DocTypes.isKnown(envelope.docType) && envelope.signature != null
        val device = if (wellFormed) registry.device(envelope.authorDeviceId) else null
        val reason = rejectionOf(envelope, device, localMaxLamport)
        return if (reason == null && device != null) {
            VerifiedDocument.Ok(envelope, device)
        } else {
            VerifiedDocument.Rejected(reason ?: DocumentRejection.UNKNOWN_DEVICE)
        }
    }

    private fun rejectionOf(envelope: DocumentEnvelope, device: RegisteredDevice?, localMaxLamport: Long) = when {
        !DocTypes.isKnown(envelope.docType) -> DocumentRejection.UNKNOWN_DOC_TYPE

        envelope.signature == null -> DocumentRejection.UNSIGNED

        device == null -> DocumentRejection.UNKNOWN_DEVICE

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
