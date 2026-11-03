package dev.kpetriv.sprout.core.crypto.chat

import dev.kpetriv.sprout.core.crypto.AesGcmAead
import dev.kpetriv.sprout.core.crypto.DeviceSigner
import dev.kpetriv.sprout.core.crypto.JceEcdsaVerifier
import dev.kpetriv.sprout.core.crypto.JceX25519KeyAgreement
import dev.kpetriv.sprout.core.domainledger.chat.ChatPhotoRef
import dev.kpetriv.sprout.core.domainledger.chat.MessageEnvelope
import dev.kpetriv.sprout.core.domainledger.chat.MessageHeader
import dev.kpetriv.sprout.core.domainledger.chat.MessageKind
import dev.kpetriv.sprout.core.domainledger.chat.MessagePlaintext
import dev.kpetriv.sprout.core.model.BlobId
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.MessageId
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.model.ThreadId
import dev.kpetriv.sprout.core.model.UserId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import java.security.SecureRandom
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class MessageCipherTest {
    private val random = SecureRandom()
    private val cipher = MessageCipher(AesGcmAead(random))
    private val key = ThreadKey.generate(thread, 1, random)
    private val parent = CryptoDevice("parent-phone", Role.PARENT, JceX25519KeyAgreement(random))
    private val sentAt = Instant.fromEpochMilliseconds(1_793_000_000_000)

    private fun header(id: String = "m-1", kind: MessageKind = MessageKind.TEXT) = MessageHeader(
        householdId = household,
        threadId = thread,
        messageId = MessageId(id),
        authorUid = UserId("parent-uid"),
        authorDeviceId = parent.deviceId,
        authorRole = Role.PARENT,
        epoch = 1,
        kind = kind,
        blobId = if (kind.hasBlob) BlobId("blob-$id") else null,
    )

    private fun seal(header: MessageHeader, plaintext: MessagePlaintext, seq: Long = 1) = MessageEnvelope.of(
        header,
        seq,
        lamport = 10,
        createdAt = sentAt,
        ciphertext = cipher.encrypt(key, header, plaintext),
    )

    @Test
    fun everyKindRoundTrips() {
        val samples = listOf(
            MessagePlaintext.Text("Well done today!"),
            MessagePlaintext.Preset("love-you", "❤️"),
            MessagePlaintext.Voice(29_999),
            MessagePlaintext.Photo(ChatPhotoRef("cd".repeat(32), 1280, 720), "Look!"),
        )
        samples.forEachIndexed { index, plaintext ->
            val envelope = seal(header("m-$index", plaintext.kind), plaintext)
            assertEquals(plaintext, cipher.decrypt(key, envelope))
            assertFalse(Base64.Default.decode(envelope.ciphertext).decodeToString().contains("Well done"))
        }
    }

    @Test
    fun aTransplantedCiphertextFailsAuthentication() {
        val original = seal(header("m-1"), MessagePlaintext.Text("secret"))
        // Same thread key, another message: the ciphertext copied into an envelope with a new id.
        val transplanted = MessageEnvelope.of(header("m-2"), 2, 11, sentAt, original.ciphertextBytes())
        assertFailsWith<MessageDecryptionException> { cipher.decrypt(key, transplanted) }
        // Into another author's envelope.
        val otherAuthor = header("m-1").copy(authorDeviceId = DeviceId("kid-tablet"), authorRole = Role.KID)
        assertFailsWith<MessageDecryptionException> {
            cipher.decrypt(key, MessageEnvelope.of(otherAuthor, 1, 10, sentAt, original.ciphertextBytes()))
        }
        // Relabelled as a preset (same plaintext bytes would not even decode, but authentication fails first).
        val asPreset = header("m-1", MessageKind.PRESET)
        assertFailsWith<MessageDecryptionException> {
            cipher.decrypt(key, MessageEnvelope.of(asPreset, 1, 10, sentAt, original.ciphertextBytes()))
        }
        // seq, lamport and createdAt are not bound into the AEAD (the signature covers them).
        val reordered = original.copy(seq = 7, lamport = 99, createdAt = sentAt.plus(kotlin.time.Duration.parse("1h")))
        assertEquals(MessagePlaintext.Text("secret"), cipher.decrypt(key, reordered))
    }

    @Test
    fun theWrongEpochKeyOrATamperedCiphertextFails() {
        val envelope = seal(header(), MessagePlaintext.Text("hello"))
        ThreadKey.generate(thread, 1, random).use { other ->
            assertFailsWith<MessageDecryptionException> { cipher.decrypt(other, envelope) }
        }
        ThreadKey.fromBytes(thread, 2, key.copyBytes()).use { laterEpoch ->
            assertFailsWith<MessageDecryptionException> { cipher.decrypt(laterEpoch, envelope) }
        }
        ThreadKey.fromBytes(ThreadId.of(KidId("kid-2")), 1, key.copyBytes()).use { otherThread ->
            assertFailsWith<MessageDecryptionException> { cipher.decrypt(otherThread, envelope) }
        }
        val bytes = envelope.ciphertextBytes().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        val tampered = envelope.copy(ciphertext = Base64.Default.encode(bytes))
        assertFailsWith<MessageDecryptionException> { cipher.decrypt(key, tampered) }
    }

    @Test
    fun encryptionChecksKeyAndKind() {
        ThreadKey.generate(thread, 0, random).use { epochZero ->
            assertFailsWith<IllegalArgumentException> {
                cipher.encrypt(epochZero, header(), MessagePlaintext.Text("x"))
            }
        }
        assertFailsWith<IllegalArgumentException> { cipher.encrypt(key, header(), MessagePlaintext.Voice(10)) }
        val longest = cipher.encrypt(key, header(), MessagePlaintext.Text("\u0001".repeat(1000)))
        assertTrue(longest.size <= 8192)
    }

    @Test
    fun ciphertextSizesRevealOnlyTheBucket() {
        val short = seal(header("m-1"), MessagePlaintext.Text("hi")).ciphertextBytes().size
        val longer = seal(header("m-2"), MessagePlaintext.Text("a".repeat(40))).ciphertextBytes().size
        assertEquals(short, longer, "texts of 2 and 40 characters share the first 64-byte bucket")
        assertEquals(64 + 29, short)
        val fixed = listOf(
            MessagePlaintext.Preset("hug"),
            MessagePlaintext.Preset("love-you", "❤️"),
            MessagePlaintext.Voice(1),
            MessagePlaintext.Photo(ChatPhotoRef("cd".repeat(32), 1, 1), "c".repeat(200)),
        ).mapIndexed { index, plaintext ->
            seal(header("f-$index", plaintext.kind), plaintext).ciphertextBytes().size
        }
        assertEquals(setOf(1536 + 29), fixed.toSet())
    }

    @Test
    fun anAuthenticatedCiphertextWithBadPaddingOrContentIsUndecodableNotLocked() {
        val aead = AesGcmAead(random)
        fun sealRaw(id: String, bytes: ByteArray): MessageEnvelope {
            val h = header(id)
            return MessageEnvelope.of(h, 1, 10, sentAt, aead.encrypt(key.key, bytes, h.associatedData()))
        }
        // Not padded at all: the length prefix points past the end.
        assertFailsWith<MessagePlaintextException> {
            cipher.decrypt(key, sealRaw("p-1", "{\"text\":\"x\"}".encodeToByteArray()))
        }
        // Padded, but a lone surrogate inside.
        val surrogate = dev.kpetriv.sprout.core.domainledger.chat.MessagePadding.pad(
            MessageKind.TEXT,
            "{\"text\":\"\\ud800\"}".encodeToByteArray(),
        )
        assertFailsWith<MessagePlaintextException> { cipher.decrypt(key, sealRaw("p-2", surrogate)) }
        // A wrong key is a plain decryption failure, not an undecodable plaintext.
        ThreadKey.generate(thread, 1, random).use { other ->
            val failure = assertFailsWith<MessageDecryptionException> {
                cipher.decrypt(other, seal(header("p-3"), MessagePlaintext.Text("hi")))
            }
            assertFalse(failure is MessagePlaintextException)
        }
    }

    @Test
    fun theSignerSignsTheEnvelopeForReceivers() = runTest {
        val signer = MessageSigner(DeviceSigner(parent.deviceKeys, Dispatchers.Unconfined))
        val signed = signer.sign(seal(header(), MessagePlaintext.Text("hi")))
        val check = JceEcdsaVerifier().asSignatureCheck()
        assertTrue(signed.verifyWith(check, parent.deviceKeys.publicKey().encoded))
        assertFalse(signed.copy(seq = 2).verifyWith(check, parent.deviceKeys.publicKey().encoded))
    }
}
