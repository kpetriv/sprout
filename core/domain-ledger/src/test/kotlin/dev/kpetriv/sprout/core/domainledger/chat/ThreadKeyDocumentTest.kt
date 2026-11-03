package dev.kpetriv.sprout.core.domainledger.chat

import dev.kpetriv.sprout.core.domainledger.CanonicalEncoder
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.Role
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ThreadKeyDocumentTest {
    private val parent = ChatDevice("parent-phone", Role.PARENT)
    private val kid = ChatDevice("kid-tablet", Role.KID, thread.kidId)

    @Test
    fun payloadHasTheDocumentedShapeAndRoundTrips() {
        val doc = keyDoc(3, parent, kid)
        assertEquals("kid-1.3", doc.docId)
        assertEquals(
            """{"createdByDeviceId":"parent-phone","epoch":3,"threadId":"kid-1",""" +
                """"wraps":{"kid-tablet":{"wrapped":"$sampleWrap"},"parent-phone":{"wrapped":"$sampleWrap"}}}""",
            CanonicalEncoder.encodeTreeToString(doc.toPayload()),
        )
        assertEquals(doc, ThreadKeyDocument.fromPayload(doc.toPayload()))
        assertTrue(doc.matchesDocId("kid-1.3"))
        assertContentEquals(Base64.Default.decode(sampleWrap), doc.wrappedFor(kid.deviceId))
        assertNull(doc.wrappedFor(DeviceId("stranger")))
    }

    @Test
    fun parsingIsStrict() {
        val payload = keyDoc(0, parent, kid).toPayload()
        val extraKey = JsonObject(payload + ("note" to JsonPrimitive("x")))
        assertNull(ThreadKeyDocument.fromPayload(extraKey))
        val stringEpoch = JsonObject(payload + ("epoch" to JsonPrimitive("0")))
        assertNull(ThreadKeyDocument.fromPayload(stringEpoch))
        val wraps = payload.getValue("wraps").jsonObject
        val extraWrapKey = JsonObject(
            payload + (
                "wraps" to JsonObject(
                    wraps +
                        (
                            "kid-tablet" to
                                JsonObject(mapOf("wrapped" to JsonPrimitive(sampleWrap), "x" to JsonPrimitive(1)))
                            ),
                )
                ),
        )
        assertNull(ThreadKeyDocument.fromPayload(extraWrapKey))
        val badId = JsonObject(payload + ("wraps" to JsonObject(wraps + ("bad id" to wraps.getValue("kid-tablet")))))
        assertNull(ThreadKeyDocument.fromPayload(badId))
        val notBase64 = JsonObject(
            payload +
                ("wraps" to JsonObject(wraps + ("kid-tablet" to JsonObject(mapOf("wrapped" to JsonPrimitive("!!")))))),
        )
        assertNull(ThreadKeyDocument.fromPayload(notBase64))
    }

    @Test
    fun theCreatorWrapsForItselfAndWrapsAreBounded() {
        assertFailsWith<IllegalArgumentException> {
            ThreadKeyDocument(thread, 0, parent.deviceId, wrapsFor(kid))
        }
        val many = (0..ChatLimits.MAX_THREAD_KEY_WRAPS).map { ChatDevice("parent-$it", Role.PARENT) }
        assertFailsWith<IllegalArgumentException> {
            ThreadKeyDocument(thread, 0, many.first().deviceId, wrapsFor(*many.toTypedArray()))
        }
        assertFailsWith<IllegalArgumentException> {
            ThreadKeyDocument(thread, 0, parent.deviceId, mapOf(parent.deviceId to "A".repeat(132)))
        }
        assertFailsWith<IllegalArgumentException> { keyDoc(-1, parent) }
    }

    @Test
    fun theLargestDocumentFitsTheRulesBudget() {
        val devices = (1..ChatLimits.MAX_THREAD_KEY_WRAPS).map { ChatDevice("d".repeat(36) + it, Role.PARENT) }
        val wrapped = Base64.Default.encode(ByteArray(61))
        val doc = ThreadKeyDocument(
            thread,
            Long.MAX_VALUE,
            devices.first().deviceId,
            devices.associate {
                it.deviceId to
                    wrapped
            },
        )
        assertEquals(84, wrapped.length)
        assertTrue(doc.canonicalPayload().size < 4096, "payload is ${doc.canonicalPayload().size} bytes")
    }

    @Test
    fun wrapFormatLayouts() {
        assertContentEquals(
            "sprout/thread-key-wrap/v1\u0000kid-1\u00007".encodeToByteArray(),
            ThreadKeyWrapFormat.info(thread, 7),
        )
        assertContentEquals(
            "sprout/thread-key-wrap/v1\u0000house-1\u0000kid-1\u00007\u0000parent-phone\u0000kid-tablet"
                .encodeToByteArray(),
            ThreadKeyWrapFormat.associatedData(household, thread, 7, parent.deviceId, kid.deviceId),
        )
        assertEquals(32, ThreadKeyWrapFormat.salt(household).size)
    }
}
