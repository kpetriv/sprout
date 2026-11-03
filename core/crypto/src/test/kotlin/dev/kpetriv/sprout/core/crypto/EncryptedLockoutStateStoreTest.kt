package dev.kpetriv.sprout.core.crypto

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class EncryptedLockoutStateStoreTest {
    private val random = SecureRandom()
    private val storage = InMemoryBlobStorage()
    private val wrapper = InMemoryKeyWrapper(random)
    private val secrets = WrappedSecretStore(wrapper, storage, random, Dispatchers.Unconfined)
    private val aead = AesGcmAead(random)
    private val store = EncryptedLockoutStateStore(secrets, storage, aead)
    private val locked = PinLockoutState(7, 123_456.milliseconds, "8c3f0c1e-1111-4222-8333-944455556666")

    @Test
    fun `nothing stored reads as null`() = runTest {
        assertNull(store.read())
    }

    @Test
    fun `state round trips and survives a new store instance`() = runTest {
        store.write(locked)
        assertEquals(locked, store.read())
        assertEquals(locked, EncryptedLockoutStateStore(secrets, storage, aead).read())
        store.write(PinLockoutState(2))
        assertEquals(PinLockoutState(2), store.read())
    }

    @Test
    fun `the stored blob is encrypted and separate from the secret`() = runTest {
        store.write(locked)
        val blob = assertNotNull(storage.read(EncryptedLockoutStateStore.STATE_BLOB))
        assertNotNull(storage.read(EncryptedLockoutStateStore.SECRET_NAME))
        assertFalse(blob.toHex().contains("8c3f0c1e".encodeToByteArray().toHex()))
        assertFalse(blob.decodeToString().contains("8c3f0c1e"))
    }

    @Test
    fun `tampered or truncated state is unavailable, never empty`() = runTest {
        store.write(locked)
        val blob = assertNotNull(storage.read(EncryptedLockoutStateStore.STATE_BLOB))
        storage.write(EncryptedLockoutStateStore.STATE_BLOB, blob.copyOf().also { it[20] = (it[20] + 1).toByte() })
        assertFailsWith<LockoutStateUnavailableException> { store.read() }
        storage.write(EncryptedLockoutStateStore.STATE_BLOB, blob.copyOf(10))
        assertFailsWith<LockoutStateUnavailableException> { store.read() }
    }

    @Test
    fun `state with a missing or foreign key is unavailable`() = runTest {
        store.write(locked)
        val blob = assertNotNull(storage.read(EncryptedLockoutStateStore.STATE_BLOB))
        storage.delete(EncryptedLockoutStateStore.SECRET_NAME)
        assertFailsWith<LockoutStateUnavailableException> { store.read() }
        // Reading never mints a key; nothing was created.
        assertNull(storage.read(EncryptedLockoutStateStore.SECRET_NAME))

        // A fresh key (as after a write) still cannot read the old blob.
        store.write(PinLockoutState.Initial)
        storage.write(EncryptedLockoutStateStore.STATE_BLOB, blob)
        assertFailsWith<LockoutStateUnavailableException> { store.read() }
    }

    @Test
    fun `codec round trips and rejects malformed input`() {
        for (state in listOf(PinLockoutState.Initial, PinLockoutState(3), locked, PinLockoutState(1, 0.seconds, "b"))) {
            assertEquals(state, PinLockoutStateCodec.decode(PinLockoutStateCodec.encode(state)))
        }
        val valid = PinLockoutStateCodec.encode(locked)
        val malformed = listOf(
            ByteArray(0),
            valid.copyOf(valid.size - 1),
            valid + byteArrayOf(0),
            valid.copyOf().also { it[0] = 2 },
            // Negative failure count.
            valid.copyOf().also { it[1] = 0x80.toByte() },
            // Start without a boot id, and a boot id without a start.
            PinLockoutStateCodec.encode(PinLockoutState(4)).also { it.fill(0, 5, 13) },
            PinLockoutStateCodec.encode(PinLockoutState(4)).copyOf(15).also { it[14] = 1 } + byteArrayOf(0x41),
        )
        malformed.forEachIndexed { index, bytes ->
            assertFailsWith<IllegalArgumentException>("case $index") { PinLockoutStateCodec.decode(bytes) }
        }
    }

    @Test
    fun `policy over the encrypted store keeps its lockout across instances`() = runTest {
        val clock = FakeBootClock()
        val policy = PinLockoutPolicy(store, clock) { true }
        repeat(4) { policy.registerFailure() }
        clock.advanceBy(10.seconds)
        val restarted = PinLockoutPolicy(EncryptedLockoutStateStore(secrets, storage, aead), clock) { true }
        assertEquals(LockoutDecision.Wait(20.seconds), restarted.decision())
    }
}
