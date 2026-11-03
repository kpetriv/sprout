package dev.kpetriv.sprout.core.crypto

import android.security.keystore.KeyProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.security.Security
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [AndroidKeystoreParentAuthKeyStore] over [FakeAndroidKeyStoreProvider]: the attestation challenge reaches the real
 * [android.security.keystore.KeyGenParameterSpec], the chain is exposed leaf first, a Keystore that cannot attest
 * falls back to an unattested key, and the per-use biometric properties hold either way.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidKeystoreParentAuthKeyStoreTest {
    private val provider = FakeAndroidKeyStoreProvider()
    private lateinit var store: AndroidKeystoreParentAuthKeyStore
    private val challenge = ByteArray(32) { it.toByte() }

    @Before
    fun setUp() {
        // Last: AndroidKeystore asks for its provider by name, and the JDK keeps answering plain "EC".
        Security.addProvider(provider)
        store = AndroidKeystoreParentAuthKeyStore(RuntimeEnvironment.getApplication(), Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        Security.removeProvider(provider.name)
    }

    @Test
    fun aChallengeIsBoundIntoTheKeyAndItsChainIsExposedLeafFirst() = runTest {
        val publicKey = store.ensureKey(challenge)

        val spec = provider.specs.single()
        assertTrue(challenge.contentEquals(spec.attestationChallenge))
        assertBiometricPerUse(spec)
        val chain = store.attestationChain()
        assertEquals(2, chain.size)
        assertTrue(chain[0].contentEquals(FakeAndroidKeyStoreProvider.LEAF + challenge), "leaf first")
        assertTrue(chain[1].contentEquals(FakeAndroidKeyStoreProvider.ROOT))
        assertEquals(publicKey, store.publicKey())
    }

    @Test
    fun withoutAChallengeTheKeyIsUnattestedAndTheChainEmpty() = runTest {
        store.ensureKey()

        val spec = provider.specs.single()
        assertNull(spec.attestationChallenge)
        assertBiometricPerUse(spec)
        assertEquals(emptyList(), store.attestationChain(), "a self-signed certificate is not a chain")
    }

    @Test
    fun aKeystoreThatCannotAttestFallsBackToAnUnattestedKey() = runTest {
        provider.refuseAttestation = true

        val publicKey = store.ensureKey(challenge)

        assertEquals(2, provider.specs.size, "tried with the challenge, then without")
        assertTrue(challenge.contentEquals(provider.specs[0].attestationChallenge))
        assertNull(provider.specs[1].attestationChallenge)
        provider.specs.forEach(::assertBiometricPerUse)
        assertEquals(emptyList(), store.attestationChain())
        assertEquals(publicKey, store.publicKey())
    }

    @Test
    fun aRefusedKeyIsUnavailableWithOrWithoutAChallengeAndLeavesNothingBehind() = runTest {
        provider.refuseAll = true

        assertFailsWith<ParentAuthUnavailableException> { store.ensureKey(challenge) }
        assertFailsWith<ParentAuthUnavailableException> { store.ensureKey() }

        assertNull(store.publicKey())
        assertEquals(emptyList(), store.attestationChain())
        assertTrue(provider.stored.isEmpty())
    }

    @Test
    fun anExistingKeyIsKeptAndANewChallengeChangesNothing() = runTest {
        val first = store.ensureKey(challenge)

        assertEquals(first, store.ensureKey(ByteArray(32) { 9 }))

        assertEquals(1, provider.specs.size)
        assertTrue(store.attestationChain()[0].contentEquals(FakeAndroidKeyStoreProvider.LEAF + challenge))
        store.deleteKey()
        assertNull(store.publicKey())
        assertEquals(emptyList(), store.attestationChain())
    }

    @Test
    fun anEmptyChallengeIsRefused() = runTest {
        assertFailsWith<IllegalArgumentException> { store.ensureKey(ByteArray(0)) }
        assertTrue(provider.specs.isEmpty())
    }

    private fun assertBiometricPerUse(spec: android.security.keystore.KeyGenParameterSpec) {
        assertTrue(spec.isUserAuthenticationRequired)
        assertTrue(spec.isInvalidatedByBiometricEnrollment)
        assertEquals(
            AndroidKeystoreParentAuthKeyStore.PER_USE_TIMEOUT_SECONDS,
            spec.userAuthenticationValidityDurationSeconds,
        )
        assertEquals(KeyProperties.AUTH_BIOMETRIC_STRONG, spec.userAuthenticationType)
        assertTrue(spec.digests.contentEquals(arrayOf(KeyProperties.DIGEST_SHA256)))
        assertEquals(KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY, spec.purposes)
    }
}
