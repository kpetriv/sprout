package dev.kpetriv.sprout.core.crypto

import java.math.BigInteger
import java.security.GeneralSecurityException
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECPublicKey

/**
 * A reference to a P-256 private key plus its public half. For Keystore keys [privateKey] is an opaque handle:
 * the key material never enters the app process.
 */
class SigningKeyHandle(
    internal val privateKey: PrivateKey,
    val publicKey: PublicKeyBytes,
    /** Android Keystore alias, or null for a software key. */
    val alias: String?,
) {
    override fun toString(): String = "SigningKeyHandle(alias=$alias, publicKey=$publicKey)"
}

/** Produces ECDSA P-256 / SHA-256 signatures. */
interface Signer {
    val publicKey: PublicKeyBytes

    /** Signs [payload] (the canonical encoding of an event or message). Returns an ASN.1 DER signature. */
    fun sign(payload: ByteArray): ByteArray
}

/** Checks ECDSA P-256 / SHA-256 signatures. Never needs Keystore, so it verifies remote devices' signatures too. */
interface Verifier {
    /**
     * True only if [signature] is a valid DER signature over [payload] by [publicKey], and [publicKey] is a P-256
     * key. Malformed keys or signatures return false rather than throwing, since both come from untrusted peers.
     *
     * ECDSA signatures are malleable (`(r, s)` and `(r, n - s)` both verify), so hash chains and identifiers
     * must be computed over the payload, never over the signature bytes.
     */
    fun verify(publicKey: PublicKeyBytes, payload: ByteArray, signature: ByteArray): Boolean
}

/** JCE ECDSA signer. Works with both software keys and Android Keystore key handles. */
class JceEcdsaSigner(private val key: SigningKeyHandle) : Signer {
    override val publicKey: PublicKeyBytes get() = key.publicKey

    override fun sign(payload: ByteArray): ByteArray = Signature.getInstance(CryptoAlgorithms.SIGNATURE).run {
        initSign(key.privateKey)
        update(payload)
        sign()
    }
}

/**
 * JCE ECDSA verifier that only accepts P-256 public keys. It never throws on untrusted input: every
 * [GeneralSecurityException] and [RuntimeException] from decoding or verification means "not valid".
 */
class JceEcdsaVerifier : Verifier {
    // Providers disagree on how they report broken input: SignatureException, InvalidKeySpecException,
    // IllegalArgumentException, ProviderException, ArrayIndexOutOfBounds from DER parsers, and so on. All of it
    // comes from untrusted peers, so all of it is "not valid"; a crash here would let a peer stop sync.
    @Suppress("TooGenericExceptionCaught")
    override fun verify(publicKey: PublicKeyBytes, payload: ByteArray, signature: ByteArray): Boolean = try {
        val key = publicKey.toPublicKey()
        isP256(key) &&
            Signature.getInstance(CryptoAlgorithms.SIGNATURE).run {
                initVerify(key)
                update(payload)
                verify(signature)
            }
    } catch (expected: GeneralSecurityException) {
        false
    } catch (expected: RuntimeException) {
        false
    }

    private fun isP256(key: PublicKey): Boolean {
        val params = (key as? ECPublicKey)?.params ?: return false
        return params.order == P256_ORDER && params.cofactor == 1 && params.curve.field.fieldSize == P256_FIELD_BITS
    }

    private companion object {
        const val P256_FIELD_BITS = 256
        val P256_ORDER = BigInteger("FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551", 16)
    }
}
