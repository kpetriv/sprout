package dev.kpetriv.sprout.core.crypto

import kotlin.time.Instant

/**
 * Where a key lives, as the local Keystore reports it through `KeyInfo`.
 *
 * This is **self-reported, not attestation**: the values come from the same OS that holds the key, so a rooted
 * or compromised device can report anything. Use it for diagnostics and UX hints only, never to make a trust
 * decision about another device. Hardware key attestation (a certificate chain to a Google root, checked by the
 * verifier) arrives in M3 with the household identity key.
 */
data class DeviceKeyProperties(
    /** Key material is inside a TEE or StrongBox and cannot be exported. */
    val isHardwareBacked: Boolean,
    /**
     * Key lives in a StrongBox secure element. Android 9-11 cannot report this after creation, so it is false
     * there even for StrongBox keys; [isHardwareBacked] is still accurate.
     */
    val isStrongBox: Boolean,
    /** When Keystore created the key, if known. */
    val createdAt: Instant?,
)

/**
 * The device's EC P-256 signing key (alias [KeyAliases.DEVICE_SIGNING], replaced by [KeyAliases.DEVICE_SIGNING_V2]
 * at registration). It signs every ledger event and chat message this device authors (threats T3, T16); its public
 * key is registered with the household at link time.
 *
 * The M1 device key is **provisional**: it is created on first use, without an attestation challenge, before the
 * device belongs to any household. At link time [regenerateForRegistration] creates the v2 key with
 * `setAttestationChallenge` (challenge from the backend) so the backend can verify where it lives.
 */
interface DeviceKeyStore {
    /** The signing key, created on first use. */
    suspend fun deviceSigningKey(): SigningKeyHandle

    /** X.509 SubjectPublicKeyInfo DER of the device key. Creates the key if needed, so it may block briefly. */
    fun publicKey(): PublicKeyBytes

    /**
     * The key's [DeviceKeyProperties], read from Keystore `KeyInfo`. Self-reported by this device, **not**
     * attestation; see [DeviceKeyProperties]. Creates the key if needed, so it may block briefly.
     */
    fun keyProperties(): DeviceKeyProperties

    /**
     * Creates a fresh signing key for registering this device with a household (M3 §1): EC P-256 under
     * [KeyAliases.DEVICE_SIGNING_V2] with `setAttestationChallenge(challenge)` (StrongBox first, TEE fallback, the
     * same key properties as the v1 key), makes it the active signing key and only then deletes the provisional v1
     * key. Calling it again replaces the v2 key. The device id (the key fingerprint) changes with it.
     *
     * @param challenge the backend's registration challenge (`beginDeviceRegistration`), bound into the attestation.
     */
    suspend fun regenerateForRegistration(challenge: ByteArray): DeviceRegistrationMaterial
}

/**
 * What a device sends to register its key: the X.509 SPKI DER [publicKey] and the Keystore attestation chain for it
 * ([attestationChain]: DER certificates, leaf first). The chain is empty when the key store cannot attest (software
 * keys, devices without attestation support); the backend then records the device as unattested (D-M3-5).
 */
class DeviceRegistrationMaterial(val publicKey: PublicKeyBytes, attestationChain: List<ByteArray>) {
    private val chain: List<ByteArray> = attestationChain.map { it.copyOf() }

    /** Copies of the DER certificates, leaf first. */
    val attestationChain: List<ByteArray> get() = chain.map { it.copyOf() }

    override fun toString(): String = "DeviceRegistrationMaterial(publicKey=$publicKey, chainLength=${chain.size})"
}
