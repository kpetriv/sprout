package dev.kpetriv.sprout.core.crypto

import dev.kpetriv.sprout.core.time.FakeClock
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InMemoryDeviceKeyStoreTest {
    private val store = InMemoryDeviceKeyStore(FakeClock())

    @Test
    fun registrationCreatesAPendingKeyWithoutAnAttestationChain() = runTest {
        val provisional = store.publicKey()

        val material = store.regenerateForRegistration(byteArrayOf(1, 2, 3))

        assertNotEquals(provisional, material.publicKey)
        assertEquals(provisional, store.publicKey(), "the active key changes only on promotion")
        assertEquals(material.publicKey, store.pendingSigningKey()?.publicKey)
        assertTrue(material.attestationChain.isEmpty())
        assertEquals(1, store.registrations)
    }

    @Test
    fun promotionSwapsInThePendingKey() = runTest {
        val material = store.regenerateForRegistration(byteArrayOf(1))

        assertEquals(material.publicKey, store.promotePendingKey())

        assertEquals(material.publicKey, store.publicKey())
        assertNull(store.pendingSigningKey())
        val payload = "event".encodeToByteArray()
        val signature = JceEcdsaSigner(store.deviceSigningKey()).sign(payload)
        assertTrue(JceEcdsaVerifier().verify(material.publicKey, payload, signature))
    }

    @Test
    fun discardingKeepsTheActiveKey() = runTest {
        val active = store.registerAndPromote()
        store.regenerateForRegistration(byteArrayOf(2))
        store.discardPendingKey()
        assertEquals(active, store.publicKey())
        assertFailsWith<IllegalStateException> { store.promotePendingKey() }
    }

    @Test
    fun anEmptyChallengeIsRefused() = runTest {
        assertFailsWith<IllegalArgumentException> { store.regenerateForRegistration(ByteArray(0)) }
    }
}
