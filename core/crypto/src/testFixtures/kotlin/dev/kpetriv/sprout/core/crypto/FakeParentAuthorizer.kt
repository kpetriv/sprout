package dev.kpetriv.sprout.core.crypto

import java.security.InvalidKeyException
import java.security.InvalidParameterException
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.SignatureException
import java.security.spec.ECGenParameterSpec

/**
 * Software [ParentAuthorizer] for JVM tests of the parent-signature flow. Test fixture only; production binds
 * [KeystoreParentAuthorizer].
 *
 * It models the Keystore key's behaviour without any prompt: [initSignature] returns a [Signature] that signs
 * exactly once (a per-use key needs a new prompt per event), [invalidateKey] simulates a biometric enrolment
 * change, and [availability] gates key creation as on a device. Like the Keystore authorizer it never creates a
 * key lazily: only [createParentKey] does. Verify its output with [verifyParentSignature].
 *
 * Attestation: a key created with a challenge gets a two-entry stand-in chain ([FAKE_LEAF_PREFIX] + challenge, then
 * [FAKE_ROOT]) while [canAttest] is true, an empty one otherwise (a Keystore that refuses the challenge), so callers
 * can check which challenge the key was bound to. The entries are not certificates.
 */
class FakeParentAuthorizer(
    @Volatile var availability: ParentAuthAvailability = ParentAuthAvailability.STRONG_BIOMETRIC_AVAILABLE,
    private val random: SecureRandom = SecureRandom(),
) : ParentAuthorizer {
    @Volatile
    private var keyPair: KeyPair? = null

    @Volatile
    private var invalidated = false

    /** Number of signatures handed out by [initSignature], i.e. the prompts the feature layer would have shown. */
    @Volatile
    var signaturesIssued: Int = 0
        private set

    /** Number of keys [createParentKey] generated. */
    @Volatile
    var keysCreated: Int = 0
        private set

    /** Whether a challenge passed to [createParentKey] yields a chain (false: Keystore without attestation). */
    @Volatile
    var canAttest: Boolean = true

    /** The challenge the current key was created with, or null. */
    @Volatile
    var keyChallenge: ByteArray? = null
        private set

    @Volatile
    private var chain: List<ByteArray> = emptyList()

    override fun availability(): ParentAuthAvailability = availability

    override fun existingParentPublicKey(): PublicKeyBytes? = keyPair?.let { PublicKeyBytes(it.public.encoded) }

    override suspend fun createParentKey(attestationChallenge: ByteArray?): PublicKeyBytes =
        loadOrCreate(attestationChallenge?.copyOf())

    override fun parentKeyAttestationChain(): List<ByteArray> = chain.map { it.copyOf() }

    @Synchronized
    private fun loadOrCreate(challenge: ByteArray?): PublicKeyBytes {
        keyPair?.let { return PublicKeyBytes(it.public.encoded) }
        if (availability != ParentAuthAvailability.STRONG_BIOMETRIC_AVAILABLE) {
            throw ParentAuthUnavailableException(availability)
        }
        val pair = KeyPairGenerator.getInstance(CryptoAlgorithms.EC).run {
            initialize(ECGenParameterSpec(CryptoAlgorithms.EC_CURVE), random)
            generateKeyPair()
        }
        keyPair = pair
        keysCreated++
        keyChallenge = challenge
        chain = if (challenge != null && canAttest) listOf(FAKE_LEAF_PREFIX + challenge, FAKE_ROOT) else emptyList()
        return PublicKeyBytes(pair.public.encoded)
    }

    @Synchronized
    override fun initSignature(): Signature {
        if (invalidated) throw ParentKeyInvalidatedException()
        val pair = checkNotNull(keyPair) { "Parent auth key has not been created" }
        signaturesIssued++
        return OneShotSignature().apply { initSign(pair.private) }
    }

    override suspend fun resetParentKey() = reset()

    @Synchronized
    private fun reset() {
        keyPair = null
        invalidated = false
        keyChallenge = null
        chain = emptyList()
    }

    /** Simulates a biometric enrolment change: [initSignature] throws until [resetParentKey]. */
    fun invalidateKey() {
        invalidated = true
    }

    companion object {
        val FAKE_LEAF_PREFIX: ByteArray = "fake-attested-leaf:".encodeToByteArray()
        val FAKE_ROOT: ByteArray = "fake-attestation-root".encodeToByteArray()
    }
}

/** SHA256withECDSA [Signature] that refuses a second [sign], like a Keystore per-use operation. */
private class OneShotSignature : Signature(CryptoAlgorithms.SIGNATURE) {
    private val delegate = getInstance(CryptoAlgorithms.SIGNATURE)
    private var used = false

    override fun engineInitSign(privateKey: PrivateKey) {
        delegate.initSign(privateKey)
    }

    override fun engineInitVerify(publicKey: PublicKey): Unit = throw InvalidKeyException("The parent key only signs")

    override fun engineUpdate(b: Byte) {
        checkUnused()
        delegate.update(b)
    }

    override fun engineUpdate(b: ByteArray, off: Int, len: Int) {
        checkUnused()
        delegate.update(b, off, len)
    }

    override fun engineSign(): ByteArray {
        checkUnused()
        used = true
        return delegate.sign()
    }

    override fun engineVerify(sigBytes: ByteArray): Boolean = throw SignatureException("The parent key only signs")

    @Deprecated("Required by SignatureSpi")
    override fun engineSetParameter(param: String?, value: Any?): Unit = throw InvalidParameterException()

    @Deprecated("Required by SignatureSpi")
    override fun engineGetParameter(param: String?): Any? = null

    private fun checkUnused() {
        if (used) throw SignatureException("Per-use parent signature already used; prompt again")
    }
}
