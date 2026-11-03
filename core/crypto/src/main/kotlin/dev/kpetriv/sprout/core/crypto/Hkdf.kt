package dev.kpetriv.sprout.core.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** HKDF with HMAC-SHA256 (RFC 5869). */
object Hkdf {
    /** SHA-256 output length in bytes. */
    const val HASH_LENGTH = 32

    /** RFC 5869 limit on output length: 255 blocks. */
    const val MAX_OUTPUT_LENGTH = 255 * HASH_LENGTH

    /** HKDF-Extract. An empty [salt] means HashLen zero bytes, as the RFC specifies. */
    fun extract(salt: ByteArray, ikm: ByteArray): ByteArray {
        val effectiveSalt = if (salt.isEmpty()) ByteArray(HASH_LENGTH) else salt
        return hmac(effectiveSalt).doFinal(ikm)
    }

    /** HKDF-Expand to [length] bytes. */
    fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..MAX_OUTPUT_LENGTH) { "HKDF output length must be 1..$MAX_OUTPUT_LENGTH" }
        require(prk.size >= HASH_LENGTH) { "PRK must be at least $HASH_LENGTH bytes" }
        val mac = hmac(prk)
        val output = ByteArray(length)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            mac.update(previous)
            mac.update(info)
            mac.update(counter.toByte())
            val block = mac.doFinal()
            val count = minOf(block.size, length - offset)
            block.copyInto(output, destinationOffset = offset, endIndex = count)
            previous.fill(0)
            previous = block
            offset += count
            counter++
        }
        previous.fill(0)
        return output
    }

    /** Extract then expand; the intermediate PRK is zeroed before returning. */
    fun derive(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = extract(salt, ikm)
        return try {
            expand(prk, info, length)
        } finally {
            prk.fill(0)
        }
    }

    private fun hmac(key: ByteArray): Mac = Mac.getInstance(CryptoAlgorithms.HMAC).apply {
        init(SecretKeySpec(key, CryptoAlgorithms.HMAC))
    }
}
