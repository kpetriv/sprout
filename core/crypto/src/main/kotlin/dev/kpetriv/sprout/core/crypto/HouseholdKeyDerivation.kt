package dev.kpetriv.sprout.core.crypto

import javax.inject.Inject

/**
 * Derives per-thread AES-256-GCM keys from an X25519 shared secret (M4 household chat, ADR-0003).
 *
 * The caller's [info] is prefixed with a fixed, versioned label so a thread key can never collide with a key
 * derived from the same shared secret for another purpose.
 */
class HouseholdKeyDerivation @Inject constructor() {
    /**
     * HKDF-SHA256(ikm = [sharedSecret], salt = [salt], info = label || 0x00 || [info]) → 32-byte key.
     * Use the thread id plus a key epoch in [info] so key rotation on device removal produces a fresh key.
     */
    fun deriveThreadKey(sharedSecret: SharedSecret, salt: ByteArray, info: ByteArray): SymmetricKey {
        val fullInfo = THREAD_KEY_LABEL + 0.toByte() + info
        val key = sharedSecret.withMaterial { ikm ->
            Hkdf.derive(ikm = ikm, salt = salt, info = fullInfo, length = SymmetricKey.AES_256_KEY_SIZE)
        }
        return SymmetricKey.adopt(key)
    }

    private companion object {
        val THREAD_KEY_LABEL = "sprout/household-thread-key/v1".encodeToByteArray()
    }
}
