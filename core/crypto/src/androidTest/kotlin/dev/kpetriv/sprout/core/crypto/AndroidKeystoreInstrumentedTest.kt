package dev.kpetriv.sprout.core.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lambdapioneer.argon2kt.Argon2Kt
import com.lambdapioneer.argon2kt.Argon2Mode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Exercises the Keystore- and JNI-backed implementations that JVM unit tests cannot load. */
@RunWith(AndroidJUnit4::class)
class AndroidKeystoreInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val random = SecureRandom()

    @Test
    fun deviceKeySignsAndJceVerifies() = runTest {
        val store = AndroidKeystoreDeviceKeyStore(context, Dispatchers.IO)
        val handle = store.deviceSigningKey()
        assertEquals(KeyAliases.DEVICE_SIGNING, handle.alias)
        val payload = "event".encodeToByteArray()
        val signature = JceEcdsaSigner(handle).sign(payload)
        assertTrue(JceEcdsaVerifier().verify(store.publicKey(), payload, signature))
        assertFalse(JceEcdsaVerifier().verify(store.publicKey(), "other".encodeToByteArray(), signature))
        // Same key on a second lookup, from a fresh instance.
        assertEquals(store.publicKey(), AndroidKeystoreDeviceKeyStore(context, Dispatchers.IO).publicKey())
        store.keyProperties()
    }

    @Test
    fun registrationKeyReplacesTheProvisionalKeyAndCarriesTheChallenge() = runTest {
        val store = AndroidKeystoreDeviceKeyStore(context, Dispatchers.IO)
        val provisional = store.publicKey()
        val challenge = ByteArray(32).also(random::nextBytes)

        val material = store.regenerateForRegistration(challenge)
        assertEquals("pending until promoted", provisional, store.publicKey())
        store.promotePendingKey()

        assertFalse("a fresh key", material.publicKey == provisional)
        assertEquals(material.publicKey, store.publicKey())
        val handle = store.deviceSigningKey()
        assertTrue(handle.alias == KeyAliases.DEVICE_SIGNING_V2 || handle.alias == KeyAliases.DEVICE_SIGNING_V2_PENDING)
        val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertFalse("v1 is deleted once a v2 key is active", keyStore.containsAlias(KeyAliases.DEVICE_SIGNING))
        val payload = "document".encodeToByteArray()
        assertTrue(JceEcdsaVerifier().verify(material.publicKey, payload, JceEcdsaSigner(handle).sign(payload)))
        // A fresh instance (next process start) picks the v2 key.
        assertEquals(material.publicKey, AndroidKeystoreDeviceKeyStore(context, Dispatchers.IO).publicKey())
        val chain = material.attestationChain
        if (chain.isNotEmpty()) {
            val leaf = java.security.cert.CertificateFactory.getInstance("X.509")
                .generateCertificate(chain.first().inputStream())
            assertArrayEquals(material.publicKey.encoded, leaf.publicKey.encoded)
            assertTrue("a chain up to an attestation root", chain.size >= 2)
        }
        store.keyProperties()
        keyStore.deleteEntry(KeyAliases.DEVICE_SIGNING_V2)
    }

    @Test
    fun wrappedSecretRoundTripsThroughKeystoreAndFiles() = runTest {
        val store = WrappedSecretStore(
            AndroidKeystoreKeyWrapper(context),
            FilesDirBlobStorage(context, Dispatchers.IO),
            random,
            Dispatchers.IO,
        )
        val first = store.getOrCreate("instrumented.test").use { it.copyBytes() }
        val second = store.getOrCreate("instrumented.test").use { it.copyBytes() }
        assertArrayEquals(first, second)
        FilesDirBlobStorage(context, Dispatchers.IO).delete("instrumented.test")
    }

    @Test
    fun argon2idHashesPepperedAndRefusesRetiredHashes() = runTest {
        val pepper = KeystoreHmacPepper(context)
        val hasher = Argon2idPinHasher(random, Dispatchers.Default, pepper)
        val encoded = hasher.hash("4821".toCharArray())
        assertTrue(encoded.startsWith("v2\$argon2id-pepper\$m=65536,t=3,p=1\$"))
        assertTrue(hasher.verify("4821".toCharArray(), encoded))
        assertFalse(hasher.verify("4822".toCharArray(), encoded))
        assertFalse(hasher.needsRehash(encoded))

        // Retired M1 hashes (unpeppered Argon2id and PBKDF2) are refused, never checked against the PIN.
        for (retired in listOf(retiredArgon2id("4821"), retiredPbkdf2("4821"))) {
            assertThrows(UnsupportedPinHashException::class.java) {
                runBlocking { hasher.verify("4821".toCharArray(), retired) }
            }
            assertTrue(hasher.needsRehash(retired))
        }

        val peppered = Pbkdf2PinHasher(random, Dispatchers.Default, pepper, iterations = 1_000)
            .hash("4821".toCharArray())
        assertTrue(hasher.verify("4821".toCharArray(), peppered))
        assertTrue(hasher.needsRehash(peppered))
    }

    @Test
    fun keystorePepperRoundTrips() {
        val pepper = KeystoreHmacPepper(context)
        val pin = "4821".encodeToByteArray()
        val first = pepper.apply(pin)
        assertEquals(32, first.size)
        assertArrayEquals(first, pepper.apply(pin))
        assertArrayEquals(first, pepper.applyExisting(pin))
        // Same Keystore key from a fresh instance; a different input gives a different output.
        assertArrayEquals(first, KeystoreHmacPepper(context).applyExisting(pin))
        assertFalse(first.contentEquals(pepper.apply("4822".encodeToByteArray())))
        // It is a keyed HMAC, not a plain hash of the PIN.
        assertFalse(first.contentEquals(MessageDigest.getInstance("SHA-256").digest(pin)))
    }

    @Test
    fun parentKeyCreationWithoutStrongBiometricFailsGracefully() = runTest {
        val checker = BiometricParentAuthAvailabilityChecker(context)
        val availability = checker.availability()
        assumeTrue(
            "Needs a device without an enrolled strong biometric",
            availability != ParentAuthAvailability.STRONG_BIOMETRIC_AVAILABLE,
        )
        val keyStore = AndroidKeystoreParentAuthKeyStore(context, Dispatchers.IO)
        keyStore.deleteKey()
        val authorizer = KeystoreParentAuthorizer(keyStore, checker)

        val gated = assertThrows(ParentAuthUnavailableException::class.java) {
            runBlocking { authorizer.createParentKey() }
        }
        assertEquals(availability, gated.availability)
        assertNull(authorizer.existingParentPublicKey())

        // Bypassing the availability check, Keystore itself refuses; that surfaces as the same typed exception,
        // never a crash, and leaves no partial key behind.
        assertThrows(ParentAuthUnavailableException::class.java) { runBlocking { keyStore.ensureKey() } }
        assertNull(keyStore.publicKey())
        assertNull(keyStore.keyProperties())
        assertThrows(IllegalStateException::class.java) { authorizer.initSignature() }
    }

    @Test
    fun parentKeyWithStrongBiometricIsPerUse() = runTest {
        val checker = BiometricParentAuthAvailabilityChecker(context)
        assumeTrue(
            "Needs an enrolled strong biometric",
            checker.availability() == ParentAuthAvailability.STRONG_BIOMETRIC_AVAILABLE,
        )
        val keyStore = AndroidKeystoreParentAuthKeyStore(context, Dispatchers.IO)
        val authorizer = KeystoreParentAuthorizer(keyStore, checker)
        try {
            val publicKey = authorizer.createParentKey()
            assertEquals(publicKey, keyStore.publicKey())
            assertEquals(publicKey, authorizer.existingParentPublicKey())
            // Emulators back Keystore in software, so only presence is checked here.
            checkNotNull(keyStore.keyProperties())
            // Without a BiometricPrompt bound to this operation, Keystore must refuse to sign.
            val unauthorised = authorizer.initSignature()
            val outcome = runCatching { ParentSigner().sign(unauthorised, "event".encodeToByteArray()) }
            assertTrue("Signed without authentication", outcome.isFailure)
        } finally {
            authorizer.resetParentKey()
        }
    }

    @Test
    fun lockoutStateRoundTripsThroughKeystoreAndFiles() = runTest {
        val storage = FilesDirBlobStorage(context, Dispatchers.IO)
        val secrets = WrappedSecretStore(AndroidKeystoreKeyWrapper(context), storage, random, Dispatchers.IO)
        val store = EncryptedLockoutStateStore(secrets, storage, AesGcmAead(random))
        val bootClock = AndroidBootClock()
        val bootId = bootClock.bootId()
        assertTrue(bootId.isNotEmpty())
        assertEquals(bootId, AndroidBootClock().bootId())

        val previous = store.read()
        try {
            val state = PinLockoutState(5, bootClock.elapsedRealtime(), bootId)
            store.write(state)
            assertEquals(state, EncryptedLockoutStateStore(secrets, storage, AesGcmAead(random)).read())
        } finally {
            if (previous != null) store.write(previous) else storage.delete(EncryptedLockoutStateStore.STATE_BLOB)
        }
    }

    /** The retired M1 format: Argon2id over the PIN itself, no pepper. */
    private fun retiredArgon2id(pin: String): String {
        val salt = ByteArray(16).also(random::nextBytes)
        val password = ByteBuffer.allocateDirect(pin.length).put(pin.encodeToByteArray()).also { it.flip() }
        val saltBuffer = ByteBuffer.allocateDirect(salt.size).put(salt).also { it.flip() }
        val hash = Argon2Kt().hash(
            mode = Argon2Mode.ARGON2_ID,
            password = password,
            salt = saltBuffer,
            tCostInIterations = 3,
            mCostInKibibyte = 65536,
            parallelism = 1,
            hashLengthInBytes = 32,
        ).rawHashAsByteArray()
        return retiredEncoding("argon2id", "m=65536,t=3,p=1", salt, hash)
    }

    /** The retired M1 format: PBKDF2 over the PIN itself, no pepper. */
    private fun retiredPbkdf2(pin: String): String {
        val salt = ByteArray(16).also(random::nextBytes)
        val hash = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(pin.toCharArray(), salt, 1_000, 256))
            .encoded
        return retiredEncoding("pbkdf2-sha256", "i=1000", salt, hash)
    }

    private fun retiredEncoding(algorithm: String, parameters: String, salt: ByteArray, hash: ByteArray): String {
        val encoder = java.util.Base64.getEncoder().withoutPadding()
        return "v1\$$algorithm\$$parameters\$${encoder.encodeToString(salt)}\$${encoder.encodeToString(hash)}"
    }
}
