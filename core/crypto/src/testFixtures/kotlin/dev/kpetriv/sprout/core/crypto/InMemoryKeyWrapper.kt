package dev.kpetriv.sprout.core.crypto

import java.security.SecureRandom

/**
 * Software [KeyWrapper] holding its AES-256-GCM key in memory. Test fixture only; production binds
 * [AndroidKeystoreKeyWrapper], which keeps the key in Keystore.
 */
class InMemoryKeyWrapper(random: SecureRandom = SecureRandom(), private val aead: Aead = AesGcmAead(random)) :
    KeyWrapper {
    private val key = SymmetricKey.generate(random)

    override fun wrap(plaintext: ByteArray, associatedData: ByteArray): ByteArray =
        aead.encrypt(key, plaintext, associatedData)

    override fun unwrap(wrapped: ByteArray, associatedData: ByteArray): ByteArray =
        aead.decrypt(key, wrapped, associatedData)
}
