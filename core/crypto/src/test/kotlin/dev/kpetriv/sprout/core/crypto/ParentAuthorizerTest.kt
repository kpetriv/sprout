package dev.kpetriv.sprout.core.crypto

import kotlinx.coroutines.test.runTest
import java.security.Signature
import java.security.SignatureException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParentAuthorizerTest {
    private val payload = "sprout/ledger-event/v1".encodeToByteArray() + 0 + "event:42".encodeToByteArray()
    private val signer = ParentSigner()

    @Test
    fun `fake authorizer signs and the helper verifies`() = runTest {
        val authorizer = FakeParentAuthorizer()
        val publicKey = authorizer.createParentKey()
        val signature = signer.sign(authorizer.initSignature(), payload)
        assertTrue(verifyParentSignature(publicKey, payload, signature))
        assertEquals(publicKey, authorizer.createParentKey())
        assertEquals(publicKey, authorizer.existingParentPublicKey())
        assertEquals(1, authorizer.signaturesIssued)
        assertEquals(1, authorizer.keysCreated)
    }

    @Test
    fun `reading the key never creates one`() = runTest {
        val authorizer = FakeParentAuthorizer()
        assertNull(authorizer.existingParentPublicKey())
        assertFailsWith<IllegalStateException> { authorizer.initSignature() }
        assertNull(authorizer.existingParentPublicKey())
        assertEquals(0, authorizer.keysCreated)

        val keyStore = SoftwareParentAuthKeyStore()
        val keystoreAuthorizer =
            KeystoreParentAuthorizer(keyStore) { ParentAuthAvailability.STRONG_BIOMETRIC_AVAILABLE }
        assertNull(keystoreAuthorizer.existingParentPublicKey())
        assertEquals(0, keyStore.creations)
    }

    @Test
    fun `mismatch and not-registered exceptions keep the recovery categories`() {
        // Data-layer use cases map both invalidated and mismatched keys to "renew the signing key".
        assertIs<ParentKeyInvalidatedException>(ParentKeyMismatchException())
        // A missing registration is the D-M2-1 "cannot sign" fallback.
        val notRegistered: ParentAuthUnavailableException =
            ParentKeyNotRegisteredException(ParentAuthAvailability.STRONG_BIOMETRIC_AVAILABLE)
        assertEquals(ParentAuthAvailability.STRONG_BIOMETRIC_AVAILABLE, notRegistered.availability)
    }

    @Test
    fun `parent signatures fail on tampering, another key, or a device key`() = runTest {
        val authorizer = FakeParentAuthorizer()
        val publicKey = authorizer.createParentKey()
        val signature = signer.sign(authorizer.initSignature(), payload)
        val tampered = payload.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertFalse(verifyParentSignature(publicKey, tampered, signature))
        assertFalse(verifyParentSignature(FakeParentAuthorizer().createParentKey(), payload, signature))
        val deviceSignature = JceEcdsaSigner(generateSoftwareSigningKey()).sign(payload)
        assertFalse(verifyParentSignature(publicKey, payload, deviceSignature))
        assertFalse(verifyParentSignature(publicKey, payload, byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `each initialised signature signs exactly once`() = runTest {
        val authorizer = FakeParentAuthorizer()
        authorizer.createParentKey()
        val authorized = authorizer.initSignature()
        signer.sign(authorized, payload)
        assertFailsWith<SignatureException> { signer.sign(authorized, payload) }
        // A new prompt (a new signature) works again.
        signer.sign(authorizer.initSignature(), payload)
        assertEquals(2, authorizer.signaturesIssued)
    }

    @Test
    fun `no key is created without a strong biometric`() = runTest {
        for (reason in ParentAuthAvailability.entries - ParentAuthAvailability.STRONG_BIOMETRIC_AVAILABLE) {
            val authorizer = FakeParentAuthorizer(availability = reason)
            val error = assertFailsWith<ParentAuthUnavailableException> { authorizer.createParentKey() }
            assertEquals(reason, error.availability)
            assertFailsWith<IllegalStateException> { authorizer.initSignature() }
        }
    }

    @Test
    fun `an invalidated key throws until it is reset, and the new key differs`() = runTest {
        val authorizer = FakeParentAuthorizer()
        val first = authorizer.createParentKey()
        val oldSignature = signer.sign(authorizer.initSignature(), payload)
        authorizer.invalidateKey()
        assertFailsWith<ParentKeyInvalidatedException> { authorizer.initSignature() }

        authorizer.resetParentKey()
        val second = authorizer.createParentKey()
        assertNotEquals(first, second)
        assertTrue(verifyParentSignature(second, payload, signer.sign(authorizer.initSignature(), payload)))
        assertFalse(verifyParentSignature(second, payload, oldSignature))
    }

    @Test
    fun `keystore authorizer passes the attestation challenge through and exposes the chain`() = runTest {
        val keyStore = SoftwareParentAuthKeyStore()
        val authorizer = KeystoreParentAuthorizer(keyStore) { ParentAuthAvailability.STRONG_BIOMETRIC_AVAILABLE }
        assertEquals(emptyList(), authorizer.parentKeyAttestationChain())
        val challenge = byteArrayOf(7, 7, 7)

        authorizer.createParentKey(challenge)

        assertTrue(challenge.contentEquals(keyStore.lastChallenge))
        assertTrue(authorizer.parentKeyAttestationChain().first().contentEquals(challenge))
        authorizer.resetParentKey()
        authorizer.createParentKey()
        assertNull(keyStore.lastChallenge)
        assertEquals(emptyList(), authorizer.parentKeyAttestationChain())
    }

    @Test
    fun `fake authorizer binds a stand-in chain to the challenge, or none when it cannot attest`() = runTest {
        val authorizer = FakeParentAuthorizer()
        val challenge = byteArrayOf(1, 2, 3)
        authorizer.createParentKey(challenge)
        val chain = authorizer.parentKeyAttestationChain()
        assertEquals(2, chain.size)
        assertTrue(chain[0].contentEquals(FakeParentAuthorizer.FAKE_LEAF_PREFIX + challenge))

        authorizer.resetParentKey()
        authorizer.canAttest = false
        authorizer.createParentKey(challenge)
        assertEquals(emptyList(), authorizer.parentKeyAttestationChain())
        assertTrue(challenge.contentEquals(authorizer.keyChallenge))
    }

    @Test
    fun `parent signer rejects other algorithms and uninitialised signatures`() {
        assertFailsWith<IllegalArgumentException> { signer.sign(Signature.getInstance("SHA256withRSA"), payload) }
        assertFailsWith<SignatureException> { signer.sign(Signature.getInstance(CryptoAlgorithms.SIGNATURE), payload) }
    }

    @Test
    fun `keystore authorizer checks availability only before the key exists`() = runTest {
        val keyStore = SoftwareParentAuthKeyStore()
        var availability = ParentAuthAvailability.NOT_ENROLLED
        val authorizer = KeystoreParentAuthorizer(keyStore) { availability }

        val error = assertFailsWith<ParentAuthUnavailableException> { authorizer.createParentKey() }
        assertEquals(ParentAuthAvailability.NOT_ENROLLED, error.availability)
        assertEquals(0, keyStore.creations)
        assertEquals(ParentAuthAvailability.NOT_ENROLLED, authorizer.availability())

        availability = ParentAuthAvailability.STRONG_BIOMETRIC_AVAILABLE
        val publicKey = authorizer.createParentKey()
        assertEquals(1, keyStore.creations)
        assertTrue(verifyParentSignature(publicKey, payload, signer.sign(authorizer.initSignature(), payload)))

        // An existing key is still reported when biometrics become unavailable; using it is Keystore's call.
        availability = ParentAuthAvailability.UNAVAILABLE_OTHER
        assertEquals(publicKey, authorizer.createParentKey())

        authorizer.resetParentKey()
        assertNull(authorizer.existingParentPublicKey())
        assertFailsWith<ParentAuthUnavailableException> { authorizer.createParentKey() }
    }

    @Test
    fun `biometric status codes map to availability`() {
        val map = BiometricParentAuthAvailabilityChecker::availabilityFor
        assertEquals(ParentAuthAvailability.STRONG_BIOMETRIC_AVAILABLE, map(0)) // BIOMETRIC_SUCCESS
        assertEquals(ParentAuthAvailability.NOT_ENROLLED, map(11)) // BIOMETRIC_ERROR_NONE_ENROLLED
        assertEquals(ParentAuthAvailability.NO_HARDWARE, map(12)) // BIOMETRIC_ERROR_NO_HARDWARE
        // HW_UNAVAILABLE, SECURITY_UPDATE_REQUIRED, UNSUPPORTED, STATUS_UNKNOWN, and anything unexpected.
        listOf(1, 15, -2, -1, 999).forEach { assertEquals(ParentAuthAvailability.UNAVAILABLE_OTHER, map(it), "$it") }
    }

    /** [ParentAuthKeyStore] with a software key, to test [KeystoreParentAuthorizer]'s gating on the JVM. */
    private class SoftwareParentAuthKeyStore : ParentAuthKeyStore {
        private var key: SigningKeyHandle? = null
        private var chain: List<ByteArray> = emptyList()
        var creations = 0
        var lastChallenge: ByteArray? = null

        override suspend fun ensureKey(attestationChallenge: ByteArray?): PublicKeyBytes = (
            key ?: generateSoftwareSigningKey().also {
                key = it
                creations++
                lastChallenge = attestationChallenge
                chain = attestationChallenge?.let { challenge -> listOf(challenge, byteArrayOf(1)) }.orEmpty()
            }
            ).publicKey

        override fun attestationChain(): List<ByteArray> = chain

        override fun publicKey(): PublicKeyBytes? = key?.publicKey

        override fun keyProperties(): DeviceKeyProperties? = null

        override fun initSignature(): Signature = Signature.getInstance(CryptoAlgorithms.SIGNATURE).apply {
            initSign(checkNotNull(key).privateKey)
        }

        override suspend fun deleteKey() {
            key = null
            chain = emptyList()
        }
    }
}
