package dev.kpetriv.sprout.core.domainledger.chat

/**
 * Length hiding for message plaintext (audit M3, ADR-0008): the canonical plaintext bytes are padded to a bucket
 * before encryption, so the ciphertext size says little more than the envelope's (visible) `kind`.
 *
 * Layout: `length (4 bytes, big-endian) || plaintext || 0x00 * n`, padded to
 * - [FIXED_BUCKET_BYTES] for PRESET, VOICE and PHOTO (every such plaintext fits: a 200-character caption escaped as
 *   `\u00XX` is 1,200 bytes);
 * - the next multiple of [TEXT_STEP_BYTES] for TEXT, at most [MAX_PADDED_BYTES].
 *
 * [MAX_PADDED_BYTES] keeps the AEAD output (29 bytes of version, nonce and tag) within
 * [ChatLimits.MAX_MESSAGE_CIPHERTEXT_BYTES]. The length prefix makes the scheme unambiguous for any plaintext,
 * trailing zero bytes included; [unpad] refuses a non-zero filler or a length that does not fit.
 */
object MessagePadding {
    /** Bytes of the length prefix. */
    const val LENGTH_BYTES: Int = 4

    /** Step of the TEXT buckets. */
    const val TEXT_STEP_BYTES: Int = 64

    /** The one bucket of PRESET, VOICE and PHOTO plaintexts. */
    const val FIXED_BUCKET_BYTES: Int = 1536

    /** AEAD overhead of `Aead` (core/crypto): version byte, 12-byte nonce, 16-byte tag. */
    const val AEAD_OVERHEAD_BYTES: Int = 29

    /** Largest padded plaintext: the largest multiple of [TEXT_STEP_BYTES] that stays within the ciphertext cap. */
    const val MAX_PADDED_BYTES: Int =
        (ChatLimits.MAX_MESSAGE_CIPHERTEXT_BYTES - AEAD_OVERHEAD_BYTES) / TEXT_STEP_BYTES * TEXT_STEP_BYTES

    /** The padded size of a [kind] plaintext of [length] bytes, or null when it does not fit any bucket. */
    fun paddedSize(kind: MessageKind, length: Int): Int? {
        val needed = LENGTH_BYTES + length
        val size = when (kind) {
            MessageKind.TEXT -> (needed + TEXT_STEP_BYTES - 1) / TEXT_STEP_BYTES * TEXT_STEP_BYTES
            MessageKind.PRESET, MessageKind.VOICE, MessageKind.PHOTO -> FIXED_BUCKET_BYTES
        }
        return size.takeIf { length >= 0 && needed <= it && it <= MAX_PADDED_BYTES }
    }

    /**
     * [plaintext] padded for [kind]. The caller still owns (and should wipe) both arrays.
     *
     * @throws IllegalArgumentException when the plaintext does not fit its bucket.
     */
    fun pad(kind: MessageKind, plaintext: ByteArray): ByteArray {
        val size = requireNotNull(paddedSize(kind, plaintext.size)) { "plaintext too large for its bucket" }
        val padded = ByteArray(size)
        val length = plaintext.size
        for (index in 0 until LENGTH_BYTES) {
            padded[index] = (length ushr (Byte.SIZE_BITS * (LENGTH_BYTES - 1 - index))).toByte()
        }
        plaintext.copyInto(padded, LENGTH_BYTES)
        return padded
    }

    /** The plaintext inside [padded], or null when the length prefix does not fit or the filler is not all zero. */
    @Suppress("ReturnCount") // One early return per malformed part.
    fun unpad(padded: ByteArray): ByteArray? {
        if (padded.size < LENGTH_BYTES) return null
        var length = 0L
        for (index in 0 until LENGTH_BYTES) {
            length =
                (length shl Byte.SIZE_BITS) or (padded[index].toLong() and BYTE_MASK)
        }
        val end = LENGTH_BYTES + length
        if (end > padded.size) return null
        for (index in end.toInt() until padded.size) if (padded[index] != ZERO) return null
        return padded.copyOfRange(LENGTH_BYTES, end.toInt())
    }

    private const val BYTE_MASK = 0xFFL
    private const val ZERO: Byte = 0
}
