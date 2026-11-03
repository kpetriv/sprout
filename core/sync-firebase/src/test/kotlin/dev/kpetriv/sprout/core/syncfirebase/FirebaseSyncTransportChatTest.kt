package dev.kpetriv.sprout.core.syncfirebase

import app.cash.turbine.test
import dev.kpetriv.sprout.core.domainledger.chat.MessageKind
import dev.kpetriv.sprout.core.model.BlobId
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.model.UserId
import dev.kpetriv.sprout.core.sync.PushItem
import dev.kpetriv.sprout.core.sync.PushItemId
import dev.kpetriv.sprout.core.sync.SyncCursor
import dev.kpetriv.sprout.core.sync.SyncError
import dev.kpetriv.sprout.core.sync.SyncException
import dev.kpetriv.sprout.core.syncfirebase.TestData.HID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The chat lanes of [FirebaseSyncTransport] (M4 §3): messages, blob chunks, read scoping, encryption keys. */
class FirebaseSyncTransportChatTest {
    private val firestore = FakeFirestore().apply {
        // Create-only, as the rules make messages and blobs.
        rule = { write, existing ->
            val createOnly = "/messages/" in write.path || "/blobs/" in write.path
            if (createOnly && existing != null) FirestoreCode.PERMISSION_DENIED else null
        }
    }
    private var kidScope: KidId? = null
    private var deviceId: DeviceId? = DeviceId(TestData.DEVICE)

    /** The session's role claim; a parent unless a test says otherwise (a kid session sets [kidScope]). */
    private var role: Role? = Role.PARENT
    private var now = java.time.Instant.parse("2026-11-03T12:00:00Z").toEpochMilli()

    private fun transport(pageSize: Int = FirebaseSyncTransport.PAGE_SIZE) = FirebaseSyncTransport(
        firestore = firestore,
        household = object : HouseholdScope {
            override suspend fun currentHouseholdId() = HID

            override suspend fun currentDeviceId() = deviceId

            override suspend fun currentUserId() = UserId(TestData.UID)

            override suspend fun currentKidId() = kidScope

            override suspend fun currentRole() = if (kidScope != null) Role.KID else role
        },
        foreground = MutableStateFlow(true),
        authProbe = { TestData.member() },
        pageSize = pageSize,
        batchSize = FirebaseSyncTransport.MAX_BATCH_WRITES,
        callTimeoutMs = 5_000,
        nowEpochMs = { now },
    )

    private fun seedHousehold() = firestore.seed("households/h1", mapOf("createdBy" to TestData.UID))

    @Test
    fun `messages are stored with the ciphertext as bytes and come back as the signed tree`() = runTest {
        val message = ChatData.message("m1", ciphertext = byteArrayOf(9, 8, 7))

        val result = transport().push(emptyList(), emptyList(), listOf(message), emptyList())

        assertEquals(listOf(PushItemId("message/m1")), result.accepted)
        val stored = firestore.docs.getValue("households/h1/messages/m1")
        assertEquals(FacadeBytes(byteArrayOf(9, 8, 7)), stored["ciphertext"])
        assertEquals(null, stored["blobId"])
        assertIs<ServerTime>(stored[FirestoreFacade.SERVER_RECEIVED_AT])
        val page = transport().pull(SyncCursor.START)
        assertEquals(listOf(message), page.messages)
        assertNotNull(page.next.perCollection[FirestoreLayout.MESSAGES])
    }

    @Test
    fun `blob chunks are committed alone with their quota counter, in outbox order`() = runTest {
        val first = ChatData.chunk("b1", chunk = 0, chunks = 2)
        val second = ChatData.chunk("b1", chunk = 1, chunks = 2, data = byteArrayOf(1))
        val message = ChatData.message("m1", kind = MessageKind.PHOTO)
        val event = TestData.event("evt-1")

        val result = transport().push(listOf(event), emptyList(), listOf(message), listOf(first, second))

        assertEquals(4, result.accepted.size, "rejected: ${result.rejected}")
        assertEquals(
            listOf(
                listOf("households/h1/events/evt-1"),
                listOf("households/h1/blobs/b1.0", "households/h1/quotas/20261103"),
                listOf("households/h1/blobs/b1.1", "households/h1/quotas/20261103"),
                listOf("households/h1/messages/m1"),
            ),
            firestore.commits.map { batch -> batch.map { it.path } },
        )
        assertEquals(
            mapOf("chunks" to 2L, "last" to "b1.1"),
            firestore.docs.getValue("households/h1/quotas/20261103"),
        )
        assertTrue(firestore.commits[1][1].merge)
        val stored = firestore.docs.getValue("households/h1/blobs/b1.1")
        assertEquals(FacadeBytes(byteArrayOf(1)), stored["data"])
        assertEquals(2L, stored["chunks"])
        assertEquals("kid-a", stored["kidId"])
    }

    @Test
    fun `malformed, foreign and unsigned chat items are refused unsent`() = runTest {
        val message = ChatData.message("m1")
        val noId = JsonObject(message - "messageId")
        val notBase64 = JsonObject(message + ("ciphertext" to JsonPrimitive("%%%")))
        val foreign = ChatData.message("m2", hid = HouseholdId("h2"))
        val unsigned = ChatData.message("m3", signature = null)
        val foreignChunk = ChatData.chunk("b1", hid = HouseholdId("h2"))
        val badChunk = dev.kpetriv.sprout.core.sync.BlobChunkJson(
            JsonObject(ChatData.chunk("b2").header + ("chunk" to JsonNull)),
            byteArrayOf(1),
        )

        // One push each: every one of them is refused before any write and would end its push (head of line).
        val codes = listOf(foreignChunk, badChunk).map { transport().push(listOf(PushItem.BlobChunk(it))) } +
            listOf(noId, notBase64, foreign, unsigned).map { transport().push(listOf(PushItem.Message(it))) }

        assertEquals(
            listOf("wrong-household", "malformed", "malformed", "malformed", "wrong-household", "unsigned"),
            codes.map { (it.rejected.single().second as SyncError.Permanent).code },
        )
        assertTrue(firestore.commits.isEmpty())
    }

    @Test
    fun `a refused rewrite is accepted only when the stored copy is identical`() = runTest {
        seedHousehold()
        val message = ChatData.message("m1")
        val chunk = ChatData.chunk("b1")
        transport().push(emptyList(), emptyList(), listOf(message), listOf(chunk))

        val again = transport().push(emptyList(), emptyList(), listOf(message), listOf(chunk))
        val changedChunk = transport().push(listOf(PushItem.BlobChunk(ChatData.chunk("b1", data = byteArrayOf(5)))))
        val changedMessage = transport().push(emptyList(), emptyList(), listOf(ChatData.message("m1", seq = 2)))

        assertEquals(2, again.accepted.size, "rejected: ${again.rejected}")
        // A chunk is never refused for good (D-M4-22): it is retried with the backoff.
        assertEquals(
            listOf(SyncError.Transient(), SyncError.Permanent("permission-denied")),
            (changedChunk.rejected + changedMessage.rejected).map { it.second },
        )
    }

    @Test
    fun `messages page with their own cursor`() = runTest {
        val messages = (1..3).map { ChatData.message("m$it", seq = it.toLong()) }
        messages.forEach { transport().push(emptyList(), emptyList(), listOf(it), emptyList()) }

        val first = transport(pageSize = 2).pull(SyncCursor.START)
        val second = transport(pageSize = 2).pull(first.next)

        assertEquals(messages.take(2), first.messages)
        assertTrue(first.hasMore)
        assertEquals(messages.drop(2), second.messages)
    }

    @Test
    fun `a parent reads every thread, a kid only its own`() = runTest {
        transport().pull(SyncCursor.START)
        assertEquals(null, firestore.queries.single { it.first.endsWith("/messages") }.second)

        firestore.queries.clear()
        kidScope = KidId("kid-a")
        transport().pull(SyncCursor.START)
        assertEquals(
            FieldEquals("threadId", "kid-a"),
            firestore.queries.single { it.first.endsWith("/messages") }.second,
        )
    }

    @Test
    fun `without a kid claim the scope comes from the device's own registry entry`() = runTest {
        role = null
        firestore.seed(
            "households/h1/devices/${TestData.DEVICE}",
            mapOf(
                "uid" to "kid-uid",
                "role" to "KID",
                "kidId" to "kid-b",
                "publicKey" to "AQID",
                "status" to "ACTIVE",
                "attestation" to "NONE",
            ),
        )
        firestore.seed("households/h1/messages/m1", ChatData.message("m1", thread = "kid-a").plain())
        firestore.seed("households/h1/messages/m2", ChatData.message("m2", thread = "kid-b").plain())

        val page = transport().pull(SyncCursor.START)

        assertEquals(listOf("m2"), page.messages.map { (it["messageId"] as JsonPrimitive).content })
    }

    @Test
    fun `a session with no kid, no role and no own registry entry skips the messages lane`() = runTest {
        role = null
        firestore.seed("households/h1/messages/m1", ChatData.message("m1").plain())

        val page = transport().pull(SyncCursor.START)

        assertTrue(page.messages.isEmpty())
        assertTrue(firestore.queries.none { it.first.endsWith("/messages") }, "no unscoped query is issued")
        assertNull(page.next.perCollection[FirestoreLayout.MESSAGES])
        transport().changes().test {
            testScheduler.advanceUntilIdle()
            assertTrue(firestore.listenFilters.keys.none { it.first.endsWith("/messages") })
            assertTrue(firestore.listenFilters.keys.any { it.first.endsWith("/events") })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a blob with a missing later chunk is incomplete, a retryable failure`() = runTest {
        transport().push(emptyList(), emptyList(), emptyList(), listOf(ChatData.chunk("b1", chunk = 0, chunks = 2)))

        val failure = transport().fetchBlob(BlobId("b1")).exceptionOrNull()

        val incomplete = assertIs<dev.kpetriv.sprout.core.sync.BlobIncompleteException>(failure)
        assertEquals(1 to 2, incomplete.stored to incomplete.expected)
        assertEquals(SyncError.Transient(), incomplete.error)
    }

    @Test
    fun `a chunk over the daily budget is transient until midnight and stops the chat lane only`() = runTest {
        seedHousehold()
        firestore.seed("households/h1/quotas/20261103", mapOf("chunks" to 60L, "last" to "x.0"))
        firestore.rule = { write, existing ->
            val quota = write.path.endsWith("/quotas/20261103")
            if (quota && ((existing?.get("chunks") as? Long) ?: 0) >= 60) FirestoreCode.PERMISSION_DENIED else null
        }
        val chunk = ChatData.chunk("b1")
        val message = ChatData.message("m1", kind = MessageKind.PHOTO)
        val events = listOf(TestData.event("evt-1"), TestData.event("evt-2"))

        val result = transport().push(
            listOf(PushItem.BlobChunk(chunk), PushItem.Message(message)) + events.map(PushItem::Event),
        )

        assertEquals(
            listOf(PushItemId.ofBlobChunk(chunk) to SyncError.Transient(retryAfterMs = 12 * 3_600_000L)),
            result.rejected,
        )
        assertNull(firestore.docs["households/h1/messages/m1"], "the message behind the chunk is not processed")
        // The ledger lane does not wait for the chat lane (D-M4-22).
        assertEquals(events.map(PushItemId::ofEvent), result.accepted)
        assertNotNull(firestore.docs["households/h1/events/evt-2"])
    }

    @Test
    fun `a refused event does not hold back the message behind it`() = runTest {
        seedHousehold()
        firestore.rule = { write, _ -> if (write.path.endsWith("evt-1")) FirestoreCode.PERMISSION_DENIED else null }
        val refused = TestData.event("evt-1")
        val message = ChatData.message("m1")

        val result = transport().push(
            listOf(PushItem.Event(refused), PushItem.Event(TestData.event("evt-2")), PushItem.Message(message)),
        )

        assertEquals(listOf(PushItemId.ofEvent(refused) to SyncError.Permanent("permission-denied")), result.rejected)
        assertEquals(listOf(PushItemId.ofMessage(message)), result.accepted)
        assertNull(firestore.docs["households/h1/events/evt-2"], "the next event waits behind the refused one")
        assertNotNull(firestore.docs["households/h1/messages/m1"])
    }

    @Test
    fun `a chunk refused for another day is transient and its next attempt recomputes the day`() = runTest {
        seedHousehold()
        // The device's clock says 23:55 on the 3rd; the server's is already on the 4th: only that counter is accepted.
        now = java.time.Instant.parse("2026-11-03T23:55:00Z").toEpochMilli()
        firestore.rule = { write, _ ->
            if ("/quotas/" in write.path && !write.path.endsWith("20261104")) FirestoreCode.PERMISSION_DENIED else null
        }
        val chunk = ChatData.chunk("b1")

        val refused = transport().push(listOf(PushItem.BlobChunk(chunk)))

        // Not over the budget: the engine's backoff, never a permanent (blocking) refusal (audit A2-M3).
        assertEquals(listOf(PushItemId.ofBlobChunk(chunk) to SyncError.Transient()), refused.rejected)
        assertNull(firestore.docs["households/h1/blobs/b1.0"])

        // A device whose date is days off is refused the same way: transient, so only its chat lane waits.
        now = java.time.Instant.parse("2026-11-10T09:00:00Z").toEpochMilli()
        val again = transport().push(listOf(PushItem.BlobChunk(chunk)))
        assertEquals(SyncError.Transient(), again.rejected.single().second)

        now = java.time.Instant.parse("2026-11-04T00:01:00Z").toEpochMilli()
        val retried = transport().push(listOf(PushItem.BlobChunk(chunk)))

        assertEquals(1, retried.accepted.size, "rejected: ${retried.rejected}")
        assertEquals(1L, firestore.docs.getValue("households/h1/quotas/20261104")["chunks"])
    }

    @Test
    fun `a chunk refused with invalid-argument or an unreadable copy is transient too`() = runTest {
        seedHousehold()
        firestore.commitFailures += listOf(FirestoreCode.INVALID_ARGUMENT, FirestoreCode.INVALID_ARGUMENT)
        val chunk = ChatData.chunk("b1")
        val message = ChatData.message("m1", kind = MessageKind.PHOTO)

        val invalid = transport().push(listOf(PushItem.BlobChunk(chunk), PushItem.Message(message)))

        assertEquals(listOf(PushItemId.ofBlobChunk(chunk) to SyncError.Transient()), invalid.rejected)
        assertTrue(invalid.accepted.isEmpty(), "the message waits behind its chunk")

        firestore.rule = { write, _ -> if ("/blobs/" in write.path) FirestoreCode.PERMISSION_DENIED else null }
        firestore.readFailures["households/h1/blobs/b1.0"] = FirestoreCode.PERMISSION_DENIED
        val again = transport().push(listOf(PushItem.BlobChunk(chunk)))
        assertEquals(SyncError.Transient(), again.rejected.single().second)
    }

    @Test
    fun `the message listener of a kid session is scoped too`() = runTest {
        kidScope = KidId("kid-a")

        transport().changes().test {
            testScheduler.advanceUntilIdle()
            val key = "households/h1/messages" to true
            assertEquals(FieldEquals("threadId", "kid-a"), firestore.listenFilters[key])
            assertNull(firestore.listenFilters["households/h1/events" to true])
            firestore.listeners.getValue(key).emit(Unit)
            assertEquals(dev.kpetriv.sprout.core.sync.RemoteChange("messages"), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `fetchBlob reads every chunk by id and never pulls blobs`() = runTest {
        val first = ChatData.chunk("b1", chunk = 0, chunks = 2)
        val second = ChatData.chunk("b1", chunk = 1, chunks = 2, data = byteArrayOf(7))
        transport().push(emptyList(), emptyList(), emptyList(), listOf(first, second))

        assertEquals(listOf(first, second), transport().fetchBlob(BlobId("b1")).getOrThrow())
        assertEquals(emptyList(), transport().fetchBlob(BlobId("missing")).getOrThrow())
        assertTrue(firestore.queries.none { it.first.endsWith("/blobs") })
    }

    @Test
    fun `a refused blob read is a sync error inside the result`() = runTest {
        seedHousehold()
        firestore.readFailures["households/h1/blobs/b1.0"] = FirestoreCode.PERMISSION_DENIED

        val failure = transport().fetchBlob(BlobId("b1")).exceptionOrNull()

        assertEquals(SyncError.Permanent("permission-denied"), (failure as SyncException).error)
    }

    @Test
    fun `a device row carries its encryption key unverified, and only with both fields`() {
        val base = mapOf(
            "uid" to "u1",
            "role" to "PARENT",
            "publicKey" to "AQID",
            "status" to "ACTIVE",
            "attestation" to "NONE",
        )
        val key = ByteArray(32) { 4 }
        val withKey = base + mapOf(
            "encryptionKey" to kotlin.io.encoding.Base64.Default.encode(key),
            "encryptionKeySignature" to "MEUC",
        )

        val device = StoredShapes.deviceOf(FacadeDocument(TestData.DEVICE, withKey), HID)
        val half = StoredShapes.deviceOf(FacadeDocument(TestData.DEVICE, base + ("encryptionKey" to "AQID")), HID)

        assertContentEquals(key, device?.encryptionKey?.publicKey)
        assertEquals(HID, device?.encryptionKey?.householdId)
        assertNotNull(half)
        assertNull(half.encryptionKey)
    }

    private fun JsonObject.plain(): Map<String, Any?> =
        StoredShapes.messageFields(this)!!.fields + (FirestoreFacade.SERVER_RECEIVED_AT to ServerTimestamp)
}
