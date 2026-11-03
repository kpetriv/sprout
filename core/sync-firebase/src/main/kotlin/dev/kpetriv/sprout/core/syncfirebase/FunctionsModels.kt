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

@Serializable
internal class EmptyRequest

@Serializable
internal data class CreateHouseholdRequest(
    val fiatCurrency: String,
    val devicePublicKey: String,
    val attestationChain: List<String>? = null,
)

@Serializable
internal data class RegisterDeviceRequest(val devicePublicKey: String, val attestationChain: List<String>? = null)

@Serializable
internal data class ApproveDeviceRequest(val deviceId: DeviceId, val approverDeviceId: DeviceId, val signature: String)

@Serializable
internal data class CreateLinkCodeRequest(val kidId: KidId)

@Serializable
internal data class RedeemLinkCodeRequest(
    val code: String,
    val devicePublicKey: String,
    val attestationChain: List<String>? = null,
) {
    override fun toString(): String = "RedeemLinkCodeRequest(code=<redacted>)"
}

@Serializable
internal data class RevokeDeviceRequest(val deviceId: DeviceId, val callerDeviceId: DeviceId)

@Serializable
internal data class DeleteHouseholdRequest(val confirmation: HouseholdId)

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

/** `revokeDevice` result: events of the device at or above [revokedAtLamport] are refused by receivers. */
@Serializable
data class RevokedDevice(val deviceId: DeviceId, val revokedAtLamport: Long)
