package dev.kpetriv.sprout.core.crypto.chat

import dev.kpetriv.sprout.core.crypto.AesGcmAead
import dev.kpetriv.sprout.core.domainledger.chat.BlobContext
import dev.kpetriv.sprout.core.domainledger.chat.ChatLimits
import dev.kpetriv.sprout.core.model.BlobId
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.SubmissionId
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BlobCipherTest {
    private val random = SecureRandom()
    private val cipher = BlobCipher(AesGcmAead(random))
    private val key = ThreadKey.generate(thread, 3, random)
    private val chat = BlobContext(household, BlobId("blob-1"), thread, thread.kidId, null, DeviceId("kid-tablet"), 3)

    @Test
    fun aLargeBlobSplitsIntoTwoChunksAndReassembles() {
        val photo = ByteArray(1_000_000).also(random::nextBytes)
        val blob = cipher.encrypt(key, chat, photo)
        assertEquals(2, blob.chunks.size)
        assertTrue(blob.chunks.all { it.size <= ChatLimits.MAX_BLOB_CHUNK_BYTES })
        assertEquals(ChatLimits.MAX_BLOB_CHUNK_BYTES, blob.chunks.first().size)
        assertContentEquals(photo, cipher.decrypt(key, chat, blob.chunks, blob.sha256))
        val voice = cipher.encrypt(key, chat, ByteArray(120_000))
        assertEquals(1, voice.chunks.size)
    }

    @Test
    fun aChorePhotoUsesItsKidsThreadKey() {
        val chore = chat.copy(threadId = null, submissionId = SubmissionId("sub-1"))
        val blob = cipher.encrypt(key, chore, byteArrayOf(1, 2, 3))
        assertContentEquals(byteArrayOf(1, 2, 3), cipher.decrypt(key, chore, blob.chunks, blob.sha256))
        assertFailsWith<BlobDecryptionException> { cipher.decrypt(key, chat, blob.chunks, blob.sha256) }
    }

    @Test
    fun hashContextAndKeyAreChecked() {
        val blob = cipher.encrypt(key, chat, ByteArray(800_000))
        assertFailsWith<BlobDecryptionException> { cipher.decrypt(key, chat, blob.chunks, "00".repeat(32)) }
        assertFailsWith<BlobDecryptionException> { cipher.decrypt(key, chat, blob.chunks.reversed(), blob.sha256) }
        assertFailsWith<BlobDecryptionException> {
            cipher.decrypt(key, chat.copy(blobId = BlobId("blob-2")), blob.chunks, blob.sha256)
        }
        assertFailsWith<BlobDecryptionException> {
            cipher.decrypt(key, chat.copy(authorDeviceId = DeviceId("parent-phone")), blob.chunks, blob.sha256)
        }
        ThreadKey.generate(thread, 3, random).use { other ->
            assertFailsWith<BlobDecryptionException> { cipher.decrypt(other, chat, blob.chunks, blob.sha256) }
        }
        val otherKid = chat.copy(
            threadId = dev.kpetriv.sprout.core.model.ThreadId.of(KidId("kid-2")),
            kidId = KidId("kid-2"),
        )
        assertFailsWith<IllegalArgumentException> { cipher.encrypt(key, otherKid, byteArrayOf(1)) }
        assertFailsWith<BlobDecryptionException> { cipher.decrypt(key, otherKid, blob.chunks, blob.sha256) }
    }

    @Test
    fun blobsOverTheLimitAreRefused() {
        assertFailsWith<IllegalArgumentException> { cipher.encrypt(key, chat, ByteArray(ChatLimits.MAX_BLOB_BYTES)) }
    }
}
