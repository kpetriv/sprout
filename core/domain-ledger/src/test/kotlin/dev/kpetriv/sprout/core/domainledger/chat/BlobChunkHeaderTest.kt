package dev.kpetriv.sprout.core.domainledger.chat

import dev.kpetriv.sprout.core.domainledger.JvmSha256Hasher
import dev.kpetriv.sprout.core.domainledger.SoftwareSignatureCheck
import dev.kpetriv.sprout.core.model.BlobId
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.model.SubmissionId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BlobChunkHeaderTest {
    private val kid = ChatDevice("kid-tablet", Role.KID, thread.kidId)
    private val data = ByteArray(1000) { it.toByte() }
    private val header = BlobChunkHeader(
        householdId = household,
        blobId = BlobId("blob-1"),
        threadId = thread,
        kidId = thread.kidId,
        authorUid = kid.uid,
        authorDeviceId = kid.deviceId,
        lamport = 12,
        chunk = 1,
        chunks = 2,
        sha256 = "0f".repeat(32),
        epoch = 3,
    )

    @Test
    fun theSignatureCoversTheCanonicalHeaderAndTheChunkHash() {
        val canonical = """{"authorDeviceId":"kid-tablet","authorUid":"kid-uid-kid-1","blobId":"blob-1","chunk":1,""" +
            """"chunks":2,"epoch":3,"householdId":"house-1","kidId":"kid-1","lamport":12,"schemaVersion":1,""" +
            """"sha256":"${"0f".repeat(32)}","submissionId":null,"threadId":"kid-1"}"""
        val expected = "sprout/blob/v1\u0000$canonical\u0000".encodeToByteArray() + JvmSha256Hasher.sha256(data)
        assertContentEquals(expected, header.signedPayload(data))
        assertEquals("blob-1.1", header.docId)

        val signed = header.signWith(data, kid.signingKey::sign)
        assertTrue(signed.verifyWith(SoftwareSignatureCheck, kid.signingKey.publicKey, data))
        assertFalse(
            signed.verifyWith(
                SoftwareSignatureCheck,
                kid.signingKey.publicKey,
                data.copyOf().also {
                    it[0] = 9
                },
            ),
        )
        assertFalse(signed.copy(chunk = 0).verifyWith(SoftwareSignatureCheck, kid.signingKey.publicKey, data))
        assertFalse(header.verifyWith(SoftwareSignatureCheck, kid.signingKey.publicKey, data))
        assertFalse(
            signed.verifyWith(
                SoftwareSignatureCheck,
                kid.signingKey.publicKey,
                ByteArray(ChatLimits.MAX_BLOB_CHUNK_BYTES + 1),
            ),
        )
    }

    @Test
    fun choreBlobsCarryASubmissionInsteadOfAThread() {
        val chore = header.copy(threadId = null, submissionId = SubmissionId("sub-1"))
        assertNull(chore.context.threadId)
        assertFailsWith<IllegalArgumentException> { header.copy(submissionId = SubmissionId("sub-1")) }
        assertFailsWith<IllegalArgumentException> { header.copy(threadId = null) }
        assertFailsWith<IllegalArgumentException> { header.copy(kidId = otherKid) }
    }

    @Test
    fun headerInvariantsAndClosedJson() {
        assertFailsWith<IllegalArgumentException> { header.copy(chunk = 2) }
        assertFailsWith<IllegalArgumentException> { header.copy(chunks = 3, chunk = 0) }
        assertFailsWith<IllegalArgumentException> { header.copy(sha256 = "nope") }
        val tree = BlobJson.toJsonObject(header)
        assertEquals(header, BlobJson.decodeOrNull(tree))
        assertNull(BlobJson.decodeOrNull(JsonObject(tree + ("data" to JsonPrimitive("AA==")))))
    }

    @Test
    fun theContextBindsBlobThreadAuthorAndEpoch() {
        val context = header.context
        val canonical = """{"authorDeviceId":"kid-tablet","blobId":"blob-1","epoch":3,"householdId":"house-1",""" +
            """"kidId":"kid-1","submissionId":null,"threadId":"kid-1"}"""
        assertContentEquals("sprout/blob/v1\u0000$canonical".encodeToByteArray(), context.associatedData())
        assertEquals(context, header.copy(chunk = 0, lamport = 99).context)
    }
}
