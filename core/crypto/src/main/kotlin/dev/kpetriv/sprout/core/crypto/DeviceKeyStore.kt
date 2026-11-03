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
 * The device's EC P-256 signing key (alias [KeyAliases.DEVICE_SIGNING], replaced at registration by a key in one of
 * the two registration slots [KeyAliases.DEVICE_SIGNING_V2] and [KeyAliases.DEVICE_SIGNING_V2_PENDING]). It signs
 * every ledger event and chat message this device authors (threats T3, T16); its public key is registered with the
 * household at link time.
 *
 * The M1 device key is **provisional**: it is created on first use, without an attestation challenge, before the
 * device belongs to any household. Registration is two-phase so a failed or refused registration can never leave the
 * device without the key the household knows (audit M1):
 * 1. [regenerateForRegistration] creates a **pending** key with `setAttestationChallenge` (challenge from the
 *    backend). The active key, which [deviceSigningKey] and `DeviceSigner` keep using, does not change.
 * 2. Once the server accepted the registration, [promotePendingKey] makes the pending key the active one and deletes
 *    the previous one; on failure [discardPendingKey] deletes the pending key and the previous key stays.
 */
interface DeviceKeyStore {
    /** The active signing key, created on first use. Never the pending key. */
    suspend fun deviceSigningKey(): SigningKeyHandle

    /** X.509 SubjectPublicKeyInfo DER of the active key. Creates the key if needed, so it may block briefly. */
    fun publicKey(): PublicKeyBytes

    /**
     * The active key's [DeviceKeyProperties], read from Keystore `KeyInfo`. Self-reported by this device, **not**
     * attestation; see [DeviceKeyProperties]. Creates the key if needed, so it may block briefly.
     */
    fun keyProperties(): DeviceKeyProperties

    /**
     * Creates a fresh **pending** signing key for registering this device with a household (M3 §1): EC P-256 with
     * `setAttestationChallenge(challenge)` (StrongBox first, TEE fallback, the same key properties as the v1 key) in
     * the registration slot the active key does not use. A pending key left by an earlier attempt is replaced. The
     * active key is untouched until [promotePendingKey].
     *
     * @param challenge the backend's registration challenge (`beginDeviceRegistration`), bound into the attestation.
     */
    suspend fun regenerateForRegistration(challenge: ByteArray): DeviceRegistrationMaterial

    /**
     * The pending key from [regenerateForRegistration], or null when there is none. It survives a process restart, so
     * a registration the server accepted just before the process died can still be promoted.
     */
    suspend fun pendingSigningKey(): SigningKeyHandle?

    /**
     * Makes the pending key the active signing key (the server accepted its registration) and deletes the previous
     * active key and the provisional v1 key. Returns the new active key's public key.
     *
     * @throws IllegalStateException when there is no pending key.
     */
    suspend fun promotePendingKey(): PublicKeyBytes

    /** Deletes the pending key, if any; the active key stays (registration failed or was refused). */
    suspend fun discardPendingKey()
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
