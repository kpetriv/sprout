package dev.kpetriv.sprout.core.syncfirebase

import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.KidId
import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64

/**
 * The key a device registers: [publicKey] is the X.509 SPKI DER of its P-256 signing key, [attestationChain] the
 * Keystore attestation certificates (leaf first, DER), or null where the key has no attestation (D-M3-5).
 */
class DeviceKeyRegistration(val publicKey: ByteArray, val attestationChain: List<ByteArray>?) {
    internal fun publicKeyBase64(): String = Base64.Default.encode(publicKey)

    internal fun attestationChainBase64(): List<String>? = attestationChain?.map { Base64.Default.encode(it) }

    override fun toString(): String = "DeviceKeyRegistration(attested=${attestationChain != null})"
}

/**
 * A device's X25519 encryption key as it is registered (M4 §1.1, D-M4-2): [publicKey] the raw 32 bytes, [signature]
 * the device key's ECDSA signature (DER) over core/domain-ledger `EncryptionKeyBindingPayload.of(hid, deviceId,
 * publicKey)`. The server verifies the binding against the device key before storing both on the device row.
 */
class EncryptionKeyUpload(publicKey: ByteArray, signature: ByteArray) {
    private val key = publicKey.copyOf()
    private val sig = signature.copyOf()

    internal fun publicKeyBase64(): String = Base64.Default.encode(key)

    internal fun signatureBase64(): String = Base64.Default.encode(sig)

    override fun toString(): String = "EncryptionKeyUpload(<redacted>)"
}

@Serializable
internal class EmptyRequest

@Serializable
internal data class CreateHouseholdRequest(
    val fiatCurrency: String,
    val devicePublicKey: String,
    val attestationChain: List<String>? = null,
    val encryptionKey: String? = null,
    val encryptionKeySignature: String? = null,
)

@Serializable
internal data class RegisterDeviceRequest(
    val devicePublicKey: String,
    val attestationChain: List<String>? = null,
    val encryptionKey: String? = null,
    val encryptionKeySignature: String? = null,
)

/** `registerEncryptionKey`: [signature] is the device proof, [bindingSignature] the key's binding. */
@Serializable
internal data class RegisterEncryptionKeyRequest(
    val publicKey: String,
    val bindingSignature: String,
    val deviceId: DeviceId,
    val signature: String,
)

@Serializable
internal data class ApproveDeviceRequest(val deviceId: DeviceId, val approverDeviceId: DeviceId, val signature: String)

@Serializable
internal data class CreateLinkCodeRequest(val kidId: KidId, val callerDeviceId: DeviceId, val signature: String)

/** A request whose only arguments are the device proof (`exportHousehold`). */
@Serializable
internal data class ProvenRequest(val callerDeviceId: DeviceId, val signature: String)

@Serializable
internal data class RegisterParentKeyRequest(
    val publicKey: String,
    val attestationChain: List<String>? = null,
    val deviceId: DeviceId,
    val signature: String,
)

@Serializable
internal data class RedeemLinkCodeRequest(
    val code: String,
    val devicePublicKey: String,
    val attestationChain: List<String>? = null,
    val encryptionKey: String? = null,
    val encryptionKeySignature: String? = null,
) {
    override fun toString(): String = "RedeemLinkCodeRequest(code=<redacted>)"
}

@Serializable
internal data class RevokeDeviceRequest(val deviceId: DeviceId, val callerDeviceId: DeviceId, val signature: String)

@Serializable
internal data class DeleteHouseholdRequest(
    val confirmation: HouseholdId,
    val callerDeviceId: DeviceId,
    val signature: String,
)

/** `pruneMessages`: the cut ([before], epoch ms) and whether it is a purge, with the device proof. */
@Serializable
internal data class PruneMessagesRequest(
    val before: Long,
    val purge: Boolean,
    val callerDeviceId: DeviceId,
    val signature: String,
)

/**
 * What the server's `pruneMessages` deleted (M7 §2): [messages] and chat [blobChunks] stored before [before] (epoch
 * ms, the cut the server applied). [more] is true when it stopped at its per-call limit: call again.
 */
@Serializable
data class PrunedMessages(val messages: Int, val blobChunks: Int, val more: Boolean, val before: Long)

@Serializable
internal data class DeviceStatusRequest(val hid: HouseholdId, val deviceId: DeviceId, val signature: String) {
    /** Says which fields are set, never their values (ids and a replayable signature). */
    override fun toString(): String = "DeviceStatusRequest(<redacted>)"
}

@Serializable
internal data class DeviceStatusResponse(val status: String, val householdGone: Boolean)

/** A device's registry status as `deviceStatus` reports it; [UNKNOWN]: no such device (or household). */
enum class DeviceStatusValue { ACTIVE, PENDING, REVOKED, UNKNOWN }

/**
 * `deviceStatus` result: this device's [status] in the registry, and [householdGone] when the household is known to
 * have been deleted (only ever with [DeviceStatusValue.UNKNOWN]).
 */
data class DeviceStatusAnswer(val status: DeviceStatusValue, val householdGone: Boolean)

@Serializable
internal data class ChallengeResponse(val challenge: String) {
    override fun toString(): String = "ChallengeResponse(<redacted>)"
}

@Serializable
internal data class ApprovedDevice(val deviceId: DeviceId, val status: String)

@Serializable
internal data class DeletedHousehold(val deleted: Boolean)

/** `createHousehold` result: the new household and this device's id. */
@Serializable
data class CreatedHousehold(val hid: HouseholdId, val deviceId: DeviceId)

/** `registerParentDevice` result: [fingerprint] is what the approving phone shows ("ab12 cd34 ef56 7890"). */
@Serializable
data class PendingDevice(val deviceId: DeviceId, val fingerprint: String)

/** A link code for a kid device; [toString] never shows [code] (T6: codes are never logged). */
@Serializable
data class LinkCode(val code: String, val expiresAt: Long) {
    override fun toString(): String = "LinkCode(expiresAt=$expiresAt)"
}

/** `redeemLinkCode` result: the household and kid this device now belongs to. */
@Serializable
data class RedeemedLink(val hid: HouseholdId, val kidId: KidId, val deviceId: DeviceId)

/**
 * `registerEncryptionKey` result: [created] is false when the same key was already registered for [deviceId] (an
 * idempotent retry).
 */
@Serializable
data class RegisteredEncryptionKey(val deviceId: DeviceId, val created: Boolean)

/** `revokeDevice` result: events of the device at or above [revokedAtLamport] are refused by receivers. */
@Serializable
data class RevokedDevice(val deviceId: DeviceId, val revokedAtLamport: Long)

/**
 * `registerParentKey` result: the key's [fingerprint] (hex SHA-256 of its SPKI DER) and the lamport the server made it
 * valid from (one above everything stored in the household when it was registered).
 */
@Serializable
data class ParentKeyRegistration(val fingerprint: String, val validFromLamport: Long)

/** `setRecoveryPin` (ADR-0013 §4): the check value, base64, with the ACTIVE caller's proof. */
@Serializable
internal data class SetRecoveryPinRequest(
    val salt: String,
    val kdf: String,
    val verifier: String,
    val newPin: Boolean,
    val callerDeviceId: DeviceId,
    val signature: String,
) {
    override fun toString(): String = "SetRecoveryPinRequest(newPin=$newPin, <redacted>)"
}

/** `recoveryStatus` and `recoverParentDevice`: the PENDING device's own proof, and the method with its verifier. */
@Serializable
internal data class RecoveryRequestBody(
    val deviceId: DeviceId,
    val signature: String,
    val method: String? = null,
    val verifier: String? = null,
) {
    override fun toString(): String = "RecoveryRequestBody(method=$method, <redacted>)"
}

@Serializable
internal data class RecoveryPinParameters(val salt: String, val kdf: String) {
    override fun toString(): String = "RecoveryPinParameters(kdf=$kdf, <redacted>)"
}

@Serializable
internal data class RecoveryStatusResponse(
    val pin: RecoveryPinParameters? = null,
    val attemptsLeft: Int,
    val waitEndsAt: Long? = null,
)

/**
 * `setRecoveryPin`'s answer: the attempts left on the value the server holds (wrong PINs tried in restores add up), and
 * whether this call's value was stored (false: another ACTIVE phone's value was kept; missing means true).
 */
@Serializable
internal data class SetRecoveryPinResponse(val set: Boolean, val attemptsLeft: Int, val stored: Boolean = true)

@Serializable
internal data class RecoveredDeviceResponse(
    val deviceId: DeviceId,
    val status: String,
    val revokedDevices: Int,
    val method: String,
    val attemptsLeft: Int,
    val signInAgain: Boolean = true,
)
