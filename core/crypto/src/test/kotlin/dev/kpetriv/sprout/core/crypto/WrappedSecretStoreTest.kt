package dev.kpetriv.sprout.core.crypto

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WrappedSecretStoreTest {
    private val random = SecureRandom()
    private val wrapper = InMemoryKeyWrapper(random)
    private val storage = InMemoryBlobStorage()
    private val store = WrappedSecretStore(wrapper, storage, random, Dispatchers.Unconfined)

    @Test
    fun `creates once and returns the same secret afterwards`() = runTest {
        val first = store.getOrCreate("sqlcipher.passphrase").use { it.copyBytes() }
        val second = store.getOrCreate("sqlcipher.passphrase").use { it.copyBytes() }
        assertEquals(32, first.size)
        assertContentEquals(first, second)
    }

    @Test
    fun `survives a new store instance over the same storage and wrapper`() = runTest {
        val first = store.getOrCreate("db").use { it.copyBytes() }
        val restarted = WrappedSecretStore(wrapper, storage, random, Dispatchers.Unconfined)
        assertContentEquals(first, restarted.getOrCreate("db").use { it.copyBytes() })
    }

    @Test
    fun `different names get different secrets`() = runTest {
        val a = store.getOrCreate("a").use { it.copyBytes() }
        val b = store.getOrCreate("b").use { it.copyBytes() }
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun `stored blob does not contain the secret`() = runTest {
        val secret = store.getOrCreate("db").use { it.copyBytes() }
        val blob = assertNotNull(storage.read("db"))
        assertEquals(1, blob[0].toInt())
        assertEquals(1 + AesGcmAead.OVERHEAD + 32, blob.size)
        assertFalse(blob.toHex().contains(secret.toHex()))
    }

    @Test
    fun `swapped blobs fail instead of returning the other secret`() = runTest {
        store.getOrCreate("a").close()
        store.getOrCreate("b").close()
        val blobA = assertNotNull(storage.read("a"))
        storage.write("b", blobA)
        assertFailsWith<SecretUnavailableException> { store.getOrCreate("b") }
    }

    @Test
    fun `corrupt blob fails and is never silently replaced`() = runTest {
        store.getOrCreate("db").close()
        val blob = assertNotNull(storage.read("db"))
        blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 1).toByte()
        storage.write("db", blob)
        assertFailsWith<SecretUnavailableException> { store.getOrCreate("db") }
        assertContentEquals(blob, storage.read("db"))

        storage.write("db", byteArrayOf(9, 9, 9))
        assertFailsWith<SecretUnavailableException> { store.getOrCreate("db") }
    }

    @Test
    fun `a different wrapping key cannot unwrap`() = runTest {
        store.getOrCreate("db").close()
        val otherDevice = WrappedSecretStore(InMemoryKeyWrapper(random), storage, random, Dispatchers.Unconfined)
        assertFailsWith<SecretUnavailableException> { otherDevice.getOrCreate("db") }
    }

    @Test
    fun `names are restricted to a safe character set`() = runTest {
        listOf("", "../db", "a/b", ".", "..", "UPPER", "x".repeat(65)).forEach { name ->
            assertFailsWith<IllegalArgumentException>(name) { store.getOrCreate(name) }
        }
        assertTrue(WrappedSecretStore.isValidName("sqlcipher.passphrase-v1_2"))
    }

    @Test
    fun `concurrent first use creates a single secret`() = runTest {
        val results = (1..16).map {
            async(Dispatchers.Default) { store.getOrCreate("db").use { it.copyBytes().toHex() } }
        }.awaitAll()
        assertEquals(1, results.toSet().size)
    }

    @Test
    fun `missing secret is created by default and when creation is allowed`() = runTest {
        val created = store.getOrCreate("db", createIfMissing = true).use { it.copyBytes() }
        assertNotNull(storage.read("db"))
        // Once stored, createIfMissing = false returns the same secret.
        assertContentEquals(created, store.getOrCreate("db", createIfMissing = false).use { it.copyBytes() })
    }

    @Test
    fun `missing secret throws when creation is disallowed and nothing is written`() = runTest {
        val error = assertFailsWith<SecretUnavailableException> { store.getOrCreate("db", createIfMissing = false) }
        assertEquals("Secret is missing and creating it is not allowed", error.message)
        assertNull(storage.read("db"))
        // A later call that allows creation still works.
        store.getOrCreate("db").close()
        assertNotNull(storage.read("db"))
    }

    @Test
    fun `deleted blob with existing data is reported, not silently replaced`() = runTest {
        val original = store.getOrCreate("db").use { it.copyBytes() }
        storage.delete("db")
        // The database layer passes createIfMissing = !databaseFileExists, i.e. false here.
        assertFailsWith<SecretUnavailableException> { store.getOrCreate("db", createIfMissing = false) }
        val replacement = store.getOrCreate("db", createIfMissing = true).use { it.copyBytes() }
        assertFalse(original.contentEquals(replacement))
    }

    @Test
    fun `a record round-trips, is wrapped and never opens as a secret`() = runTest {
        assertNull(store.readRecord("chat.identity.v1"))
        val record = "household h1, device d1".encodeToByteArray()
        store.writeRecord("chat.identity.v1", record)

        assertContentEquals(record, store.readRecord("chat.identity.v1"))
        val blob = assertNotNull(storage.read("chat.identity.v1"))
        assertEquals(2, blob[0].toInt())
        assertFalse(blob.decodeToString().contains("household"))
        assertFailsWith<SecretUnavailableException> { store.getOrCreate("chat.identity.v1", createIfMissing = false) }
    }

    @Test
    fun `a renamed secret opens only once it is moved back, and a renamed record only under its first name`() =
        runTest {
            val secret = store.getOrCreate("chat.x25519.v1").use { it.copyBytes() }
            store.writeRecord("chat.identity.v1", byteArrayOf(7))

            assertTrue(store.rename("chat.x25519.v1", "chat.x25519.v1.stash"))
            assertTrue(store.rename("chat.identity.v1", "chat.identity.v1.stash"))

            assertNull(storage.read("chat.x25519.v1"))
            assertFailsWith<SecretUnavailableException> { store.getOrCreate("chat.x25519.v1.stash") }
            assertFailsWith<SecretUnavailableException> { store.readRecord("chat.identity.v1.stash") }
            assertContentEquals(
                byteArrayOf(7),
                store.readRecord("chat.identity.v1.stash", boundTo = "chat.identity.v1"),
            )

            assertTrue(store.rename("chat.x25519.v1.stash", "chat.x25519.v1"))
            assertContentEquals(
                secret,
                store.getOrCreate("chat.x25519.v1", createIfMissing = false).use {
                    it.copyBytes()
                },
            )
            assertFalse(store.rename("chat.x25519.v1.stash", "chat.x25519.v1"), "nothing left to move")
        }

    @Test
    fun `delete removes a secret and nothing stored is not a failure`() = runTest {
        store.getOrCreate("a").close()
        store.delete("a")
        store.delete("a")
        assertNull(storage.read("a"))
    }
}
