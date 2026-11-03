package dev.kpetriv.sprout.core.crypto.chat

import dev.kpetriv.sprout.core.crypto.Aead
import dev.kpetriv.sprout.core.crypto.AeadException
import dev.kpetriv.sprout.core.crypto.CryptoAlgorithms
import dev.kpetriv.sprout.core.domainledger.chat.BlobContext
import dev.kpetriv.sprout.core.domainledger.chat.ChatLimits
import dev.kpetriv.sprout.core.domainledger.toHex
import java.security.GeneralSecurityException
import java.security.MessageDigest
import javax.inject.Inject

/** A blob ciphertext, its lower-case hex [sha256] and its [chunks] (each at most [ChatLimits.MAX_BLOB_CHUNK_BYTES]). */
class EncryptedBlob(val sha256: String, val chunks: List<ByteArray>) {
    override fun toString(): String = "EncryptedBlob(chunks=${chunks.size}, bytes=${chunks.sumOf { it.size }})"
}

/** A blob did not reassemble, match its hash, or decrypt. */
class BlobDecryptionException(message: String, cause: Throwable? = null) : GeneralSecurityException(message, cause)

/**
 * Encrypts photos and voice notes for the `blobs` lane (M4 §2): one AEAD ciphertext under the thread key of the
 * context's epoch with [BlobContext.associatedData], split into at most [ChatLimits.MAX_BLOB_CHUNKS] chunks of at most
 * [ChatLimits.MAX_BLOB_CHUNK_BYTES]. Chore photos use the kid's thread key ([BlobContext.kidId]'s thread).
 */
class BlobCipher @Inject constructor(private val aead: Aead) {
    /**
     * @throws IllegalArgumentException if [key] is not the epoch key of the context's kid, or the ciphertext would
     *   exceed [ChatLimits.MAX_BLOB_BYTES].
     */
    fun encrypt(key: ThreadKey, context: BlobContext, plaintext: ByteArray): EncryptedBlob {
        requireKeyFor(key, context)
        val ciphertext = aead.encrypt(key.key, plaintext, context.associatedData())
        require(ciphertext.size <= ChatLimits.MAX_BLOB_BYTES) { "blob exceeds ${ChatLimits.MAX_BLOB_BYTES} bytes" }
        val chunks = (0 until ciphertext.size step ChatLimits.MAX_BLOB_CHUNK_BYTES).map { start ->
            ciphertext.copyOfRange(start, minOf(start + ChatLimits.MAX_BLOB_CHUNK_BYTES, ciphertext.size))
        }
        return EncryptedBlob(sha256(ciphertext), chunks)
    }

    /**
     * Joins [chunks] (in chunk order), checks the joined ciphertext against [expectedSha256] (the signed header's
     * `sha256`) and decrypts it.
     *
     * @throws BlobDecryptionException when the hash differs, the key is for another kid or epoch, or the ciphertext
     *   does not authenticate under [context].
     */
    fun decrypt(key: ThreadKey, context: BlobContext, chunks: List<ByteArray>, expectedSha256: String): ByteArray {
        if (key.threadId.value != context.kidId.value || key.epoch != context.epoch) {
            throw BlobDecryptionException("Key is not the blob's epoch key")
        }
        val ciphertext = joinIntact(chunks, expectedSha256)
        return try {
            aead.decrypt(key.key, ciphertext, context.associatedData())
        } catch (e: AeadException) {
            throw BlobDecryptionException("Blob does not authenticate", e)
        }
    }

    /** The chunks joined, after checking their count and the hash of the whole ciphertext. */
    private fun joinIntact(chunks: List<ByteArray>, expectedSha256: String): ByteArray {
        val ciphertext = chunks.takeIf { it.size in 1..ChatLimits.MAX_BLOB_CHUNKS }
            ?.reduce { joined, chunk -> joined + chunk }
        val intact = ciphertext != null &&
            MessageDigest.isEqual(sha256(ciphertext).encodeToByteArray(), expectedSha256.encodeToByteArray())
        if (!intact) throw BlobDecryptionException("Blob chunks do not match their hash")
        return ciphertext
    }

    private fun requireKeyFor(key: ThreadKey, context: BlobContext) {
        require(key.threadId.value == context.kidId.value && key.epoch == context.epoch) {
            "key is not the blob's epoch key"
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance(CryptoAlgorithms.DIGEST).digest(bytes).toHex()
}
