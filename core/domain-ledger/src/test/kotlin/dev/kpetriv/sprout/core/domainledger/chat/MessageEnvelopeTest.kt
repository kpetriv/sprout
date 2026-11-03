package dev.kpetriv.sprout.core.domainledger.chat

import dev.kpetriv.sprout.core.domainledger.SoftwareKey
import dev.kpetriv.sprout.core.domainledger.SoftwareSignatureCheck
import dev.kpetriv.sprout.core.model.Role
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MessageEnvelopeTest {
    private val parent = ChatDevice("parent-phone", Role.PARENT)

    @Test
    fun theSignedPayloadIsTheDomainAndTheCanonicalEnvelopeWithoutSignature() {
        val envelope = parent.message(seq = 3, lamport = 17, ciphertext = byteArrayOf(1, 2, 3))
        val canonical = """{"authorDeviceId":"parent-phone","authorRole":"PARENT","authorUid":"parent-uid",""" +
            """"blobId":null,"ciphertext":"AQID","createdAt":1793000000000,"epoch":0,"householdId":"house-1",""" +
            """"kind":"TEXT","lamport":17,"messageId":"m-3","schemaVersion":1,"seq":3,"threadId":"kid-1"}"""
        assertContentEquals("sprout/message/v1\u0000$canonical".encodeToByteArray(), envelope.signedPayload())
        assertTrue(envelope.verifyWith(SoftwareSignatureCheck, parent.signingKey.publicKey))
    }

    @Test
    fun theAssociatedDataBindsTheHeaderFieldsOnly() {
        val header = parent.header(messageId = "m-9", kind = MessageKind.PHOTO, epoch = 4)
        val canonical = """{"authorDeviceId":"parent-phone","authorRole":"PARENT","authorUid":"parent-uid",""" +
            """"blobId":"b-m-9","epoch":4,"householdId":"house-1","kind":"PHOTO","messageId":"m-9",""" +
            """"schemaVersion":1,"threadId":"kid-1"}"""
        assertContentEquals("sprout/message/v1\u0000$canonical".encodeToByteArray(), header.associatedData())
        val envelope = MessageEnvelope.of(header, seq = 5, lamport = 9, createdAt = sentAt, ciphertext = ByteArray(8))
        assertEquals(header, envelope.header)
        assertContentEquals(header.associatedData(), envelope.header.associatedData())
    }

    @Test
    fun anyChangeBreaksTheSignature() {
        val envelope = parent.message(seq = 1)
        val key = parent.signingKey.publicKey
        assertFalse(envelope.copy(seq = 2).verifyWith(SoftwareSignatureCheck, key))
        assertFalse(envelope.copy(lamport = 99).verifyWith(SoftwareSignatureCheck, key))
        assertFalse(
            envelope.copy(ciphertext = Base64.Default.encode(ByteArray(40))).verifyWith(SoftwareSignatureCheck, key),
        )
        assertFalse(envelope.verifyWith(SoftwareSignatureCheck, SoftwareKey().publicKey))
        assertFalse(envelope.copy(signature = null).verifyWith(SoftwareSignatureCheck, key))
        assertFalse(envelope.copy(signature = "%%").verifyWith(SoftwareSignatureCheck, key))
    }

    @Test
    fun jsonRoundTripsAndTheTopLevelIsClosed() {
        val envelope = parent.message()
        val tree = MessageJson.toJsonObject(envelope)
        assertEquals(envelope, MessageJson.decodeOrNull(tree))
        assertEquals(envelope, MessageJson.decodeOrNull(MessageJson.encode(envelope)))
        assertNull(MessageJson.decodeOrNull(JsonObject(tree + ("extra" to JsonPrimitive(1)))))
        // A missing blobId and an explicit null decode (and so canonicalise) the same.
        val withoutBlob = JsonObject(tree - "blobId")
        assertEquals(envelope, MessageJson.decodeOrNull(withoutBlob))
        assertEquals(JsonNull, tree["blobId"])
    }

    @Test
    fun envelopeInvariants() {
        val envelope = parent.message()
        assertFailsWith<IllegalArgumentException> { envelope.copy(seq = 0) }
        assertFailsWith<IllegalArgumentException> { envelope.copy(epoch = -1) }
        assertFailsWith<IllegalArgumentException> {
            envelope.copy(createdAt = kotlin.time.Instant.fromEpochSeconds(1, nanosecondAdjustment = 1))
        }
        assertFailsWith<IllegalArgumentException> { envelope.copy(kind = MessageKind.VOICE) }
        assertFailsWith<IllegalArgumentException> { envelope.copy(ciphertext = "") }
        assertFailsWith<IllegalArgumentException> { envelope.copy(ciphertext = "not base64") }
        val max = Base64.Default.encode(ByteArray(ChatLimits.MAX_MESSAGE_CIPHERTEXT_BYTES))
        assertNotNull(envelope.copy(ciphertext = max))
        val over = Base64.Default.encode(ByteArray(ChatLimits.MAX_MESSAGE_CIPHERTEXT_BYTES + 1))
        assertFailsWith<IllegalArgumentException> { envelope.copy(ciphertext = over) }
        assertFailsWith<IllegalArgumentException> {
            parent.header(kind = MessageKind.TEXT).copy(blobId = dev.kpetriv.sprout.core.model.BlobId("b"))
        }
    }

    @Test
    fun toStringCarriesNoContent() {
        val envelope = parent.message()
        assertFalse(envelope.toString().contains(envelope.ciphertext))
    }
}
