package dev.kpetriv.sprout.core.sync

import dev.kpetriv.sprout.core.domainledger.SignatureCheck
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec

/** A software P-256 device key for tests, standing in for the Keystore key of core/crypto. */
class SoftwareDeviceKey {
    private val keyPair: KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    /** X.509 SPKI DER, as registered in the device registry. */
    val publicKey: ByteArray get() = keyPair.public.encoded

    /** SHA256withECDSA (DER) over [payload]. */
    fun sign(payload: ByteArray): ByteArray = Signature.getInstance(ALGORITHM).run {
        initSign(keyPair.private)
        update(payload)
        sign()
    }

    companion object {
        const val ALGORITHM: String = "SHA256withECDSA"
    }
}

/** [SignatureCheck] with `java.security`: false, never an exception, on any malformed input. */
object SoftwareSignatureCheck : SignatureCheck {
    override fun verify(publicKey: ByteArray, payload: ByteArray, signature: ByteArray): Boolean = try {
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicKey))
        Signature.getInstance(SoftwareDeviceKey.ALGORITHM).run {
            initVerify(key)
            update(payload)
            verify(signature)
        }
    } catch (expected: GeneralSecurityException) {
        false
    } catch (expected: IllegalArgumentException) {
        false
    }
}
