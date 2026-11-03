package dev.kpetriv.sprout.core.domainledger.chat

import dev.kpetriv.sprout.core.model.MAX_CHAT_TEXT_LENGTH
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class MessagePlaintextCodecTest {
    private val sha = "ab".repeat(32)
    private val samples = listOf(
        MessagePlaintext.Text("Great job! 🌱") to """{"text":"Great job! 🌱"}""",
        MessagePlaintext.Preset("thank-you", "🙏") to """{"emoji":"🙏","preset":"thank-you"}""",
        MessagePlaintext.Preset("hug") to """{"emoji":null,"preset":"hug"}""",
        MessagePlaintext.Voice(12_500) to """{"durationMs":12500}""",
        MessagePlaintext.Photo(ChatPhotoRef(sha, 1280, 960), "Tidy room") to
            """{"caption":"Tidy room","photoRef":{"height":960,"sha256":"$sha","width":1280}}""",
        MessagePlaintext.Photo(ChatPhotoRef(sha, 10, 20)) to
            """{"caption":null,"photoRef":{"height":20,"sha256":"$sha","width":10}}""",
    )

    @Test
    fun eachKindHasACanonicalFormAndRoundTrips() {
        for ((plaintext, canonical) in samples) {
            val bytes = MessagePlaintextCodec.encode(plaintext)
            assertEquals(canonical, bytes.decodeToString())
            assertEquals(plaintext, MessagePlaintextCodec.decode(plaintext.kind, bytes))
        }
    }

    @Test
    fun decodingIsStrictAboutKindKeysAndTypes() {
        val text = MessagePlaintextCodec.encode(MessagePlaintext.Text("hi"))
        assertNull(MessagePlaintextCodec.decode(MessageKind.PRESET, text))
        assertNull(MessagePlaintextCodec.decode(MessageKind.TEXT, """{"text":"hi","x":1}""".encodeToByteArray()))
        assertNull(MessagePlaintextCodec.decode(MessageKind.TEXT, """{"text":5}""".encodeToByteArray()))
        assertNull(MessagePlaintextCodec.decode(MessageKind.TEXT, """{"text":"   "}""".encodeToByteArray()))
        assertNull(MessagePlaintextCodec.decode(MessageKind.VOICE, """{"durationMs":"5"}""".encodeToByteArray()))
        assertNull(MessagePlaintextCodec.decode(MessageKind.VOICE, """{"durationMs":30001}""".encodeToByteArray()))
        assertNull(MessagePlaintextCodec.decode(MessageKind.VOICE, """{"durationMs":1.5}""".encodeToByteArray()))
        assertNull(MessagePlaintextCodec.decode(MessageKind.PRESET, """{"preset":"hug"}""".encodeToByteArray()))
        assertNull(
            MessagePlaintextCodec.decode(MessageKind.PRESET, """{"preset":"hug","emoji":1}""".encodeToByteArray()),
        )
        assertNull(
            MessagePlaintextCodec.decode(
                MessageKind.PHOTO,
                """{"caption":null,"photoRef":{"height":1,"sha256":"$sha","width":1,"x":0}}""".encodeToByteArray(),
            ),
        )
        assertNull(MessagePlaintextCodec.decode(MessageKind.TEXT, byteArrayOf(0x7b, 0xff.toByte(), 0x7d)))
        assertNull(MessagePlaintextCodec.decode(MessageKind.TEXT, "[]".encodeToByteArray()))
    }

    @Test
    fun limits() {
        MessagePlaintext.Text("x".repeat(MAX_CHAT_TEXT_LENGTH))
        assertFailsWith<IllegalArgumentException> { MessagePlaintext.Text("x".repeat(MAX_CHAT_TEXT_LENGTH + 1)) }
        assertFailsWith<IllegalArgumentException> { MessagePlaintext.Text("") }
        assertFailsWith<IllegalArgumentException> { MessagePlaintext.Voice(0) }
        assertFailsWith<IllegalArgumentException> { MessagePlaintext.Voice(ChatLimits.MAX_VOICE_NOTE_MILLIS + 1) }
        assertFailsWith<IllegalArgumentException> { MessagePlaintext.Preset("bad id") }
        assertFailsWith<IllegalArgumentException> { MessagePlaintext.Preset("ok", "x".repeat(17)) }
        assertFailsWith<IllegalArgumentException> { ChatPhotoRef("AB".repeat(32), 1, 1) }
        assertFailsWith<IllegalArgumentException> { ChatPhotoRef(sha, 0, 1) }
        assertFailsWith<IllegalArgumentException> { MessagePlaintext.Photo(ChatPhotoRef(sha, 1, 1), "c".repeat(201)) }
        // An unpaired surrogate cannot be encoded.
        assertFailsWith<CharacterCodingException> { MessagePlaintextCodec.encode(MessagePlaintext.Text("a\uD800b")) }
    }

    @Test
    fun theLongestTextFitsTheCiphertextLimitEvenWhenEveryCharacterIsEscaped() {
        val worst = MessagePlaintextCodec.encode(MessagePlaintext.Text("\u0001".repeat(MAX_CHAT_TEXT_LENGTH)))
        val aeadOverhead = 1 + 12 + 16
        kotlin.test.assertTrue(worst.size + aeadOverhead <= ChatLimits.MAX_MESSAGE_CIPHERTEXT_BYTES)
    }

    @Test
    fun aPlaintextThatCannotBeEncodedAgainDoesNotDecode() {
        // A JSON escape of a lone surrogate parses, but the result has no canonical (strict UTF-8) encoding.
        assertNull(MessagePlaintextCodec.decode(MessageKind.TEXT, """{"text":"\ud800"}""".encodeToByteArray()))
        assertNull(MessagePlaintextCodec.decode(MessageKind.TEXT, """{"text":"a\udfffb"}""".encodeToByteArray()))
        val caption = """{"caption":"\ud800","photoRef":{"height":1,"sha256":"$sha","width":1}}"""
        assertNull(MessagePlaintextCodec.decode(MessageKind.PHOTO, caption.encodeToByteArray()))
        // A correctly escaped pair is fine.
        val pair = MessagePlaintextCodec.decode(MessageKind.TEXT, """{"text":"\ud83c\udf31"}""".encodeToByteArray())
        assertEquals(MessagePlaintext.Text("🌱"), pair)
    }

    @Test
    fun oversizedDecodedTextDoesNotDecode() {
        val tooLong = "x".repeat(MAX_CHAT_TEXT_LENGTH + 1)
        assertNull(MessagePlaintextCodec.decode(MessageKind.TEXT, """{"text":"$tooLong"}""".encodeToByteArray()))
        // Escapes that expand past the limit once decoded.
        val escaped = "\\u0041".repeat(MAX_CHAT_TEXT_LENGTH + 1)
        assertNull(MessagePlaintextCodec.decode(MessageKind.TEXT, """{"text":"$escaped"}""".encodeToByteArray()))
    }

    @Test
    fun toStringNeverPrintsContent() {
        assertFalse(MessagePlaintext.Text("secret words").toString().contains("secret"))
        assertFalse(MessagePlaintext.Photo(ChatPhotoRef(sha, 1, 1), "secret").toString().contains("secret"))
        assertFalse(MessagePlaintext.Preset("hug", "🤗").toString().contains("🤗"))
    }
}
