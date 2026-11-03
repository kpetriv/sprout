package dev.kpetriv.sprout.core.crypto

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec

/**
 * An EC P-256 public key as X.509 SubjectPublicKeyInfo DER: the form registered in the household device list
 * and carried in device-link QR codes. Public, so content equality is fine here.
 */
class PublicKeyBytes(encoded: ByteArray) {
    private val bytes: ByteArray = encoded.copyOf()

    /** A copy of the DER encoding. */
    val encoded: ByteArray get() = bytes.copyOf()

    /** SHA-256 of the DER encoding; shown as the key fingerprint when approving a second parent device. */
    fun fingerprint(): ByteArray = MessageDigest.getInstance(CryptoAlgorithms.DIGEST).digest(bytes)

    /** Decodes into a JCE key. Throws [java.security.spec.InvalidKeySpecException] when the DER is malformed. */
    fun toPublicKey(): PublicKey = KeyFactory.getInstance(CryptoAlgorithms.EC).generatePublic(X509EncodedKeySpec(bytes))

    override fun equals(other: Any?): Boolean = other is PublicKeyBytes && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "PublicKeyBytes(sha256=${fingerprint().toHex().take(FINGERPRINT_PREFIX_CHARS)}…)"

    private companion object {
        const val FINGERPRINT_PREFIX_CHARS = 16
    }
}

internal fun ByteArray.toHex(): String = joinToString(separator = "") { "%02x".format(it) }
