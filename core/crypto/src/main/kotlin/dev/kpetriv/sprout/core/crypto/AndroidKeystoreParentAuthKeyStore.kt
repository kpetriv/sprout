package dev.kpetriv.sprout.core.crypto

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.security.GeneralSecurityException
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.ProviderException
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import android.security.keystore.KeyProperties as AndroidKeyProperties

/**
 * [ParentAuthKeyStore] backed by Android Keystore: EC P-256 under [KeyAliases.PARENT_AUTH], SHA-256 only,
 * StrongBox first and TEE otherwise (ADR-0004 section 1).
 *
 * Key policy:
 * - `setUserAuthenticationRequired(true)` and authorisation **per use**: every signature needs its own
 *   `BiometricPrompt` bound to that operation through a `CryptoObject`. There is no validity window.
 * - API 30+: `setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG)`, so only a Class 3 biometric authorises
 *   the key; the device credential (screen-lock PIN, pattern, password) cannot.
 * - API 28-29: `setUserAuthenticationParameters` does not exist. The key uses
 *   `setUserAuthenticationValidityDurationSeconds(-1)` (per use), and the spec has no way to name the allowed
 *   authenticators, so this code cannot exclude the device credential by policy there. In AOSP Keystore of those
 *   releases a per-use key is bound to the biometric secure user ids only ("fingerprint-only" on API 28,
 *   "biometric-only" on API 29), and only a biometric that can mint Keystore auth tokens (Class 3) can authorise
 *   a `CryptoObject`; that is platform behaviour, not something this module enforces or has verified on those
 *   API levels.
 * - `setInvalidatedByBiometricEnrollment(true)`: adding or removing a biometric permanently invalidates the key.
 *
 * Keystore refuses to create a per-use biometric key while no biometric is enrolled; [ensureKey] reports that as
 * [ParentAuthUnavailableException] and deletes whatever partial entry the attempt left.
 *
 * **Attestation.** With a challenge (the server's `beginDeviceRegistration`, for `registerParentKey`, D-M3-34) the key
 * is generated with `setAttestationChallenge(challenge)` and [attestationChain] returns Keystore's chain for it, so the
 * server can verify that the key lives in a TEE or StrongBox and needs a biometric per use. A Keystore that cannot
 * attest refuses the challenge with a [java.security.ProviderException] or [java.security.KeyStoreException]; the key
 * is then created unattested, exactly as [AndroidKeystoreDeviceKeyStore.regenerateForRegistration] does, and
 * [attestationChain] is empty. Every user-authentication property above applies either way.
 */
class AndroidKeystoreParentAuthKeyStore internal constructor(
    private val keystore: AndroidKeystore,
    private val dispatcher: CoroutineDispatcher,
) : ParentAuthKeyStore {
    constructor(context: Context, dispatcher: CoroutineDispatcher) : this(AndroidKeystore(context), dispatcher)

    override suspend fun ensureKey(attestationChallenge: ByteArray?): PublicKeyBytes {
        require(attestationChallenge == null || attestationChallenge.isNotEmpty()) {
            "the attestation challenge must not be empty"
        }
        val challenge = attestationChallenge?.copyOf()
        return withContext(dispatcher) { loadOrCreate(challenge) }
    }

    override fun attestationChain(): List<ByteArray> = attestationChainOf(keystore.keyStore.getCertificateChain(ALIAS))

    override fun publicKey(): PublicKeyBytes? = keystore.keyStore.getCertificate(ALIAS)?.let {
        PublicKeyBytes(it.publicKey.encoded)
    }

    override fun keyProperties(): DeviceKeyProperties? =
        keystore.privateKey(ALIAS)?.let { keystore.properties(ALIAS, it) }

    override fun initSignature(): Signature {
        val key = checkNotNull(keystore.privateKey(ALIAS)) { "Parent auth key has not been created" }
        return try {
            Signature.getInstance(CryptoAlgorithms.SIGNATURE).apply { initSign(key) }
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw ParentKeyInvalidatedException(e)
        }
    }

    override suspend fun deleteKey() = withContext(dispatcher) { delete() }

    @Synchronized
    private fun delete() = keystore.delete(ALIAS)

    @Synchronized
    private fun loadOrCreate(challenge: ByteArray?): PublicKeyBytes {
        publicKey()?.let { return it }
        val pair = runCatching { generateKeyPair(challenge) }.getOrElse { failure ->
            // Keystore 1 refuses with InvalidAlgorithmParameterException when no biometric is enrolled; Keystore 2
            // reports it from generateKeyPair as a ProviderException (after the StrongBox fallback also failed).
            if (failure.isKeyCreationRefusal()) throw unavailable(failure) else throw failure
        }
        return PublicKeyBytes(pair.public.encoded)
    }

    /** Attested when [challenge] is set and Keystore can attest, unattested otherwise (class comment). */
    private fun generateKeyPair(challenge: ByteArray?): KeyPair =
        generateWithAttestationFallback(challenge, cleanUp = { keystore.delete(ALIAS) }) { attest ->
            keystore.generate(
                alias = ALIAS,
                purposes = AndroidKeyProperties.PURPOSE_SIGN or AndroidKeyProperties.PURPOSE_VERIFY,
                configure = { configureParentKey(attest) },
                generator = { spec ->
                    KeyPairGenerator.getInstance(AndroidKeyProperties.KEY_ALGORITHM_EC, AndroidKeystore.PROVIDER)
                        .apply { initialize(spec) }
                        .generateKeyPair()
                },
            )
        }

    private fun Throwable.isKeyCreationRefusal(): Boolean =
        this is GeneralSecurityException || this is ProviderException || this is IllegalStateException

    private fun unavailable(cause: Throwable): ParentAuthUnavailableException {
        try {
            keystore.delete(ALIAS)
        } catch (e: GeneralSecurityException) {
            cause.addSuppressed(e)
        }
        return ParentAuthUnavailableException(ParentAuthAvailability.UNAVAILABLE_OTHER, cause)
    }

    companion object {
        /**
         * The parent key's spec: EC P-256, SHA-256, per-use strong-biometric authentication, invalidated by enrolment
         * changes, plus `setAttestationChallenge` when [challenge] is set. Internal for the Robolectric spec test.
         */
        internal fun KeyGenParameterSpec.Builder.configureParentKey(challenge: ByteArray?) {
            setAlgorithmParameterSpec(ECGenParameterSpec(CryptoAlgorithms.EC_CURVE))
            setDigests(AndroidKeyProperties.DIGEST_SHA256)
            setUserAuthenticationRequired(true)
            setInvalidatedByBiometricEnrollment(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setUserAuthenticationParameters(PER_USE_TIMEOUT_SECONDS, AndroidKeyProperties.AUTH_BIOMETRIC_STRONG)
            } else {
                // Per use. See the class comment for what API 28-29 can and cannot express.
                @Suppress("DEPRECATION")
                setUserAuthenticationValidityDurationSeconds(LEGACY_PER_USE_VALIDITY_SECONDS)
            }
            if (challenge != null) setAttestationChallenge(challenge)
        }

        /** `setUserAuthenticationParameters` timeout meaning "authorise each operation separately" (API 30+). */
        const val PER_USE_TIMEOUT_SECONDS = 0

        /** `setUserAuthenticationValidityDurationSeconds` value meaning "every use" (API 28-29). */
        const val LEGACY_PER_USE_VALIDITY_SECONDS = -1
        private const val ALIAS = KeyAliases.PARENT_AUTH
    }
}
