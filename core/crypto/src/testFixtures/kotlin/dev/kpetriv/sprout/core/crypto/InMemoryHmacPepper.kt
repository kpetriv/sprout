package dev.kpetriv.sprout.core.crypto

import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Software [PinPepper]: HMAC-SHA256 under a random key held in memory. Test fixture only; production binds
 * [KeystoreHmacPepper], whose key never leaves Keystore. Two instances have different keys, like two devices.
 */
class InMemoryHmacPepper(random: SecureRandom = SecureRandom()) : PinPepper {
    private val key = ByteArray(KEY_SIZE).also(random::nextBytes)

    override fun apply(pin: ByteArray): ByteArray = Mac.getInstance(CryptoAlgorithms.HMAC).run {
        init(SecretKeySpec(key, CryptoAlgorithms.HMAC))
        doFinal(pin)
    }

    private companion object {
        const val KEY_SIZE = 32
    }
}
