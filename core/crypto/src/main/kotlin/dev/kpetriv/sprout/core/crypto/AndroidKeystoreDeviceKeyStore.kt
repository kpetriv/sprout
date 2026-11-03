package dev.kpetriv.sprout.core.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec
import android.security.keystore.KeyProperties as AndroidKeyProperties

/**
 * Which Keystore alias holds the active device key and which the pending registration key ([DeviceKeyStore]'s
 * two-phase registration). Keystore cannot rename an entry, so promoting a pending key means recording its alias as
 * the active one. Holds alias names only (no key material); it must be written durably before the call returns.
 */
internal interface DeviceKeySlots {
    var active: String?
    var pending: String?
}

/** [DeviceKeySlots] in a private shared-preferences file, written with `commit` (durable before returning). */
internal class SharedPreferencesDeviceKeySlots(context: Context) : DeviceKeySlots {
    private val preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override var active: String?
        get() = preferences.getString(ACTIVE, null)
        set(value) = write(ACTIVE, value)

    override var pending: String?
        get() = preferences.getString(PENDING, null)
        set(value) = write(PENDING, value)

    private fun write(key: String, value: String?) {
        check(preferences.edit().putString(key, value).commit()) { "the device key slot could not be saved" }
    }

    private companion object {
        const val FILE = "sprout_device_key"
        const val ACTIVE = "active"
        const val PENDING = "pending"
    }
}

/**
 * [DeviceKeyStore] backed by Android Keystore: EC P-256, SHA-256 digest only, StrongBox when available, TEE
 * otherwise.
 *
 * Three aliases: the provisional [KeyAliases.DEVICE_SIGNING] (v1, created on first use) and the two registration
 * slots [KeyAliases.DEVICE_SIGNING_V2] and [KeyAliases.DEVICE_SIGNING_V2_PENDING]. [regenerateForRegistration] creates
 * a key with an attestation challenge in the slot the active key does not use and records it as pending
 * ([DeviceKeySlots]); [promotePendingKey] records it as active and deletes the previous key and v1;
 * [discardPendingKey] deletes it. The active key is the recorded one, else [KeyAliases.DEVICE_SIGNING_V2] when it
 * exists (a key registered before the slots were recorded), else v1.
 *
 * The key deliberately does **not** require user authentication: ledger events and chat messages must be
 * signable by background sync and by kid devices with no parent present. Parent-only operations are gated by
 * the PIN/biometric layer (feature/lock) and by the separate [ParentAuthKeyStore] key, not by this key.
 */
class AndroidKeystoreDeviceKeyStore internal constructor(
    private val keystore: AndroidKeystore,
    private val slots: DeviceKeySlots,
    private val dispatcher: CoroutineDispatcher,
) : DeviceKeyStore {
    constructor(context: Context, dispatcher: CoroutineDispatcher) :
        this(AndroidKeystore(context), SharedPreferencesDeviceKeySlots(context), dispatcher)

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

    override suspend fun pendingSigningKey(): SigningKeyHandle? = withContext(dispatcher) { pending() }

    override suspend fun promotePendingKey(): PublicKeyBytes = withContext(dispatcher) { promote() }

    override suspend fun discardPendingKey() = withContext(dispatcher) { discard() }

    @Synchronized
    private fun regenerate(challenge: ByteArray): DeviceRegistrationMaterial {
        val alias = if (loadOrCreate().alias == KeyAliases.DEVICE_SIGNING_V2_PENDING) {
            KeyAliases.DEVICE_SIGNING_V2
        } else {
            KeyAliases.DEVICE_SIGNING_V2_PENDING
        }
        // Recorded before the key exists, so a crash mid-generation leaves a known slot to clean up, never a stray
        // key in the active slot.
        slots.pending = alias
        keystore.delete(alias)
        // Keystore without attestation support refuses the challenge: register an unattested key instead.
        val pair = generateWithAttestationFallback(challenge, cleanUp = { keystore.delete(alias) }) { attest ->
            generate(alias) { if (attest != null) setAttestationChallenge(attest) }
        }
        val handle = handleFor(pair.private, pair.public.encoded, alias)
        return DeviceRegistrationMaterial(handle.publicKey, attestationChain(alias))
    }

    @Synchronized
    @Suppress("ReturnCount") // No pending slot, the slot is the active one, or the key.
    private fun pending(): SigningKeyHandle? {
        val alias = slots.pending ?: return null
        if (alias == loadOrCreate().alias) return null
        return existing(alias)
    }

    @Synchronized
    private fun promote(): PublicKeyBytes {
        val handle = checkNotNull(pending()) { "no pending device key" }
        val previous = loadOrCreate().alias
        slots.active = handle.alias
        slots.pending = null
        cached = handle
        if (previous != null && previous != handle.alias) keystore.delete(previous)
        keystore.delete(KeyAliases.DEVICE_SIGNING)
        return handle.publicKey
    }

    @Synchronized
    private fun discard() {
        val alias = slots.pending ?: return
        if (alias != loadOrCreate().alias) keystore.delete(alias)
        slots.pending = null
    }

    /** The attestation chain of [alias], leaf first; empty when Keystore only holds a self-signed certificate. */
    private fun attestationChain(alias: String): List<ByteArray> =
        attestationChainOf(keystore.keyStore.getCertificateChain(alias))

    @Synchronized
    private fun loadOrCreate(): SigningKeyHandle {
        cached?.let { return it }
        val handle = slots.active?.let(::existing)
            ?: existing(KeyAliases.DEVICE_SIGNING_V2)?.takeIf { slots.pending != KeyAliases.DEVICE_SIGNING_V2 }
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
