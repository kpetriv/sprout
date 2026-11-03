package dev.kpetriv.sprout.core.sync

import dev.kpetriv.sprout.core.domainledger.BoundEncryptionKey
import dev.kpetriv.sprout.core.domainledger.DeviceStatus
import dev.kpetriv.sprout.core.domainledger.EncryptionKeyBindingPayload
import dev.kpetriv.sprout.core.domainledger.chat.MessageKind
import dev.kpetriv.sprout.core.model.BlobId
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.Role
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The fake server refuses what `firestore/firestore.rules` refuses: registry collections, and the chat collections
 * (`messages`, `blobs`, `threadKeys`) with their read scoping and size limits (M4 §3).
 */
class FakeBackendRulesTest {
    private val parent = TestIdentity("parent-device", "parent-uid", Role.PARENT)
    private val kid = TestIdentity("kid-device", "kid-uid", Role.KID, KID_1)
    private val otherKid = TestIdentity("kid2-device", "kid2-uid", Role.KID, KID_2)
    private val backend = FakeBackend(HOUSEHOLD).apply {
        registerDevice(parent.registered)
        registerDevice(kid.registered)
        registerDevice(otherKid.registered)
    }
    private val transport = FakeSyncTransport(backend, parent.deviceId, parent.uid, parent.role)
    private val kidTransport = FakeSyncTransport(backend, kid.deviceId, kid.uid, kid.role)
    private val otherKidTransport = FakeSyncTransport(backend, otherKid.deviceId, otherKid.uid, otherKid.role)

    private suspend fun FakeSyncTransport.pushMessages(vararg messages: JsonObject) =
        push(emptyList(), emptyList(), messages.toList(), emptyList())

    private suspend fun FakeSyncTransport.pushChunks(vararg chunks: BlobChunkJson) =
        push(emptyList(), emptyList(), emptyList(), chunks.toList())

    private fun codes(result: PushResult) = result.rejected.map { (it.second as SyncError.Permanent).code }

    private val denied = listOf(FakeBackend.PERMISSION_DENIED)

    @Test
    fun aParentCannotWriteRegistryDocuments() = runTest {
        val documents = FakeBackend.SERVER_ONLY.map { parent.signed(it, "c".repeat(64), lamport = 1) }

        val result = transport.push(emptyList(), documents)

        assertTrue(result.accepted.isEmpty())
        assertEquals(
            documents.map { PushItemId.ofDocument(it) to SyncError.Permanent(FakeBackend.PERMISSION_DENIED) },
            result.rejected,
        )
        assertTrue(backend.documents().isEmpty())
    }

    @Test
    fun everyClientTypeIsWritableByAParent() = runTest {
        val documents = DocTypes.all.map { parent.signed(it, "doc-1", lamport = 1) }

        val result = transport.push(emptyList(), documents)

        assertEquals(emptyList(), result.rejected)
        assertEquals(DocTypes.all.toSet(), backend.documents().map { it.docType }.toSet())
    }

    @Test
    fun noClientTypeIsAServerOnlyCollection() {
        assertEquals(10, DocTypes.all.size)
        assertTrue(DocTypes.THREAD_KEY in DocTypes.all)
        assertTrue(DocTypes.all.none { it in FakeBackend.SERVER_ONLY })
    }

    @Test
    fun anUnknownTypeIsRefusedBeforeTheRules() = runTest {
        val document = parent.signed("gift_card", "doc-1", lamport = 1)

        val result = transport.push(emptyList(), listOf(document))

        assertEquals(listOf(SyncError.Permanent(FakeBackend.UNKNOWN_DOC_TYPE)), result.rejected.map { it.second })
    }

    // ---- thread keys ----

    @Test
    fun threadKeysAreWrittenByParentsOnly() = runTest {
        val byParent = parent.signed(DocTypes.THREAD_KEY, "kid-1.0", lamport = 1)
        val byKid = kid.signed(DocTypes.THREAD_KEY, "kid-1.1", lamport = 1)

        assertEquals(emptyList(), transport.push(emptyList(), listOf(byParent)).rejected)
        assertEquals(denied, codes(kidTransport.push(emptyList(), listOf(byKid))))
        assertEquals(Role.PARENT, DocTypes.writerRole(DocTypes.THREAD_KEY))
    }

    // ---- messages ----

    @Test
    fun aParentWritesInEveryThreadAKidOnlyInItsOwn() = runTest {
        val byParent =
            transport.pushMessages(parent.message("m1", thread = KID_1), parent.message("m2", thread = KID_2))
        assertTrue(byParent.rejected.isEmpty())
        assertTrue(kidTransport.pushMessages(kid.message("m3")).rejected.isEmpty())

        assertEquals(denied, codes(kidTransport.pushMessages(kid.message("m4", thread = KID_2))))
        assertEquals(listOf("m1", "m2", "m3"), backend.messages().map { it.string("messageId") })
    }

    @Test
    fun theAuthorMustBeAnActiveDeviceOfTheCallerWithTheCallersRole() = runTest {
        // Another member's device, a claimed PARENT role from a kid.
        assertEquals(denied, codes(transport.pushMessages(kid.message("m1"))))
        assertEquals(denied, codes(kidTransport.pushMessages(kid.message("m2", authorRole = Role.PARENT))))

        backend.revoke(kid.deviceId, atLamport = 1)
        assertEquals(denied, codes(kidTransport.pushMessages(kid.message("m4"))))
        assertTrue(backend.messages().isEmpty())
    }

    @Test
    fun kidModeOnAParentPhoneWritesKidRoleMessagesInAnyThread() = runTest {
        val result = transport.pushMessages(
            parent.message("m1", thread = KID_1, authorRole = Role.KID),
            parent.message("m2", thread = KID_2, authorRole = Role.KID),
        )

        assertTrue(result.rejected.isEmpty())
        assertEquals(listOf("KID", "KID"), backend.messages().map { it.string("authorRole") })
    }

    @Test
    fun theCiphertextIsLimitedOnItsDecodedBytes() = runTest {
        val largest = parent.message("m1", ciphertextBytes = FakeBackend.MAX_MESSAGE_CIPHERTEXT_BYTES)
        val oversize = parent.message("m2").with("ciphertext", base64Of(FakeBackend.MAX_MESSAGE_CIPHERTEXT_BYTES + 1))

        assertTrue(transport.pushMessages(largest).rejected.isEmpty())
        assertEquals(denied, codes(transport.pushMessages(oversize)))
        assertEquals(denied, codes(transport.pushMessages(parent.message("m3").with("ciphertext", ""))))
    }

    @Test
    fun theKeySetIsClosedAndFieldsAreChecked() = runTest {
        val base = parent.message("m1")
        val refused = listOf(
            base.with("extra", "x"),
            JsonObject(base - "seq"),
            base.with("schemaVersion", 2),
            base.with("seq", 0),
            base.with("epoch", -1),
            base.with("kind", "VIDEO"),
            // A blob id exactly for VOICE and PHOTO.
            base.with("blobId", "blob-1"),
            parent.message("m2", kind = MessageKind.PHOTO).with("blobId", JsonNull),
        )
        refused.forEachIndexed { index, message ->
            val again = message.with("messageId", "bad-$index")
            assertEquals(denied, codes(transport.pushMessages(again)), "case $index")
        }
        assertTrue(transport.pushMessages(parent.message("m3", kind = MessageKind.VOICE)).rejected.isEmpty())
    }

    @Test
    fun messagesAreCreateOnlyAndIdempotent() = runTest {
        val message = parent.message("m1")

        assertTrue(transport.pushMessages(message).rejected.isEmpty())
        assertTrue(transport.pushMessages(message).rejected.isEmpty(), "an identical re-send is accepted")
        assertEquals(denied, codes(transport.pushMessages(parent.message("m1", seq = 2))))
        assertEquals(listOf(message), backend.messages())
    }

    @Test
    fun clientSideChecksComeFirst() = runTest {
        val message = parent.message("m1")

        assertEquals(listOf(FakeBackend.MALFORMED), codes(transport.pushMessages(JsonObject(message - "messageId"))))
        assertEquals(
            listOf(FakeBackend.MALFORMED),
            codes(transport.pushMessages(message.with("ciphertext", "not base64!"))),
        )
        assertEquals(
            listOf(FakeBackend.WRONG_HOUSEHOLD),
            codes(transport.pushMessages(message.with("householdId", "household-2"))),
        )
        assertEquals(listOf(FakeBackend.UNSIGNED), codes(transport.pushMessages(message.with("signature", JsonNull))))
    }

    @Test
    fun aKidPullsOnlyItsOwnThread() = runTest {
        transport.pushMessages(parent.message("p1", thread = KID_1), parent.message("p2", thread = KID_2))
        kidTransport.pushMessages(kid.message("k1"))
        otherKidTransport.pushMessages(otherKid.message("o1"))

        fun ids(page: PullPage) = page.messages.map { it.string("messageId") }
        assertEquals(listOf("p1", "p2", "k1", "o1"), ids(transport.pull(SyncCursor.START)))
        assertEquals(listOf("p1", "k1"), ids(kidTransport.pull(SyncCursor.START)))
        assertEquals(listOf("p2", "o1"), ids(otherKidTransport.pull(SyncCursor.START)))
    }

    @Test
    fun messagesPageWithTheirOwnCursor() = runTest {
        val small = FakeBackend(HOUSEHOLD, pageSize = 2).apply { registerDevice(parent.registered) }
        val paged = FakeSyncTransport(small, parent.deviceId, parent.uid, parent.role)
        paged.pushMessages(*(1..3).map { parent.message("m$it", seq = it.toLong()) }.toTypedArray())

        val first = paged.pull(SyncCursor.START)
        val second = paged.pull(first.next)

        assertEquals(2, first.messages.size)
        assertTrue(first.hasMore)
        assertEquals("2", first.next.perCollection[FakeBackend.MESSAGES])
        assertEquals(listOf("m3"), second.messages.map { it.string("messageId") })
    }

    // ---- blobs ----

    @Test
    fun blobChunksAreLimitedAndKidScoped() = runTest {
        val largest = kid.blobChunk("b1", chunk = 0, chunks = 2, size = FakeBackend.MAX_BLOB_CHUNK_BYTES)
        val second = kid.blobChunk("b1", chunk = 1, chunks = 2, size = 1)
        val oversize = kid.blobChunk("b2", size = FakeBackend.MAX_BLOB_CHUNK_BYTES + 1)
        val otherKids = kid.blobChunk("b3", thread = KID_2)
        val chorePhoto = kid.blobChunk("b4", thread = null, kid = KID_1, submission = "sub-1")

        assertTrue(kidTransport.pushChunks(largest, second, chorePhoto).rejected.isEmpty())
        assertEquals(denied, codes(kidTransport.pushChunks(oversize)))
        assertEquals(denied, codes(kidTransport.pushChunks(otherKids)))
        assertTrue(transport.pushChunks(parent.blobChunk("b5", thread = KID_2)).rejected.isEmpty())
        assertEquals(listOf("b1.0", "b1.1", "b4.0", "b5.0"), backend.blobs().map { it.docId })
    }

    @Test
    fun blobHeadersAreChecked() = runTest {
        val chunk = parent.blobChunk("b1")
        val header = chunk.header
        val refused = listOf(
            header.with("chunk", 2).with("chunks", 2),
            header.with("chunks", 3),
            header.with("chunk", 1).with("chunks", 1),
            header.with("submissionId", "sub-1"),
            header.with("threadId", "kid-2"),
            header.with("sha256", "xyz"),
            header.with("extra", "x"),
            JsonObject(header - "kidId"),
        )
        refused.forEachIndexed { index, bad ->
            val renamed = BlobChunkJson(bad.with("blobId", "bad-$index"), chunk.data)
            assertEquals(denied, codes(transport.pushChunks(renamed)), "case $index")
        }
        assertEquals(denied, codes(transport.pushChunks(BlobChunkJson(header.with("blobId", "empty"), ByteArray(0)))))
    }

    @Test
    fun blobChunksAreCreateOnlyAndIdempotent() = runTest {
        val chunk = parent.blobChunk("b1")

        assertTrue(transport.pushChunks(chunk).rejected.isEmpty())
        assertTrue(transport.pushChunks(chunk).rejected.isEmpty())
        assertEquals(denied, codes(transport.pushChunks(parent.blobChunk("b1", size = 65))))
    }

    @Test
    fun blobsAreFetchedByIdAndKidScoped() = runTest {
        kidTransport.pushChunks(kid.blobChunk("b1", chunk = 1, chunks = 2), kid.blobChunk("b1", chunk = 0, chunks = 2))
        otherKidTransport.pushChunks(otherKid.blobChunk("b2"))

        assertEquals(listOf("b1.0", "b1.1"), kidTransport.fetchBlob(BlobId("b1")).getOrThrow().map { it.docId })
        assertEquals(listOf("b2.0"), transport.fetchBlob(BlobId("b2")).getOrThrow().map { it.docId })
        assertEquals(emptyList(), kidTransport.fetchBlob(BlobId("missing")).getOrThrow())
        val refused = kidTransport.fetchBlob(BlobId("b2")).exceptionOrNull() as SyncException
        assertEquals(SyncError.Permanent(FakeBackend.PERMISSION_DENIED), refused.error)
        assertTrue(transport.pull(SyncCursor.START).messages.isEmpty(), "blobs are never pulled")
    }

    @Test
    fun theBackendStoresWhatWasSentByteForByte() = runTest {
        val chunk = parent.blobChunk("b1", size = 100)
        transport.pushChunks(chunk)

        assertContentEquals(chunk.data, backend.blobs().single().data)
    }

    // ---- encryption keys ----

    private fun bound(identity: TestIdentity, publicKey: ByteArray, hid: HouseholdId = HOUSEHOLD) = BoundEncryptionKey(
        hid,
        publicKey,
        identity.key.sign(EncryptionKeyBindingPayload.of(hid, identity.deviceId, publicKey)),
    )

    @Test
    fun anEncryptionKeyIsRegisteredOnceAndKeptAcrossStatusChanges() = runTest {
        val key = ByteArray(32) { 7 }
        val result = backend.registerEncryptionKey(kid.deviceId, bound(kid, key), SoftwareSignatureCheck)

        assertEquals(FakeBackend.EncryptionKeyRegistration.REGISTERED, result)
        assertEquals(
            FakeBackend.EncryptionKeyRegistration.UNCHANGED,
            backend.registerEncryptionKey(kid.deviceId, bound(kid, key), SoftwareSignatureCheck),
        )
        assertEquals(
            FakeBackend.EncryptionKeyRegistration.DIFFERENT_KEY,
            backend.registerEncryptionKey(kid.deviceId, bound(kid, ByteArray(32) { 8 }), SoftwareSignatureCheck),
        )
        val pulled = transport.pull(SyncCursor.START).devices.single { it.deviceId == kid.deviceId }
        assertContentEquals(key, pulled.verifiedEncryptionKey(SoftwareSignatureCheck))

        backend.revoke(kid.deviceId, atLamport = 5)
        val revoked = transport.pull(SyncCursor.START).devices.single { it.deviceId == kid.deviceId }
        assertEquals(DeviceStatus.Revoked(5), revoked.status)
        assertContentEquals(key, revoked.encryptionKey?.publicKey)
    }

    @Test
    fun anEncryptionKeyNeedsAValidBindingAndAnActiveDevice() = runTest {
        val key = ByteArray(32) { 1 }

        assertEquals(
            FakeBackend.EncryptionKeyRegistration.INVALID_BINDING,
            backend.registerEncryptionKey(kid.deviceId, bound(parent, key), SoftwareSignatureCheck),
        )
        assertEquals(
            FakeBackend.EncryptionKeyRegistration.INVALID_BINDING,
            backend.registerEncryptionKey(kid.deviceId, bound(kid, key, HouseholdId("other")), SoftwareSignatureCheck),
        )
        backend.setStatus(otherKid.deviceId, DeviceStatus.Pending)
        assertEquals(
            FakeBackend.EncryptionKeyRegistration.NOT_ACTIVE,
            backend.registerEncryptionKey(otherKid.deviceId, bound(otherKid, key), SoftwareSignatureCheck),
        )
        assertNull(transport.pull(SyncCursor.START).devices.single { it.deviceId == kid.deviceId }.encryptionKey)
    }
}
