package dev.kpetriv.sprout.core.domainledger.chat

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class MessagePaddingTest {
    @Test
    fun textIsPaddedToSixtyFourByteSteps() {
        assertEquals(64, MessagePadding.pad(MessageKind.TEXT, ByteArray(1)).size)
        assertEquals(64, MessagePadding.pad(MessageKind.TEXT, ByteArray(60)).size)
        assertEquals(128, MessagePadding.pad(MessageKind.TEXT, ByteArray(61)).size)
        assertEquals(MessagePadding.MAX_PADDED_BYTES, MessagePadding.paddedSize(MessageKind.TEXT, 8_100))
        assertNull(MessagePadding.paddedSize(MessageKind.TEXT, MessagePadding.MAX_PADDED_BYTES))
        assertFailsWith<IllegalArgumentException> { MessagePadding.pad(MessageKind.TEXT, ByteArray(9_000)) }
    }

    @Test
    fun otherKindsShareOneFixedBucket() {
        for (kind in listOf(MessageKind.PRESET, MessageKind.VOICE, MessageKind.PHOTO)) {
            assertEquals(MessagePadding.FIXED_BUCKET_BYTES, MessagePadding.pad(kind, ByteArray(20)).size)
            assertEquals(MessagePadding.FIXED_BUCKET_BYTES, MessagePadding.pad(kind, ByteArray(1_400)).size)
        }
        // The largest photo plaintext (a 200-character caption, every character escaped) fits the bucket.
        val sha = "ab".repeat(32)
        val worst = MessagePlaintextCodec.encode(
            MessagePlaintext.Photo(ChatPhotoRef(sha, 8192, 8192), "\u0001".repeat(200)),
        )
        assertEquals(MessagePadding.FIXED_BUCKET_BYTES, MessagePadding.pad(MessageKind.PHOTO, worst).size)
    }

    @Test
    fun theMaximumPaddedSizeStaysWithinTheCiphertextCap() {
        val ciphertext = MessagePadding.MAX_PADDED_BYTES + MessagePadding.AEAD_OVERHEAD_BYTES
        assert(ciphertext <= ChatLimits.MAX_MESSAGE_CIPHERTEXT_BYTES)
        assertEquals(0, MessagePadding.MAX_PADDED_BYTES % MessagePadding.TEXT_STEP_BYTES)
    }

    @Test
    fun unpaddingReturnsExactlyThePlaintextIncludingTrailingZeros() {
        val plaintext = byteArrayOf(1, 2, 0, 0)
        val padded = MessagePadding.pad(MessageKind.TEXT, plaintext)
        assertContentEquals(byteArrayOf(0, 0, 0, 4, 1, 2, 0, 0), padded.copyOf(8))
        assertContentEquals(plaintext, MessagePadding.unpad(padded))
        assertContentEquals(ByteArray(0), MessagePadding.unpad(MessagePadding.pad(MessageKind.TEXT, ByteArray(0))))
    }

    @Test
    fun malformedPaddingIsRefused() {
        val padded = MessagePadding.pad(MessageKind.TEXT, byteArrayOf(7))
        assertNull(MessagePadding.unpad(padded.copyOf().also { it[10] = 1 }), "non-zero filler")
        assertNull(MessagePadding.unpad(padded.copyOf().also { it[3] = 61 }), "length past the end")
        assertNull(MessagePadding.unpad(padded.copyOf().also { it[0] = 0x80.toByte() }), "huge length")
        assertNull(MessagePadding.unpad(ByteArray(3)), "no length")
    }
}
