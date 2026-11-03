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
 * The device's EC P-256 signing key (alias [KeyAliases.DEVICE_SIGNING]). It signs every ledger event and chat
 * message this device authors (threats T3, T16); its public key is registered with the household at link time.
 *
 * The M1 device key is **provisional**: it is created on first use, without an attestation challenge, before the
 * device belongs to any household. M3 generates the household identity key at link time with
 * `setAttestationChallenge` (challenge from the household link handshake) so peers can verify where it lives.
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
}
