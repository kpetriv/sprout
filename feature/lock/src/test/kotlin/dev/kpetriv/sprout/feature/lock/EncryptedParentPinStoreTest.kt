package dev.kpetriv.sprout.feature.lock

import dev.kpetriv.sprout.core.crypto.AesGcmAead
import dev.kpetriv.sprout.core.crypto.InMemoryBlobStorage
import dev.kpetriv.sprout.core.crypto.InMemoryHmacPepper
import dev.kpetriv.sprout.core.crypto.InMemoryKeyWrapper
import dev.kpetriv.sprout.core.crypto.Pbkdf2PinHasher
import dev.kpetriv.sprout.core.crypto.WrappedSecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.security.SecureRandom
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EncryptedParentPinStoreTest {
    private val random = SecureRandom()
    private val storage = InMemoryBlobStorage()
    private val secrets = WrappedSecretStore(InMemoryKeyWrapper(random), storage, random, Dispatchers.Unconfined)
    private val store = EncryptedParentPinStore(secrets, storage, AesGcmAead(random))
    private val hasher = Pbkdf2PinHasher(random, Dispatchers.Unconfined, InMemoryHmacPepper(random), iterations = 1_000)

    @Test
    fun `nothing stored reads as no PIN`() = runTest {
        assertFalse(store.hasPin())
        assertNull(store.storedHash())
    }

    @Test
    fun `a saved hash round-trips and is encrypted at rest`() = runTest {
        val hash = hasher.hash("482913".toCharArray())
        store.save(hash)

        assertTrue(store.hasPin())
        assertEquals(hash, store.storedHash())
        val blob = storage.read(EncryptedParentPinStore.HASH_BLOB)!!
        assertFalse(blob.decodeToString(throwOnInvalidSequence = false).contains("pbkdf2"))
    }

    @Test
    fun `a tampered blob is unavailable, never absent`() = runTest {
        store.save(hasher.hash("482913".toCharArray()))
        val blob = storage.read(EncryptedParentPinStore.HASH_BLOB)!!
        blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 1).toByte()
        storage.write(EncryptedParentPinStore.HASH_BLOB, blob)

        assertTrue(store.hasPin())
        assertFailsWith<ParentPinUnavailableException> { store.storedHash() }
    }

    @Test
    fun `a lost encryption key is unavailable, never absent`() = runTest {
        store.save(hasher.hash("482913".toCharArray()))
        storage.delete(EncryptedParentPinStore.SECRET_NAME)

        assertTrue(store.hasPin())
        assertFailsWith<ParentPinUnavailableException> { store.storedHash() }
    }

    @Test
    fun `only encoded hashes can be saved`() = runTest {
        assertFailsWith<IllegalArgumentException> { store.save("482913") }
        assertFalse(store.hasPin())
    }
}
