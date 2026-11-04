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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

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
        injectDocument(parent.signed(DocTypes.KID, KID_1.value, lamport = 1))
        injectDocument(parent.signed(DocTypes.KID, KID_2.value, lamport = 1))
    }

    /** A thread key document for [thread] and [epoch] created by [by], with its per-creator docId. */
    private fun threadKey(
        by: TestIdentity,
        thread: String = KID_1.value,
        epoch: Long = 0,
        lamport: Long = 1,
        docId: String = "$thread.$epoch.${by.deviceId.value}",
        createdBy: String = by.deviceId.value,
        deleted: Boolean = false,
    ): DocumentEnvelope = by.signed(
        DocTypes.THREAD_KEY,
        docId,
        lamport,
        payload = buildJsonObject {
            put("threadId", thread)
            put("epoch", epoch)
            put("createdByDeviceId", createdBy)
            put("wraps", buildJsonObject { put(by.deviceId.value, buildJsonObject { put("wrapped", "AAAA") }) })
        },
        deleted = deleted,
    )

    /** A `household_policy` document by the parent: the v1 keys plus [extra]. */
    private fun policy(extra: JsonObject, lamport: Long = 2): DocumentEnvelope = parent.signed(
        DocTypes.HOUSEHOLD_POLICY,
        HOUSEHOLD.value,
        lamport,
        payload = JsonObject(
            buildJsonObject {
                put("fiatCurrency", "GBP")
                put("requireParentSignature", true)
            } + extra,
        ),
    )

    private val transport = FakeSyncTransport(backend, parent.deviceId, parent.uid, parent.role)
    private val kidTransport = FakeSyncTransport(backend, kid.deviceId, kid.uid, kid.role)
    private val otherKidTransport = FakeSyncTransport(backend, otherKid.deviceId, otherKid.uid, otherKid.role)

    private suspend fun FakeSyncTransport.pushMessages(vararg messages: JsonObject) =
        push(emptyList(), emptyList(), messages.toList(), emptyList())

    private suspend fun FakeSyncTransport.pushChunks(vararg chunks: BlobChunkJson) =
        push(emptyList(), emptyList(), emptyList(), chunks.toList())

    private fun codes(result: PushResult) = result.rejected.map { it.second.code }

    private val denied = listOf(FakeBackend.PERMISSION_DENIED)

    /** How a rules refusal of a blob chunk is reported (D-M4-22): transient, never permanent. */
    private val deferred = listOf(SyncError.Transient().code)

    @Test
    fun aParentCannotWriteRegistryDocuments() = runTest {
        val documents = FakeBackend.SERVER_ONLY.map { parent.signed(it, "c".repeat(64), lamport = 1) }

        val result = transport.push(emptyList(), documents)

        assertTrue(result.accepted.isEmpty())
        assertEquals(
            documents.map { PushItemId.ofDocument(it) to SyncError.Permanent(FakeBackend.PERMISSION_DENIED) },
            result.rejected,
        )
        assertTrue(backend.documents().none { it.docType in FakeBackend.SERVER_ONLY })
    }

    @Test
    fun everyClientTypeIsWritableByAParent() = runTest {
        val documents = DocTypes.all.map {
            when (it) {
                DocTypes.THREAD_KEY -> threadKey(parent)
                DocTypes.HOUSEHOLD_POLICY -> policy(buildJsonObject {})
                else -> parent.signed(it, "doc-1", lamport = 2)
            }
        }

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
        val byParent = threadKey(parent)
        val byKid = threadKey(kid, epoch = 1)

        assertEquals(emptyList(), transport.push(emptyList(), listOf(byParent)).rejected)
        assertEquals(denied, codes(kidTransport.push(emptyList(), listOf(byKid))))
        assertEquals(Role.PARENT, DocTypes.writerRole(DocTypes.THREAD_KEY))
    }

    @Test
    fun threadKeysAreHardened() = runTest {
        val second = TestIdentity("parent2-device", "parent-uid", Role.PARENT)
        backend.registerDevice(second.registered)
        val secondTransport = FakeSyncTransport(backend, second.deviceId, second.uid, second.role)
        val refused = listOf(
            // The old "<threadId>.<epoch>" id, and ids that do not name the creator.
            threadKey(parent, docId = "${KID_1.value}.0"),
            threadKey(parent, docId = "${KID_1.value}.1.${parent.deviceId.value}"),
            threadKey(parent, createdBy = second.deviceId.value, docId = "${KID_1.value}.0.${second.deviceId.value}"),
            // No tombstones, and a sane lamport.
            threadKey(parent, deleted = true),
            threadKey(parent, lamport = FakeBackend.MAX_THREAD_KEY_LAMPORT + 1),
        )
        refused.forEachIndexed { index, document ->
            assertEquals(denied, codes(transport.push(emptyList(), listOf(document))), "case $index")
        }

        assertEquals(emptyList(), transport.push(emptyList(), listOf(threadKey(parent, lamport = 2))).rejected)
        // Two parents rotating the same epoch each write their own document.
        assertEquals(emptyList(), secondTransport.push(emptyList(), listOf(threadKey(second, lamport = 1))).rejected)
        assertEquals(2, backend.documents().count { it.docType == DocTypes.THREAD_KEY })
        // Only the creator updates its document: another device's newer copy under that id is refused.
        val hijack = second.signed(
            DocTypes.THREAD_KEY,
            "${KID_1.value}.0.${parent.deviceId.value}",
            lamport = 9,
            payload = threadKey(parent).payload,
        )
        assertEquals(denied, codes(secondTransport.push(emptyList(), listOf(hijack))))
        assertEquals(emptyList(), transport.push(emptyList(), listOf(threadKey(parent, lamport = 3))).rejected)
    }

    // ---- push order ----

    @Test
    fun aPushIsProcessedInOrderAndStopsAtABlockingRefusal() = runTest {
        val chunk = parent.blobChunk("b1")
        val refusedMessage = parent.message("m1").with("seq", 0)
        val behind = listOf(
            PushItem.Message(parent.message("m2", seq = 2)),
            PushItem.Document(parent.signed(DocTypes.REWARD, "reward-1", lamport = 1)),
        )

        val result = transport.push(
            listOf(PushItem.BlobChunk(chunk), PushItem.Message(refusedMessage)) + behind,
        )

        assertEquals(
            listOf(PushItemId.ofBlobChunk(chunk), PushItemId.ofDocument(parent.signed(DocTypes.REWARD, "reward-1", 1))),
            result.accepted,
        )
        assertEquals(listOf(PushItemId.ofMessage(refusedMessage)), result.rejected.map { it.first })
        assertTrue(backend.messages().isEmpty(), "nothing behind the refused message in the chat lane is written")
        // The ledger lane does not wait for the chat lane (D-M4-22).
        assertNotNull(backend.document(DocTypes.REWARD, "reward-1"))
    }

    @Test
    fun aRefusedDocumentDoesNotStopThePush() = runTest {
        val refused = parent.signed("gift_card", "doc-1", lamport = 1)
        val message = parent.message("m1")

        val result = transport.push(listOf(PushItem.Document(refused), PushItem.Message(message)))

        assertEquals(listOf(PushItemId.ofMessage(message)), result.accepted)
        assertEquals(1, result.rejected.size)
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
        assertEquals(deferred, codes(kidTransport.pushChunks(oversize)))
        assertEquals(deferred, codes(kidTransport.pushChunks(otherKids)))
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
            assertEquals(deferred, codes(transport.pushChunks(renamed)), "case $index")
        }
        assertEquals(deferred, codes(transport.pushChunks(BlobChunkJson(header.with("blobId", "empty"), ByteArray(0)))))
    }

    @Test
    fun blobChunksAreCreateOnlyAndIdempotent() = runTest {
        val chunk = parent.blobChunk("b1")

        assertTrue(transport.pushChunks(chunk).rejected.isEmpty())
        assertTrue(transport.pushChunks(chunk).rejected.isEmpty())
        assertEquals(deferred, codes(transport.pushChunks(parent.blobChunk("b1", size = 65))))
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
    fun aBlobNamesAKidTheHouseholdHas() = runTest {
        val unknownKid = dev.kpetriv.sprout.core.model.KidId("kid-unknown")

        assertEquals(deferred, codes(transport.pushChunks(parent.blobChunk("b1", thread = unknownKid))))
    }

    @Test
    fun aHouseholdStoresAtMostSixtyNewChunksPerUtcDay() = runTest {
        val day = 20_000L * 86_400_000
        backend.nowEpochMs = { day + 3_600_000 }
        val stored = (0 until FakeBackend.DAILY_BLOB_CHUNKS).map { n -> parent.blobChunk("b$n") }
        stored.forEachIndexed { n, chunk -> assertTrue(transport.pushChunks(chunk).rejected.isEmpty(), "chunk $n") }
        val over = parent.blobChunk("over")
        val behind = parent.message("m1")
        val event = parent.event("e1", 1)

        val result = transport.push(listOf(PushItem.BlobChunk(over), PushItem.Message(behind), PushItem.Event(event)))

        assertEquals(listOf(PushItemId.ofBlobChunk(over) to SyncError.Transient(23 * 3_600_000L)), result.rejected)
        assertEquals(listOf(PushItemId.ofEvent(event)), result.accepted, "the chat lane stops, the ledger lane goes on")
        // A re-send of a stored chunk is not a new chunk; the next UTC day has a fresh budget.
        assertTrue(transport.pushChunks(stored.first()).rejected.isEmpty())
        backend.nowEpochMs = { day + 86_400_000 }
        assertTrue(transport.pushChunks(over).rejected.isEmpty())
    }

    @Test
    fun aBlobWithAMissingLaterChunkIsIncomplete() = runTest {
        transport.pushChunks(parent.blobChunk("b1", chunk = 0, chunks = 2))

        val failure = transport.fetchBlob(BlobId("b1")).exceptionOrNull()

        assertEquals(BlobIncompleteException::class, failure!!::class)
        assertEquals(SyncError.Transient(), (failure as SyncException).error)
        assertEquals(1 to 2, (failure as BlobIncompleteException).stored to failure.expected)
        transport.pushChunks(parent.blobChunk("b1", chunk = 1, chunks = 2))
        assertEquals(2, transport.fetchBlob(BlobId("b1")).getOrThrow().size)
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

    @Test
    fun theHouseholdPolicyTakesTheV2SettingsWithinTheirBounds() = runTest {
        val good = policy(
            buildJsonObject {
                put("chatRetentionDays", 30)
                put("dailyGameCapMinor", 0)
            },
        )
        assertEquals(emptyList(), transport.push(emptyList(), listOf(good)).rejected)
        val nulls = policy(
            buildJsonObject {
                put("chatRetentionDays", JsonNull)
                put("dailyGameCapMinor", 10_000)
            },
            lamport = 3,
        )
        assertEquals(emptyList(), transport.push(emptyList(), listOf(nulls)).rejected)

        val bad = listOf(
            buildJsonObject { put("chatRetentionDays", 0) },
            buildJsonObject { put("chatRetentionDays", 3651) },
            buildJsonObject { put("chatRetentionDays", "30") },
            buildJsonObject { put("dailyGameCapMinor", -1) },
            buildJsonObject { put("dailyGameCapMinor", 10_001) },
            buildJsonObject { put("purgeChatAt", 1) },
        ).mapIndexed { index, extra -> policy(extra, lamport = 10L + index) }
        for (document in bad) {
            assertEquals(denied, codes(transport.push(emptyList(), listOf(document))), document.payload.toString())
        }
        assertEquals(3L, backend.document(DocTypes.HOUSEHOLD_POLICY, HOUSEHOLD.value)?.lamport)
        // A kid never writes the policy.
        val kidPolicy = kid.signed(DocTypes.HOUSEHOLD_POLICY, HOUSEHOLD.value, lamport = 50)
        assertEquals(denied, codes(kidTransport.push(emptyList(), listOf(kidPolicy))))
    }

    @Test
    fun pruneMessagesDeletesWhatIsOlderThanTheCutAndOnlyForAParent() = runTest {
        val day = 86_400_000L
        val created = CREATED_AT.toEpochMilliseconds()
        // Stored 50 days before the messages' signed date: the server's own stamp decides.
        backend.nowEpochMs = { created - 50 * day }
        transport.pushMessages(parent.message("m-old", seq = 1))
        transport.pushChunks(parent.blobChunk("b-orphan"), parent.blobChunk("blob-m-new"))
        transport.pushChunks(parent.blobChunk("p-photo", thread = null, kid = KID_1, submission = "sub-1"))
        backend.nowEpochMs = { created }
        transport.pushMessages(parent.message("m-new", seq = 2, kind = MessageKind.PHOTO))
        backend.nowEpochMs = { created + day }
        val now = Instant.fromEpochMilliseconds(created + day)

        fun refusal(result: Result<FakeBackend.PruneCounts>) =
            (result.exceptionOrNull() as? FakeBackend.CallableRefusedException)?.code

        assertEquals("failed-precondition", refusal(backend.pruneMessages(parent.deviceId, now - 30.days, false)))
        transport.push(emptyList(), listOf(policy(buildJsonObject { put("chatRetentionDays", 30) })))
        assertEquals(FakeBackend.PERMISSION_DENIED, refusal(backend.pruneMessages(kid.deviceId, now, true)))
        assertEquals("invalid-argument", refusal(backend.pruneMessages(parent.deviceId, now - 31.days, false)))
        assertEquals("invalid-argument", refusal(backend.pruneMessages(parent.deviceId, now + 1.days, true)))

        val counts = backend.pruneMessages(parent.deviceId, now - 30.days, false).getOrThrow()

        assertEquals(FakeBackend.PruneCounts(messages = 1, blobChunks = 1), counts)
        assertEquals(listOf("m-new"), backend.messages().map { it.string("messageId") })
        assertEquals(
            setOf("blob-m-new.0", "p-photo.0"),
            backend.blobs().map { it.docId }.toSet(),
            "a chunk a kept message names, and chore photos, stay",
        )
        // A purge takes everything before now; pulls after it simply never see the deleted rows.
        assertEquals(1, backend.pruneMessages(parent.deviceId, now, true).getOrThrow().messages)
        assertTrue(backend.messages().isEmpty())
    }
}
