package dev.kpetriv.sprout.core.crypto.chat

import dev.kpetriv.sprout.core.crypto.SymmetricKey
import dev.kpetriv.sprout.core.model.ThreadId
import java.security.SecureRandom

/**
 * The AES-256-GCM key of one thread epoch (M4 §1.2). Closing it wipes the key. `toString` never prints key material.
 * The data layer persists it through [copyBytes] (in the SQLCipher database) and restores it with [fromBytes].
 */
class ThreadKey private constructor(val threadId: ThreadId, val epoch: Long, internal val key: SymmetricKey) :
    AutoCloseable {
    init {
        require(epoch >= 0) { "epoch must not be negative" }
    }

    /** A copy of the 32 key bytes; the caller owns it and should zero it after use. */
    fun copyBytes(): ByteArray = key.copyBytes()

    override fun close() = key.close()

    override fun toString(): String = "ThreadKey(threadId=$threadId, epoch=$epoch, key=<redacted>)"

    companion object {
        const val SIZE: Int = SymmetricKey.AES_256_KEY_SIZE

        /** A fresh random key for ([threadId], [epoch]). */
        fun generate(threadId: ThreadId, epoch: Long, random: SecureRandom): ThreadKey =
            ThreadKey(threadId, epoch, SymmetricKey.generate(random, SIZE))

        /** Copies [bytes] (32); the caller still owns the array it passed in. */
        fun fromBytes(threadId: ThreadId, epoch: Long, bytes: ByteArray): ThreadKey {
            require(bytes.size == SIZE) { "A thread key is $SIZE bytes" }
            return ThreadKey(threadId, epoch, SymmetricKey.copyOf(bytes))
        }
    }
}
