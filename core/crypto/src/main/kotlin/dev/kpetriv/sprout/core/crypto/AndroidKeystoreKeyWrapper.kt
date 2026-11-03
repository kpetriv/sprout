package dev.kpetriv.sprout.core.crypto

import android.content.Context
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyProperties as AndroidKeyProperties

/**
 * [KeyWrapper] using an AES-256-GCM key in Android Keystore under [KeyAliases.SECRET_WRAP]. Keystore picks
 * the 12-byte nonce (randomized encryption is enforced); output is `nonce || ciphertext || tag`.
 *
 * No user authentication: the SQLCipher passphrase must be unwrappable at cold start and by background sync.
 */
class AndroidKeystoreKeyWrapper internal constructor(private val keystore: AndroidKeystore) : KeyWrapper {
    constructor(context: Context) : this(AndroidKeystore(context))

    override fun wrap(plaintext: ByteArray, associatedData: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(CryptoAlgorithms.SYMMETRIC_CIPHER)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val nonce = cipher.iv
        check(nonce.size == AesGcmAead.NONCE_SIZE) { "Unexpected GCM nonce size" }
        cipher.updateAAD(associatedData)
        return nonce + cipher.doFinal(plaintext)
    }

    override fun unwrap(wrapped: ByteArray, associatedData: ByteArray): ByteArray {
        if (wrapped.size < AesGcmAead.NONCE_SIZE + AesGcmAead.TAG_SIZE) throw AeadException("Wrapped secret too short")
        val key = keystore.secretKey(KeyAliases.SECRET_WRAP) ?: throw AeadException("Wrapping key is missing")
        return try {
            Cipher.getInstance(CryptoAlgorithms.SYMMETRIC_CIPHER).run {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(AesGcmAead.TAG_BITS, wrapped, 0, AesGcmAead.NONCE_SIZE))
                updateAAD(associatedData)
                doFinal(wrapped, AesGcmAead.NONCE_SIZE, wrapped.size - AesGcmAead.NONCE_SIZE)
            }
        } catch (e: AEADBadTagException) {
            throw AeadException("Authentication failed", e)
        }
    }

    @Synchronized
    private fun key(): SecretKey = keystore.secretKey(KeyAliases.SECRET_WRAP) ?: keystore.generate(
        alias = KeyAliases.SECRET_WRAP,
        purposes = AndroidKeyProperties.PURPOSE_ENCRYPT or AndroidKeyProperties.PURPOSE_DECRYPT,
        configure = {
            setKeySize(KEY_SIZE_BITS)
            setBlockModes(AndroidKeyProperties.BLOCK_MODE_GCM)
            setEncryptionPaddings(AndroidKeyProperties.ENCRYPTION_PADDING_NONE)
            setRandomizedEncryptionRequired(true)
            setUserAuthenticationRequired(false)
        },
        generator = { spec ->
            KeyGenerator.getInstance(AndroidKeyProperties.KEY_ALGORITHM_AES, AndroidKeystore.PROVIDER)
                .apply { init(spec) }
                .generateKey()
        },
    )

    private companion object {
        const val KEY_SIZE_BITS = 256
    }
}
