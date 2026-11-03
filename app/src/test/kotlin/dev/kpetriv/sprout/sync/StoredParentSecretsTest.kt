package dev.kpetriv.sprout.sync

import dev.kpetriv.sprout.core.crypto.BlobStorage
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals

/** An account change clears the previous parent's PIN, lockout and signing key, never the data keys (audit L10). */
class StoredParentSecretsTest {
    private val blobs = mutableMapOf<String, ByteArray>()
    private val storage = object : BlobStorage {
        override suspend fun read(name: String): ByteArray? = blobs[name]

        override suspend fun write(name: String, blob: ByteArray) {
            blobs[name] = blob
        }

        override suspend fun delete(name: String) {
            blobs.remove(name)
        }
    }

    @Test
    fun `the parent's blobs and key go, the lock reloads, and the database and photo keys stay`() = runTest {
        val steps = mutableListOf<String>()
        listOf("pin.hash", "pin.hash.value", "pin.lockout", "pin.lockout.state", "db.passphrase", "photos.key")
            .forEach { blobs[it] = byteArrayOf(1) }

        StoredParentSecrets(storage, { steps += "reset parent key" }, { steps += "reload lock" }).clear()

        assertEquals(setOf("db.passphrase", "photos.key"), blobs.keys)
        assertEquals(listOf("reset parent key", "reload lock"), steps)
        assertEquals(
            setOf("pin.hash", "pin.hash.value", "pin.lockout", "pin.lockout.state"),
            StoredParentSecrets.PARENT_BLOBS.toSet(),
        )
    }
}
