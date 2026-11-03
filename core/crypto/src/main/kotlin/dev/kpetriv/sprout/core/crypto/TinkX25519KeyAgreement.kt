package dev.kpetriv.sprout.core.crypto

import com.google.crypto.tink.subtle.X25519
import java.security.GeneralSecurityException
import java.security.SecureRandom

/**
 * X25519 through Tink's pure-Java implementation (`com.google.crypto.tink.subtle.X25519`, `tink-android`), the
 * fallback for API 28-32 where JCE `XDH` is not reliably available (D-M4-1). Same raw 32-byte key format and the same
 * behaviour as [JceX25519KeyAgreement]: the scalar is clamped inside the scalar multiplication, the peer key's top bit
 * is ignored, and an all-zero shared secret (or a low-order point Tink refuses) is a [KeyAgreementException].
 */
class TinkX25519KeyAgreement(private val random: SecureRandom) : X25519KeyAgreement {
    override fun generateKeyPair(): X25519KeyPair {
        val privateKey = X25519PrivateKey.adopt(ByteArray(X25519PrivateKey.SIZE).also(random::nextBytes))
        return X25519KeyPair(privateKey, publicKeyOf(privateKey))
    }

    override fun publicKeyOf(privateKey: X25519PrivateKey): X25519PublicKey =
        sharedSecret(privateKey, BASE_POINT).use { X25519PublicKey(it.copyBytes()) }

    override fun sharedSecret(privateKey: X25519PrivateKey, peerPublicKey: X25519PublicKey): SharedSecret {
        val secret = try {
            privateKey.withMaterial { X25519.computeSharedSecret(it, peerPublicKey.bytes) }
        } catch (e: GeneralSecurityException) {
            // Tink refuses known low-order points with InvalidKeyException.
            throw KeyAgreementException("X25519 agreement failed", e)
        } catch (e: IllegalStateException) {
            throw KeyAgreementException("X25519 agreement failed", e)
        }
        if (secret.all { it == 0.toByte() }) throw KeyAgreementException("X25519 produced an all-zero secret")
        return SharedSecret(secret)
    }

    private companion object {
        /** The X25519 base point u = 9. */
        val BASE_POINT = X25519PublicKey(ByteArray(X25519PublicKey.SIZE).also { it[0] = 9 })
    }
}

/** Chooses the [X25519KeyAgreement] for this device (D-M4-1). */
object X25519KeyAgreements {
    /** First API level whose platform JCE (Conscrypt) provides X25519 `XDH` reliably. */
    const val JCE_XDH_MIN_SDK: Int = 33

    /**
     * [JceX25519KeyAgreement] when [sdkInt] is at least [JCE_XDH_MIN_SDK] and [jceWorks] (by default a run of the
     * RFC 7748 §6.1 vector through JCE) says the platform's `XDH` gives the right answer; [TinkX25519KeyAgreement]
     * otherwise. Both produce identical results, so a device may switch implementations (an OS update) without
     * losing its stored key.
     */
    fun select(random: SecureRandom, sdkInt: Int, jceWorks: () -> Boolean = ::jceSelfTest): X25519KeyAgreement =
        if (sdkInt >= JCE_XDH_MIN_SDK && jceWorks()) {
            JceX25519KeyAgreement(random)
        } else {
            TinkX25519KeyAgreement(random)
        }

    /** True when JCE `XDH` exists and computes the RFC 7748 §6.1 shared secret; false on any failure. */
    // Any provider failure, including NoSuchAlgorithmException or a RuntimeException from a broken provider, means
    // "do not use JCE".
    @Suppress("TooGenericExceptionCaught")
    fun jceSelfTest(): Boolean = try {
        val agreement = JceX25519KeyAgreement(SecureRandom())
        X25519PrivateKey.copyOf(RFC_ALICE_PRIVATE.decodeHex()).use { alice ->
            agreement.sharedSecret(alice, X25519PublicKey(RFC_BOB_PUBLIC.decodeHex())).use {
                it.copyBytes().contentEquals(RFC_SHARED.decodeHex())
            }
        }
    } catch (expected: Exception) {
        false
    }

    private const val RFC_ALICE_PRIVATE = "77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a"
    private const val RFC_BOB_PUBLIC = "de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f"
    private const val RFC_SHARED = "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742"
    private const val HEX_RADIX = 16

    private fun String.decodeHex(): ByteArray =
        ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(HEX_RADIX).toByte() }
}
