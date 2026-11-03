package dev.kpetriv.sprout.core.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStoreException
import java.security.PrivateKey
import java.security.ProviderException
import java.security.spec.ECGenParameterSpec
import android.security.keystore.KeyProperties as AndroidKeyProperties

/**
 * [DeviceKeyStore] backed by Android Keystore: EC P-256, SHA-256 digest only, StrongBox when available, TEE
 * otherwise.
 *
 * Two aliases: the provisional [KeyAliases.DEVICE_SIGNING] (v1, created on first use) and the registered
 * [KeyAliases.DEVICE_SIGNING_V2] (created by [regenerateForRegistration] with an attestation challenge). The v2 key
 * wins whenever it exists; v1 is deleted once v2 has been created.
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
        return keystore.properties(checkNotNull(handle.alias), handle.privateKey)
    }

    override suspend fun regenerateForRegistration(challenge: ByteArray): DeviceRegistrationMaterial {
        require(challenge.isNotEmpty()) { "the attestation challenge must not be empty" }
        return withContext(dispatcher) { regenerate(challenge.copyOf()) }
    }

    @Synchronized
    private fun regenerate(challenge: ByteArray): DeviceRegistrationMaterial {
        val alias = KeyAliases.DEVICE_SIGNING_V2
        val pair = try {
            generate(alias) { setAttestationChallenge(challenge) }
        } catch (e: ProviderException) {
            // Keystore without attestation support refuses the challenge: register an unattested key instead.
            generateUnattested(alias, e)
        } catch (e: KeyStoreException) {
            generateUnattested(alias, e)
        }
        val handle = handleFor(pair.private, pair.public.encoded, alias)
        cached = handle
        // Only now, with the v2 key in place, is the provisional key dropped.
        keystore.delete(KeyAliases.DEVICE_SIGNING)
        return DeviceRegistrationMaterial(handle.publicKey, attestationChain(alias))
    }

    private fun generateUnattested(alias: String, cause: Exception): KeyPair = try {
        generate(alias) {}
    } catch (e: ProviderException) {
        e.addSuppressed(cause)
        throw e
    }

    /** The attestation chain of [alias], leaf first; empty when Keystore only holds a self-signed certificate. */
    private fun attestationChain(alias: String): List<ByteArray> {
        val chain = keystore.keyStore.getCertificateChain(alias).orEmpty()
        return if (chain.size < 2) emptyList() else chain.map { it.encoded }
    }

    @Synchronized
    private fun loadOrCreate(): SigningKeyHandle {
        cached?.let { return it }
        val handle = existing(KeyAliases.DEVICE_SIGNING_V2)
            ?: existing(KeyAliases.DEVICE_SIGNING)
            ?: generate(KeyAliases.DEVICE_SIGNING) {}.let {
                handleFor(it.private, it.public.encoded, KeyAliases.DEVICE_SIGNING)
            }
        cached = handle
        return handle
    }

    private fun existing(alias: String): SigningKeyHandle? = keystore.privateKey(alias)?.let { key ->
        handleFor(key, checkNotNull(keystore.keyStore.getCertificate(alias)).publicKey.encoded, alias)
    }

    /** EC P-256 under [alias] with the device key properties, plus whatever [extra] adds. */
    private fun generate(alias: String, extra: KeyGenParameterSpec.Builder.() -> Unit): KeyPair = keystore.generate(
        alias = alias,
        purposes = AndroidKeyProperties.PURPOSE_SIGN or AndroidKeyProperties.PURPOSE_VERIFY,
        configure = {
            setAlgorithmParameterSpec(ECGenParameterSpec(CryptoAlgorithms.EC_CURVE))
            setDigests(AndroidKeyProperties.DIGEST_SHA256)
            // See the class comment: background sync must be able to sign.
            setUserAuthenticationRequired(false)
            extra()
        },
        generator = { spec ->
            KeyPairGenerator.getInstance(AndroidKeyProperties.KEY_ALGORITHM_EC, AndroidKeystore.PROVIDER)
                .apply { initialize(spec) }
                .generateKeyPair()
        },
    )

    private fun handleFor(privateKey: PrivateKey, publicDer: ByteArray, alias: String) =
        SigningKeyHandle(privateKey, PublicKeyBytes(publicDer), alias)
}
