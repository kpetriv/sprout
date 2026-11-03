package dev.kpetriv.sprout.core.crypto

import dev.kpetriv.sprout.core.time.FakeClock
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class InMemoryDeviceKeyStoreTest {
    private val store = InMemoryDeviceKeyStore(FakeClock())

    @Test
    fun registrationSwapsInAFreshKeyWithoutAnAttestationChain() = runTest {
        val provisional = store.publicKey()

        val material = store.regenerateForRegistration(byteArrayOf(1, 2, 3))

        assertNotEquals(provisional, material.publicKey)
        assertEquals(material.publicKey, store.publicKey())
        assertEquals(material.publicKey, store.deviceSigningKey().publicKey)
        assertTrue(material.attestationChain.isEmpty())
        assertEquals(1, store.registrations)
        val payload = "event".encodeToByteArray()
        val signature = JceEcdsaSigner(store.deviceSigningKey()).sign(payload)
        assertTrue(JceEcdsaVerifier().verify(material.publicKey, payload, signature))
    }

    @Test
    fun registeringAgainReplacesTheKey() = runTest {
        val first = store.regenerateForRegistration(byteArrayOf(1)).publicKey
        val second = store.regenerateForRegistration(byteArrayOf(2)).publicKey
        assertNotEquals(first, second)
        assertEquals(second, store.publicKey())
    }

    @Test
    fun anEmptyChallengeIsRefused() = runTest {
        assertFailsWith<IllegalArgumentException> { store.regenerateForRegistration(ByteArray(0)) }
    }
}
