package dev.kpetriv.sprout.core.crypto

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
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [AndroidKeystoreDeviceKeyStore]'s two-phase registration over [FakeAndroidKeyStoreProvider] (audit M1): the
 * registration key is pending until promoted, the active key keeps signing meanwhile, a discarded registration keeps
 * the previous key, and every state survives a process restart (a fresh instance).
 */
@RunWith(RobolectricTestRunner::class)
class AndroidKeystoreDeviceKeyStoreTest {
    private val provider = FakeAndroidKeyStoreProvider()
    private val context get() = RuntimeEnvironment.getApplication()
    private val challenge = ByteArray(32) { it.toByte() }

    @Before
    fun setUp() {
        Security.addProvider(provider)
    }

    @After
    fun tearDown() {
        Security.removeProvider(provider.name)
    }

    private fun store() = AndroidKeystoreDeviceKeyStore(context, Dispatchers.Unconfined)

    @Test
    fun aRegistrationKeyIsPendingAndTheActiveKeyKeepsSigning() = runTest {
        val store = store()
        val provisional = store.publicKey()

        val material = store.regenerateForRegistration(challenge)

        assertNotEquals(provisional, material.publicKey)
        assertEquals(provisional, store.publicKey(), "the active key is unchanged until promotion")
        assertEquals(KeyAliases.DEVICE_SIGNING, store.deviceSigningKey().alias)
        val pending = store.pendingSigningKey()
        assertEquals(material.publicKey, pending?.publicKey)
        assertEquals(KeyAliases.DEVICE_SIGNING_V2_PENDING, pending?.alias)
        val spec = provider.stored.getValue(KeyAliases.DEVICE_SIGNING_V2_PENDING).spec
        assertTrue(challenge.contentEquals(spec.attestationChallenge))
        assertEquals(2, material.attestationChain.size)
        // A restart in between (the server may already have accepted it) still finds the pending key.
        assertEquals(material.publicKey, store().pendingSigningKey()?.publicKey)
        assertEquals(provisional, store().publicKey())
    }

    @Test
    fun promotionMakesThePendingKeyActiveAndDeletesThePreviousOne() = runTest {
        val store = store()
        store.publicKey()
        val material = store.regenerateForRegistration(challenge)

        assertEquals(material.publicKey, store.promotePendingKey())

        assertEquals(material.publicKey, store.publicKey())
        assertEquals(material.publicKey, store.deviceSigningKey().publicKey)
        assertNull(store.pendingSigningKey())
        assertFalse(KeyAliases.DEVICE_SIGNING in provider.stored, "the provisional key is deleted")
        val payload = "event".encodeToByteArray()
        val signature = JceEcdsaSigner(store.deviceSigningKey()).sign(payload)
        assertTrue(JceEcdsaVerifier().verify(material.publicKey, payload, signature))
        val restarted = store()
        assertEquals(material.publicKey, restarted.publicKey())
        assertNull(restarted.pendingSigningKey())
    }

    @Test
    fun aDiscardedRegistrationKeepsThePreviousKey() = runTest {
        val store = store()
        val first = store.regenerateForRegistration(challenge).publicKey
        store.promotePendingKey()

        store.regenerateForRegistration(challenge)
        store.discardPendingKey()

        assertEquals(first, store.publicKey())
        assertNull(store.pendingSigningKey())
        assertEquals(setOf(KeyAliases.DEVICE_SIGNING_V2_PENDING), provider.stored.keys)
        assertEquals(first, store().publicKey())
    }

    @Test
    fun registrationsAlternateBetweenTheTwoSlots() = runTest {
        val store = store()
        store.regenerateForRegistration(challenge)
        store.promotePendingKey()

        val second = store.regenerateForRegistration(challenge)

        assertEquals(KeyAliases.DEVICE_SIGNING_V2, store.pendingSigningKey()?.alias, "the slot not in use")
        assertEquals(KeyAliases.DEVICE_SIGNING_V2_PENDING, store.deviceSigningKey().alias)
        store.promotePendingKey()
        assertEquals(second.publicKey, store().publicKey())
        assertEquals(setOf(KeyAliases.DEVICE_SIGNING_V2), provider.stored.keys)
    }

    @Test
    fun aKeyRegisteredBeforeSlotsWereRecordedStaysActive() = runTest {
        // A device registered by an earlier version: a v2 key and no recorded slots.
        provider.generate(
            android.security.keystore.KeyGenParameterSpec.Builder(
                KeyAliases.DEVICE_SIGNING_V2,
                android.security.keystore.KeyProperties.PURPOSE_SIGN,
            ).build(),
        )
        val store = store()

        assertEquals(KeyAliases.DEVICE_SIGNING_V2, store.deviceSigningKey().alias)
        store.regenerateForRegistration(challenge)
        assertEquals(KeyAliases.DEVICE_SIGNING_V2_PENDING, store.pendingSigningKey()?.alias)
        assertEquals(KeyAliases.DEVICE_SIGNING_V2, store().deviceSigningKey().alias)
    }

    @Test
    fun promotingWithoutAPendingKeyFails() = runTest {
        val store = store()
        assertFailsWith<IllegalStateException> { store.promotePendingKey() }
        store.discardPendingKey()
        assertEquals(KeyAliases.DEVICE_SIGNING, store.deviceSigningKey().alias)
    }
}
