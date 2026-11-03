package dev.kpetriv.sprout.core.crypto

import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Authenticated encryption with associated data. */
interface Aead {
    /**
     * Encrypts [plaintext] under [key], binding [associatedData] (for example `householdId || docId`) so the
     * ciphertext cannot be replayed into another context. [associatedData] must not be empty: every caller has a
     * context to bind, and an empty one is almost always a bug. Throws [IllegalArgumentException] if it is.
     *
     * Output: `version || nonce || ciphertext || tag`, where `version` is a one-byte format version that is also
     * authenticated (the cipher sees `version || associatedData` as its associated data).
     */
    fun encrypt(key: SymmetricKey, plaintext: ByteArray, associatedData: ByteArray): ByteArray

    /**
     * Reverses [encrypt]. Throws [AeadException] when the format version is unknown or when the key, associated
     * data, version, nonce, ciphertext, or tag do not match; beyond an unknown version, the exception never says
     * which part was wrong. Throws [IllegalArgumentException] for empty [associatedData], as [encrypt] does.
     */
    fun decrypt(key: SymmetricKey, ciphertext: ByteArray, associatedData: ByteArray): ByteArray
}

/** Decryption failed authentication, the format version is unknown, or the input was too short. */
class AeadException(message: String, cause: Throwable? = null) : GeneralSecurityException(message, cause)

/**
 * AES-256-GCM through JCE with a fresh 12-byte nonce from [random] on every call (never a counter, never reused)
 * and a 128-bit tag. With random nonces a single key stays well within the NIST limit of 2^32 messages for any
 * realistic household; thread keys also rotate on device removal.
 *
 * Wire format v1: `0x01 || nonce(12) || ciphertext || tag(16)`, with GCM associated data `0x01 || associatedData`.
 * Binding the version into the tag means a blob cannot be relabelled as another format version and still decrypt.
 */
class AesGcmAead(private val random: SecureRandom) : Aead {
    override fun encrypt(key: SymmetricKey, plaintext: ByteArray, associatedData: ByteArray): ByteArray {
        requireAssociatedData(associatedData)
        val nonce = ByteArray(NONCE_SIZE).also(random::nextBytes)
        val body = key.withMaterial { material ->
            requireKeySize(material)
            Cipher.getInstance(CryptoAlgorithms.SYMMETRIC_CIPHER).run {
                init(
                    Cipher.ENCRYPT_MODE,
                    SecretKeySpec(material, CryptoAlgorithms.AES),
                    GCMParameterSpec(TAG_BITS, nonce),
                )
                updateAAD(versionedAssociatedData(FORMAT_V1, associatedData))
                doFinal(plaintext)
            }
        }
        return byteArrayOf(FORMAT_V1) + nonce + body
    }

    override fun decrypt(key: SymmetricKey, ciphertext: ByteArray, associatedData: ByteArray): ByteArray {
        requireAssociatedData(associatedData)
        if (ciphertext.size < OVERHEAD) throw AeadException("Ciphertext too short")
        val version = ciphertext[0]
        if (version != FORMAT_V1) throw AeadException("Unsupported ciphertext version")
        return key.withMaterial { material ->
            requireKeySize(material)
            try {
                Cipher.getInstance(CryptoAlgorithms.SYMMETRIC_CIPHER).run {
                    init(
                        Cipher.DECRYPT_MODE,
                        SecretKeySpec(material, CryptoAlgorithms.AES),
                        GCMParameterSpec(TAG_BITS, ciphertext, VERSION_SIZE, NONCE_SIZE),
                    )
                    updateAAD(versionedAssociatedData(version, associatedData))
                    doFinal(ciphertext, BODY_OFFSET, ciphertext.size - BODY_OFFSET)
                }
            } catch (e: AEADBadTagException) {
                throw AeadException("Authentication failed", e)
            }
        }
    }

    private fun requireAssociatedData(associatedData: ByteArray) {
        require(associatedData.isNotEmpty()) { "Associated data must not be empty" }
    }

    private fun requireKeySize(material: ByteArray) {
        require(material.size == SymmetricKey.AES_256_KEY_SIZE) { "AES-256-GCM needs a 32-byte key" }
    }

    companion object {
        /** Format version byte that prefixes every ciphertext and is bound into the associated data. */
        const val FORMAT_V1: Byte = 0x01
        const val VERSION_SIZE = 1
        const val NONCE_SIZE = 12
        const val TAG_BITS = 128
        const val TAG_SIZE = TAG_BITS / Byte.SIZE_BITS

        /** Bytes added to the plaintext length: version byte, nonce, and tag. */
        const val OVERHEAD = VERSION_SIZE + NONCE_SIZE + TAG_SIZE

        private const val BODY_OFFSET = VERSION_SIZE + NONCE_SIZE

        /** The associated data GCM actually authenticates: `version || associatedData`. */
        internal fun versionedAssociatedData(version: Byte, associatedData: ByteArray): ByteArray =
            byteArrayOf(version) + associatedData
    }
}
