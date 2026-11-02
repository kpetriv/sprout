package dev.kpetriv.sprout.core.crypto

import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement

/** An X25519 public key: the 32-byte little-endian u-coordinate from RFC 7748. */
class X25519PublicKey(bytes: ByteArray) {
    private val raw: ByteArray = bytes.copyOf()

    init {
        require(raw.size == SIZE) { "X25519 public key must be $SIZE bytes" }
    }

    val bytes: ByteArray get() = raw.copyOf()

    override fun equals(other: Any?): Boolean = other is X25519PublicKey && raw.contentEquals(other.raw)

    override fun hashCode(): Int = raw.contentHashCode()

    override fun toString(): String = "X25519PublicKey(${raw.toHex()})"

    companion object {
        const val SIZE = 32
    }
}

/** An X25519 key pair. Closing it wipes the private key. */
class X25519KeyPair(val privateKey: X25519PrivateKey, val publicKey: X25519PublicKey) : AutoCloseable {
    override fun close() = privateKey.close()

    override fun toString(): String = "X25519KeyPair(publicKey=$publicKey, privateKey=<redacted>)"
}

/** Thrown when an agreement fails, including a low-order peer key that would give an all-zero secret. */
class KeyAgreementException(message: String, cause: Throwable? = null) : GeneralSecurityException(message, cause)

/**
 * X25519 Diffie-Hellman (RFC 7748), used at link time to derive household chat keys (M4). Named for the curve so
 * it does not clash with `javax.crypto.KeyAgreement`.
 */
interface X25519KeyAgreement {
    fun generateKeyPair(): X25519KeyPair

    /** The public key for [privateKey], computed as X25519(k, 9). */
    fun publicKeyOf(privateKey: X25519PrivateKey): X25519PublicKey

    /**
     * X25519(privateKey, peerPublicKey). Throws [KeyAgreementException] if the result is all zeros (low-order
     * peer key), as RFC 7748 section 6.1 allows. Pass the result through [HouseholdKeyDerivation] before use.
     */
    fun sharedSecret(privateKey: X25519PrivateKey, peerPublicKey: X25519PublicKey): SharedSecret
}

/**
 * X25519 through the JCE "XDH" algorithm (JDK 11+, Android 13+ via Conscrypt). Keys travel as raw 32-byte
 * values and are wrapped in their fixed DER prefixes only at the JCE boundary.
 *
 * The private key is 32 random bytes; X25519 clamps the scalar itself (RFC 7748 section 5), so no
 * KeyPairGenerator is needed and the raw scalar can be persisted through a [SecretStore].
 */
class JceX25519KeyAgreement(private val random: SecureRandom) : X25519KeyAgreement {
    override fun generateKeyPair(): X25519KeyPair {
        val privateKey = X25519PrivateKey.adopt(ByteArray(X25519PrivateKey.SIZE).also(random::nextBytes))
        return X25519KeyPair(privateKey, publicKeyOf(privateKey))
    }

    override fun publicKeyOf(privateKey: X25519PrivateKey): X25519PublicKey =
        sharedSecret(privateKey, BASE_POINT).use { X25519PublicKey(it.copyBytes()) }

    override fun sharedSecret(privateKey: X25519PrivateKey, peerPublicKey: X25519PublicKey): SharedSecret {
        val secret = try {
            val factory = KeyFactory.getInstance(CryptoAlgorithms.XDH)
            val peer = factory.generatePublic(X509EncodedKeySpec(PUBLIC_DER_PREFIX + peerPublicKey.bytes))
            val pkcs8 = privateKey.withMaterial { PRIVATE_DER_PREFIX + it }
            try {
                KeyAgreement.getInstance(CryptoAlgorithms.XDH).run {
                    init(factory.generatePrivate(PKCS8EncodedKeySpec(pkcs8)))
                    doPhase(peer, true)
                    generateSecret()
                }
            } finally {
                pkcs8.fill(0)
            }
        } catch (e: GeneralSecurityException) {
            // JDK rejects some low-order points itself; report every failure the same way.
            throw KeyAgreementException("X25519 agreement failed", e)
        }
        if (secret.all { it == 0.toByte() }) throw KeyAgreementException("X25519 produced an all-zero secret")
        return SharedSecret(secret)
    }

    private companion object {
        /** SubjectPublicKeyInfo header for id-X25519 (OID 1.3.101.110) with a 32-byte key. */
        val PUBLIC_DER_PREFIX = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x6e, 0x03, 0x21, 0x00)

        /** PKCS#8 PrivateKeyInfo header for id-X25519 wrapping a 32-byte OCTET STRING. */
        val PRIVATE_DER_PREFIX = byteArrayOf(
            0x30, 0x2e, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x6e, 0x04, 0x22, 0x04, 0x20,
        )

        /** The X25519 base point u = 9. */
        val BASE_POINT = X25519PublicKey(ByteArray(X25519PublicKey.SIZE).also { it[0] = 9 })
    }
}
