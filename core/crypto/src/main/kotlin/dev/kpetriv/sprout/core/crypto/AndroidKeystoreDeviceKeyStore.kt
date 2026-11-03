package dev.kpetriv.sprout.core.crypto

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec
import android.security.keystore.KeyProperties as AndroidKeyProperties

/**
 * [DeviceKeyStore] backed by Android Keystore: EC P-256 under [KeyAliases.DEVICE_SIGNING], SHA-256 digest only,
 * StrongBox when available, TEE otherwise.
 *
 * The key deliberately does **not** require user authentication: ledger events and chat messages must be
 * signable by background sync and by kid devices with no parent present. Parent-only operations are gated by
 * the PIN/biometric layer (feature/lock) and by the separate [ParentAuthKeyStore] key, not by this key.
 */
class AndroidKeystoreDeviceKeyStore internal constructor(
    private val keystore: AndroidKeystore,
    private val dispatcher: CoroutineDispatcher,
) : DeviceKeyStore {
    constructor(context: Context, dispatcher: CoroutineDispatcher) : this(AndroidKeystore(context), dispatcher)

    @Volatile
    private var cached: SigningKeyHandle? = null

    override suspend fun deviceSigningKey(): SigningKeyHandle = cached ?: withContext(dispatcher) { loadOrCreate() }

    override fun publicKey(): PublicKeyBytes = (cached ?: loadOrCreate()).publicKey

    override fun keyProperties(): DeviceKeyProperties {
        val handle = cached ?: loadOrCreate()
        return keystore.properties(KeyAliases.DEVICE_SIGNING, handle.privateKey)
    }

    @Synchronized
    private fun loadOrCreate(): SigningKeyHandle {
        cached?.let { return it }
        val alias = KeyAliases.DEVICE_SIGNING
        val existing = keystore.privateKey(alias)
        val handle = if (existing != null) {
            handleFor(existing, checkNotNull(keystore.keyStore.getCertificate(alias)).publicKey.encoded)
        } else {
            val pair = keystore.generate(
                alias = alias,
                purposes = AndroidKeyProperties.PURPOSE_SIGN or AndroidKeyProperties.PURPOSE_VERIFY,
                configure = {
                    setAlgorithmParameterSpec(ECGenParameterSpec(CryptoAlgorithms.EC_CURVE))
                    setDigests(AndroidKeyProperties.DIGEST_SHA256)
                    // See the class comment: background sync must be able to sign.
                    setUserAuthenticationRequired(false)
                },
                generator = { spec ->
                    KeyPairGenerator.getInstance(AndroidKeyProperties.KEY_ALGORITHM_EC, AndroidKeystore.PROVIDER)
                        .apply { initialize(spec) }
                        .generateKeyPair()
                },
            )
            handleFor(pair.private, pair.public.encoded)
        }
        cached = handle
        return handle
    }

    private fun handleFor(privateKey: PrivateKey, publicDer: ByteArray) =
        SigningKeyHandle(privateKey, PublicKeyBytes(publicDer), KeyAliases.DEVICE_SIGNING)
}
