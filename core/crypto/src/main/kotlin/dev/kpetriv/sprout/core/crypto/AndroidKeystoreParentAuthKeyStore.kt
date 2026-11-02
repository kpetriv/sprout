package dev.kpetriv.sprout.core.crypto

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import android.security.keystore.KeyProperties as AndroidKeyProperties

/**
 * [ParentAuthKeyStore] backed by Android Keystore: EC P-256 under [KeyAliases.PARENT_AUTH] that requires a
 * strong biometric or the device credential within the last [AUTH_VALIDITY_SECONDS] seconds, and is invalidated
 * when biometric enrolment changes.
 */
class AndroidKeystoreParentAuthKeyStore internal constructor(
    private val context: Context,
    private val keystore: AndroidKeystore,
    private val dispatcher: CoroutineDispatcher,
) : ParentAuthKeyStore {
    constructor(context: Context, dispatcher: CoroutineDispatcher) :
        this(context, AndroidKeystore(context), dispatcher)

    override fun isAvailable(): Boolean = context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    override suspend fun ensureKey(): PublicKeyBytes = withContext(dispatcher) { loadOrCreate() }

    override fun publicKey(): PublicKeyBytes? = keystore.keyStore.getCertificate(ALIAS)?.let {
        PublicKeyBytes(it.publicKey.encoded)
    }

    override fun keyProperties(): DeviceKeyProperties? =
        keystore.privateKey(ALIAS)?.let { keystore.properties(ALIAS, it) }

    override fun initSignature(): Signature {
        val key = checkNotNull(keystore.privateKey(ALIAS)) { "Parent auth key has not been created" }
        return Signature.getInstance(CryptoAlgorithms.SIGNATURE).apply { initSign(key) }
    }

    override suspend fun deleteKey() = withContext(dispatcher) { delete() }

    @Synchronized
    private fun delete() = keystore.delete(ALIAS)

    @Synchronized
    private fun loadOrCreate(): PublicKeyBytes {
        publicKey()?.let { return it }
        check(isAvailable()) { "A secure lock screen is required for the parent key" }
        val pair = keystore.generate(
            alias = ALIAS,
            purposes = AndroidKeyProperties.PURPOSE_SIGN or AndroidKeyProperties.PURPOSE_VERIFY,
            configure = {
                setAlgorithmParameterSpec(ECGenParameterSpec(CryptoAlgorithms.EC_CURVE))
                setDigests(AndroidKeyProperties.DIGEST_SHA256)
                // TODO(M2, ADR-0004): move to timeout 0 with a per-use BiometricPrompt.CryptoObject and
                //  AUTH_BIOMETRIC_STRONG only, so each parent operation needs its own strong biometric (T7, T16).
                setUserAuthenticationRequired(true)
                setInvalidatedByBiometricEnrollment(true)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setUserAuthenticationParameters(
                        AUTH_VALIDITY_SECONDS,
                        AndroidKeyProperties.AUTH_BIOMETRIC_STRONG or AndroidKeyProperties.AUTH_DEVICE_CREDENTIAL,
                    )
                } else {
                    @Suppress("DEPRECATION")
                    setUserAuthenticationValidityDurationSeconds(AUTH_VALIDITY_SECONDS)
                }
            },
            generator = { spec ->
                KeyPairGenerator.getInstance(AndroidKeyProperties.KEY_ALGORITHM_EC, AndroidKeystore.PROVIDER)
                    .apply { initialize(spec) }
                    .generateKeyPair()
            },
        )
        return PublicKeyBytes(pair.public.encoded)
    }

    companion object {
        const val AUTH_VALIDITY_SECONDS = 30
        private const val ALIAS = KeyAliases.PARENT_AUTH
    }
}
