package dev.kpetriv.sprout.core.crypto.chat

import dev.kpetriv.sprout.core.crypto.DeviceSigner
import dev.kpetriv.sprout.core.crypto.JceEcdsaSigner
import dev.kpetriv.sprout.core.crypto.PublicKeyBytes
import dev.kpetriv.sprout.core.crypto.SigningKeyHandle
import dev.kpetriv.sprout.core.crypto.Verifier
import dev.kpetriv.sprout.core.crypto.X25519PublicKey
import dev.kpetriv.sprout.core.domainledger.BoundEncryptionKey
import dev.kpetriv.sprout.core.domainledger.EncryptionKeyBindingPayload
import dev.kpetriv.sprout.core.domainledger.SignatureCheck
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.HouseholdId
import javax.inject.Inject

/**
 * Binds this device's X25519 chat key to its P-256 device key (M4 §1.1, D-M4-2): a DER ECDSA signature over
 * [EncryptionKeyBindingPayload.of]. The server stores `encryptionKey` and `encryptionKeySignature` only after verifying
 * it; receivers verify it again with [BoundEncryptionKey.verify] (or `RegisteredDevice.verifiedEncryptionKey`).
 */
class EncryptionKeyBinding @Inject constructor(private val deviceSigner: DeviceSigner) {
    /** Signs with the **active** device key: `registerEncryptionKey` for a device registered before M4. */
    suspend fun sign(householdId: HouseholdId, deviceId: DeviceId, encryptionKey: X25519PublicKey): ByteArray =
        deviceSigner.sign(EncryptionKeyBindingPayload.of(householdId, deviceId, encryptionKey.bytes))

    /**
     * Signs with [signingKey], for a registration that sends the encryption key together with a not yet active
     * (pending) device key (`createHousehold`, `registerParentDevice`, `redeemLinkCode`). Keystore signing blocks:
     * call it off the main thread.
     */
    fun signWith(
        signingKey: SigningKeyHandle,
        householdId: HouseholdId,
        deviceId: DeviceId,
        encryptionKey: X25519PublicKey,
    ): ByteArray =
        JceEcdsaSigner(signingKey).sign(EncryptionKeyBindingPayload.of(householdId, deviceId, encryptionKey.bytes))

    companion object {
        /** Whether [bound] is bound to [deviceId] by [devicePublicKey] (the registered P-256 key); never throws. */
        fun verify(
            check: SignatureCheck,
            bound: BoundEncryptionKey,
            deviceId: DeviceId,
            devicePublicKey: PublicKeyBytes,
        ): Boolean = bound.verify(check, deviceId, devicePublicKey.encoded)
    }
}

/** This [Verifier] as the domain's [SignatureCheck] port (SPKI DER keys, DER signatures, false on bad input). */
fun Verifier.asSignatureCheck(): SignatureCheck =
    SignatureCheck { publicKey, payload, signature -> verify(PublicKeyBytes(publicKey), payload, signature) }
