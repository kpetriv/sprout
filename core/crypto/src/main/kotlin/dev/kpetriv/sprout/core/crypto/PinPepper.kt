package dev.kpetriv.sprout.core.crypto

import android.content.Context
import java.security.GeneralSecurityException
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import android.security.keystore.KeyProperties as AndroidKeyProperties

/**
 * A device-bound pepper for the parent PIN (ADR-0004 section 3): a keyed one-way function whose key never leaves
 * this device, applied before the slow hash. A hash dumped from a rooted device then cannot be brute-forced
 * off-device, because every guess needs this device's key.
 */
interface PinPepper {
    /**
     * `HMAC-SHA256(pepperKey, pin)`, 32 bytes, creating the key on first use. Used when hashing a new PIN.
     * The caller owns both arrays and zeroes them.
     */
    fun apply(pin: ByteArray): ByteArray

    /**
     * Like [apply] but never creates the key: used when verifying an existing peppered hash. Throws
     * [PinPepperUnavailableException] if the key is gone, so a lost key reads as corrupt storage rather than as
     * "wrong PIN" (which would feed the lockout). Implementations whose key always exists may keep the default.
     */
    fun applyExisting(pin: ByteArray): ByteArray = apply(pin)
}

/** The pepper key is missing, so peppered PIN hashes on this device can no longer be verified. */
class PinPepperUnavailableException(message: String) : GeneralSecurityException(message)

/**
 * [PinPepper] with an HMAC-SHA256 key in Android Keystore under [KeyAliases.PIN_PEPPER]: 256-bit, purpose SIGN,
 * no user authentication (the PIN screen must work before any biometric), StrongBox first and TEE otherwise.
 * The HMAC runs inside Keystore; the key is non-exportable. Each call is blocking IPC, so callers run it on a
 * background dispatcher (the PIN hashers do).
 */
class KeystoreHmacPepper internal constructor(private val keystore: AndroidKeystore) : PinPepper {
    constructor(context: Context) : this(AndroidKeystore(context))

    override fun apply(pin: ByteArray): ByteArray = hmac(existingKey() ?: createKey(), pin)

    override fun applyExisting(pin: ByteArray): ByteArray =
        hmac(existingKey() ?: throw PinPepperUnavailableException("PIN pepper key is missing"), pin)

    private fun hmac(key: SecretKey, pin: ByteArray): ByteArray = Mac.getInstance(CryptoAlgorithms.HMAC).run {
        init(key)
        doFinal(pin)
    }

    private fun existingKey(): SecretKey? = keystore.secretKey(KeyAliases.PIN_PEPPER)

    @Synchronized
    private fun createKey(): SecretKey = existingKey() ?: keystore.generate(
        alias = KeyAliases.PIN_PEPPER,
        purposes = AndroidKeyProperties.PURPOSE_SIGN,
        configure = {
            setKeySize(KEY_SIZE_BITS)
            setUserAuthenticationRequired(false)
        },
        generator = { spec ->
            KeyGenerator.getInstance(AndroidKeyProperties.KEY_ALGORITHM_HMAC_SHA256, AndroidKeystore.PROVIDER)
                .apply { init(spec) }
                .generateKey()
        },
    )

    private companion object {
        const val KEY_SIZE_BITS = 256
    }
}
