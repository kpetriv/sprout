package dev.kpetriv.sprout.core.sync

import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.model.UserId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DocumentEnvelopeTest {
    private val parent = TestIdentity("parent-device", "parent-uid", Role.PARENT)
    private val other = TestIdentity("other-device", "other-uid", Role.PARENT)

    private val golden = DocumentEnvelope(
        householdId = HOUSEHOLD,
        docType = DocTypes.REWARD,
        docId = "reward-1",
        authorUid = UserId("u1"),
        authorDeviceId = DeviceId("d1"),
        lamport = 7,
        payload = buildJsonObject {
            put("name", "Ice cream \"large\"")
            put("cost", 250)
            putJsonArray("tags") {
                add("b")
                add("a")
            }
            put("note", "line\nbreak")
            put("minutes", null as String?)
        },
        signature = "c2lnbmF0dXJl",
    )

    @Test
    fun canonicalFormIsStable() {
        val tree = DocumentJsonCodec.toJsonObject(golden)
        assertEquals(
            """{"authorDeviceId":"d1","authorUid":"u1","deleted":false,"docId":"reward-1","docType":"reward",""" +
                """"householdId":"household-1","lamport":7,"payload":{"cost":250,"minutes":null,""" +
                """"name":"Ice cream \"large\"","note":"line\nbreak","tags":["b","a"]},"schemaVersion":1}""",
            canonicalDocumentString(tree),
        )
    }

    @Test
    fun signedPayloadIsDomainSeparated() {
        val expected = "sprout/document/v1".encodeToByteArray() + 0.toByte() +
            canonicalDocumentString(DocumentJsonCodec.toJsonObject(golden)).encodeToByteArray()
        assertContentEquals(expected, golden.signedPayload())
        // The signature is excluded, so signing does not change what is signed.
        assertContentEquals(golden.copy(signature = null).signedPayload(), golden.signedPayload())
        assertContentEquals(golden.signedPayload(), documentSignedPayloadOf(DocumentJsonCodec.toJsonObject(golden)))
    }

    @Test
    fun signAndVerifyRoundTrip() {
        val signed = parent.signed(DocTypes.REWARD, "reward-1", lamport = 3)
        assertNotNull(signed.signature)
        assertTrue(signed.verifyWith(SoftwareSignatureCheck, parent.key.publicKey))
    }

    @Test
    fun tamperingBreaksTheSignature() {
        val signed = parent.signed(DocTypes.REWARD, "reward-1", lamport = 3)
        val tampered = listOf(
            signed.copy(lamport = 4),
            signed.copy(deleted = true),
            signed.copy(docId = "reward-2"),
            signed.copy(authorUid = UserId("someone-else")),
            signed.copy(payload = JsonObject(signed.payload + ("name" to JsonPrimitive("free")))),
            signed.copy(schemaVersion = 2),
        )
        tampered.forEach { assertFalse(it.verifyWith(SoftwareSignatureCheck, parent.key.publicKey), "$it") }
        assertFalse(signed.verifyWith(SoftwareSignatureCheck, other.key.publicKey))
    }

    @Test
    fun malformedSignaturesFailClosed() {
        val signed = parent.signed(DocTypes.REWARD, "reward-1", lamport = 3)
        assertFalse(signed.copy(signature = null).verifyWith(SoftwareSignatureCheck, parent.key.publicKey))
        assertFalse(signed.copy(signature = "not base64!").verifyWith(SoftwareSignatureCheck, parent.key.publicKey))
        val garbage = signed.copy(signature = Base64.encode(ByteArray(8)))
        assertFalse(garbage.verifyWith(SoftwareSignatureCheck, parent.key.publicKey))
        assertFalse(signed.verifyWith(SoftwareSignatureCheck, ByteArray(4)))
    }

    @Test
    fun nonCanonicalPayloadFailsVerificationInsteadOfThrowing() {
        val withFraction = parent.envelope(
            DocTypes.REWARD,
            "reward-1",
            lamport = 3,
            payload = buildJsonObject { put("cost", 2.5) },
        ).copy(signature = Base64.encode(ByteArray(64)))
        assertFalse(withFraction.verifyWith(SoftwareSignatureCheck, parent.key.publicKey))
    }

    @Test
    fun codecRoundTripsAndRejectsGarbage() {
        val signed = parent.signed(DocTypes.REWARD, "reward-1", lamport = 3)
        val decoded = DocumentJsonCodec.decodeOrNull(DocumentJsonCodec.encode(signed))
        assertEquals(signed, decoded)
        assertTrue(decoded!!.verifyWith(SoftwareSignatureCheck, parent.key.publicKey))
        assertNull(DocumentJsonCodec.decodeOrNull("{"))
        assertNull(DocumentJsonCodec.decodeOrNull("""{"docType":"reward"}"""))
        val badId = DocumentJsonCodec.encode(signed).replace("reward-1", "reward 1")
        assertNull(DocumentJsonCodec.decodeOrNull(badId))
    }

    @Test
    fun unknownTopLevelFieldsAreDroppedAndNoLongerVerify() {
        val signed = parent.signed(DocTypes.REWARD, "reward-1", lamport = 3)
        val tree = DocumentJsonCodec.toJsonObject(signed)
        val newer = JsonObject(tree + ("addedLater" to JsonPrimitive("x")))
        val decoded = assertNotNull(DocumentJsonCodec.decodeOrNull(newer))
        // The decoded envelope verifies only because the extra field was not signed; a field the author did sign
        // is covered by the tree form, which differs from the decoded one.
        assertTrue(decoded.verifyWith(SoftwareSignatureCheck, parent.key.publicKey))
        assertFalse(documentSignedPayloadOf(newer).contentEquals(decoded.signedPayload()))
    }

    @Test
    fun toStringNeverShowsThePayload() {
        val text = golden.toString()
        assertFalse("Ice cream" in text)
        assertFalse("c2lnbmF0dXJl" in text)
    }
}
