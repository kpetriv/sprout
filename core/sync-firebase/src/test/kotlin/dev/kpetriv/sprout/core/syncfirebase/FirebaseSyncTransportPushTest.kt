package dev.kpetriv.sprout.core.syncfirebase

import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.sync.DocTypes
import dev.kpetriv.sprout.core.sync.PushItemId
import dev.kpetriv.sprout.core.sync.SyncError
import dev.kpetriv.sprout.core.sync.SyncException
import dev.kpetriv.sprout.core.syncfirebase.TestData.HID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FirebaseSyncTransportPushTest {
    private val firestore = FakeFirestore()
    private var hid: HouseholdId? = HID

    /** What a forced token refresh returns; a member of [HID] by default. */
    private var refreshed: AuthState? = TestData.member()
    private var deviceId: DeviceId? = DeviceId(TestData.DEVICE)

    private fun transport(batchSize: Int = FirebaseSyncTransport.MAX_BATCH_WRITES) = FirebaseSyncTransport(
        firestore = firestore,
        household = object : HouseholdScope {
            override suspend fun currentHouseholdId() = hid

            override suspend fun currentDeviceId() = deviceId
        },
        foreground = MutableStateFlow(true),
        authProbe = { refreshed },
        pageSize = FirebaseSyncTransport.PAGE_SIZE,
        batchSize = batchSize,
        callTimeoutMs = 5_000,
    )

    /** Firestore rules in miniature: events and parent keys are create-only, documents last-writer-wins. */
    private val rules: (SetWrite, Map<String, Any?>?) -> FirestoreCode? = { write, existing ->
        val denied = when {
            existing == null -> false

            "/events/" in write.path || "/parentKeys/" in write.path -> true

            else -> {
                val newLamport = write.data["lamport"] as Long
                val oldLamport = existing["lamport"] as Long
                val newer = newLamport > oldLamport ||
                    (
                        newLamport == oldLamport &&
                            (write.data["authorDeviceId"] as String) > (existing["authorDeviceId"] as String)
                        )
                !newer
            }
        }
        if (denied) FirestoreCode.PERMISSION_DENIED else null
    }

    @Test
    fun `events are stored flattened at events-eventId with a server stamp`() = runTest {
        val event = TestData.event("evt-1")

        val result = transport().push(listOf(event), emptyList())

        assertEquals(listOf(PushItemId.ofEvent(event)), result.accepted)
        val stored = firestore.docs.getValue("households/h1/events/evt-1")
        assertEquals("evt-1", stored["eventId"])
        assertEquals(1L, stored["lamport"])
        assertEquals("manual_adjustment", stored["type"])
        assertEquals(250L, stored["amount"])
        assertTrue("prevHash" in stored && stored["prevHash"] == null, "explicit nulls are kept")
        assertFalse("envelope" in stored)
        assertTrue(stored[FirestoreFacade.SERVER_RECEIVED_AT] is ServerTime)
    }

    @Test
    fun `documents are stored as their envelope fields with a server stamp`() = runTest {
        val document = TestData.document()

        val result = transport().push(emptyList(), listOf(document))

        assertEquals(listOf(PushItemId.ofDocument(document)), result.accepted)
        val stored = firestore.docs.getValue("households/h1/chores/doc-1")
        assertEquals(
            setOf(
                "schemaVersion", "householdId", "docType", "docId", "authorUid", "authorDeviceId", "lamport", "deleted",
                "payload", "signature", FirestoreFacade.SERVER_RECEIVED_AT,
            ),
            stored.keys,
        )
        assertEquals(mapOf("id" to "doc-1", "title" to "Feed the cat", "points" to 3L), stored["payload"])
    }

    @Test
    fun `every document type goes to its own collection`() = runTest {
        val documents = DocTypes.all.filter { it != DocTypes.PARENT_KEY }.map { TestData.document(docType = it) }

        transport().push(emptyList(), documents)

        assertEquals(
            setOf(
                "kids", "accounts", "householdPolicy", "chores", "choreSubmissions", "rewards", "allowanceSchedules",
                "savingsGoals", "interestPolicy",
            ),
            firestore.docs.keys.map { it.split('/')[2] }.toSet(),
        )
    }

    @Test
    fun `a parent key document is never written - keys are registered by the server`() = runTest {
        val key = TestData.parentKeyDocument()
        val unsigned = TestData.document(docType = DocTypes.PARENT_KEY, docId = "c".repeat(64), signature = null)

        val result = transport().push(emptyList(), listOf(key, unsigned))

        assertEquals(
            listOf(key, unsigned).map { PushItemId.ofDocument(it) to SyncError.Permanent("unsupported") },
            result.rejected,
        )
        assertTrue(firestore.commits.isEmpty())
    }

    @Test
    fun `writes go out in batches of at most the batch size`() = runTest {
        val events = (1..850).map { TestData.event("evt-$it") }

        val result = transport().push(events, emptyList())

        assertEquals(850, result.accepted.size)
        assertEquals(listOf(400, 400, 50), firestore.commits.map { it.size })
    }

    @Test
    fun `a refused batch is retried item by item and the refusal lands on the right item`() = runTest {
        firestore.rule = { write, _ -> if (write.path.endsWith("evt-2")) FirestoreCode.PERMISSION_DENIED else null }
        firestore.seed("households/h1", mapOf("createdBy" to "x"))
        val events = (1..3).map { TestData.event("evt-$it") }

        val result = transport().push(events, emptyList())

        assertEquals(
            listOf("evt-1", "evt-3"),
            result.accepted.map {
                it.value.substringAfter('/').substringBefore(':')
            },
        )
        assertEquals(listOf(PushItemId.ofEvent(events[1]) to SyncError.Permanent("permission-denied")), result.rejected)
    }

    @Test
    fun `an event already stored with the same hash is accepted`() = runTest {
        firestore.rule = rules
        firestore.seed("households/h1", mapOf("createdBy" to "x"))
        val event = TestData.event("evt-1")
        transport().push(listOf(event), emptyList())

        val again = transport().push(listOf(event, TestData.event("evt-2")), emptyList())

        assertEquals(2, again.accepted.size)
        assertTrue(again.rejected.isEmpty())
    }

    @Test
    fun `a conflicting copy of an event is kept under eventId__hash`() = runTest {
        firestore.rule = rules
        firestore.seed("households/h1", mapOf("createdBy" to "x"))
        transport().push(listOf(TestData.event("evt-1", hash = TestData.hash(1))), emptyList())
        val conflicting = TestData.event("evt-1", hash = TestData.hash(2))

        val result = transport().push(listOf(conflicting), emptyList())

        assertEquals(listOf(PushItemId.ofEvent(conflicting)), result.accepted)
        assertEquals(TestData.hash(1), firestore.docs.getValue("households/h1/events/evt-1")["hash"])
        assertEquals(
            TestData.hash(2),
            firestore.docs.getValue("households/h1/events/evt-1__${TestData.hash(2)}")["hash"],
        )
        // Pushing the conflicting copy again is idempotent too.
        assertEquals(1, transport().push(listOf(conflicting), emptyList()).accepted.size)
    }

    @Test
    fun `a document the server copy already beats is accepted, a refused newer one is not`() = runTest {
        firestore.rule = rules
        firestore.seed("households/h1", mapOf("createdBy" to "x"))
        transport().push(emptyList(), listOf(TestData.document(lamport = 9)))

        val older = TestData.document(lamport = 4)
        val same = TestData.document(lamport = 9)
        assertEquals(2, transport().push(emptyList(), listOf(older, same)).accepted.size)

        firestore.rule = { _, _ -> FirestoreCode.PERMISSION_DENIED }
        val newer = TestData.document(lamport = 10)
        val result = transport().push(emptyList(), listOf(newer))
        assertEquals(listOf(PushItemId.ofDocument(newer) to SyncError.Permanent("permission-denied")), result.rejected)
    }

    @Test
    fun `a bare refusal while the household is unreadable is transient, not gone`() = runTest {
        firestore.rule = { _, _ -> FirestoreCode.PERMISSION_DENIED }
        firestore.readFailures["households/h1"] = FirestoreCode.PERMISSION_DENIED
        val event = TestData.event("evt-1")

        val result = transport().push(listOf(event), listOf(TestData.document()))

        assertEquals(listOf(SyncError.Transient(), SyncError.Transient()), result.rejected.map { it.second })
        // The household document does not exist (or reads as such): still no evidence.
        firestore.readFailures.clear()
        assertEquals(SyncError.Transient(), transport().push(listOf(event), emptyList()).rejected.single().second)
    }

    @Test
    fun `a refusal is household gone when a forced refresh finds the account deleted or disabled`() = runTest {
        firestore.rule = { _, _ -> FirestoreCode.PERMISSION_DENIED }
        refreshed = AuthState.UserGone

        val failure = assertFailsWith<SyncException> { transport().push(listOf(TestData.event("evt-1")), emptyList()) }

        assertEquals(SyncError.HouseholdGone, failure.error)
    }

    @Test
    fun `a refusal is household gone when the hid claim is gone after a forced refresh`() = runTest {
        firestore.rule = { _, _ -> FirestoreCode.PERMISSION_DENIED }
        firestore.seed("households/h1", mapOf("createdBy" to "x"))
        refreshed = TestData.member(hid = null)

        val failure = assertFailsWith<SyncException> { transport().push(emptyList(), listOf(TestData.document())) }

        assertEquals(SyncError.HouseholdGone, failure.error)
    }

    @Test
    fun `a refusal is household gone when this device reads as revoked in the registry`() = runTest {
        firestore.rule = { _, _ -> FirestoreCode.PERMISSION_DENIED }
        firestore.seed("households/h1", mapOf("createdBy" to "x"))
        firestore.seed("households/h1/devices/${TestData.DEVICE}", mapOf("status" to "REVOKED"))

        val failure = assertFailsWith<SyncException> { transport().push(listOf(TestData.event("evt-1")), emptyList()) }

        assertEquals(SyncError.HouseholdGone, failure.error)
        // Without a device id to look up, the same refusal is the item's own.
        deviceId = null
        val result = transport().push(listOf(TestData.event("evt-1")), emptyList())
        assertEquals(SyncError.Permanent("permission-denied"), result.rejected.single().second)
    }

    @Test
    fun `an unauthenticated write is household gone only when the account is gone`() = runTest {
        firestore.commitFailures += FirestoreCode.UNAUTHENTICATED
        assertEquals(
            SyncError.Unauthenticated,
            transport().push(listOf(TestData.event("evt-1")), emptyList()).rejected.single().second,
        )

        firestore.commitFailures += FirestoreCode.UNAUTHENTICATED
        refreshed = AuthState.UserGone
        val failure = assertFailsWith<SyncException> { transport().push(listOf(TestData.event("evt-1")), emptyList()) }
        assertEquals(SyncError.HouseholdGone, failure.error)
    }

    @Test
    fun `an unauthenticated batch is rejected as such and later batches are not sent`() = runTest {
        firestore.commitFailures += listOf(null, FirestoreCode.UNAUTHENTICATED)
        val events = (1..5).map { TestData.event("evt-$it") }

        val result = transport(batchSize = 2).push(events, emptyList())

        assertEquals(2, result.accepted.size)
        assertEquals(List(2) { SyncError.Unauthenticated }, result.rejected.map { it.second })
        assertEquals(2, firestore.commits.size)
    }

    @Test
    fun `network trouble is transient and a quota error says when to retry`() = runTest {
        firestore.commitFailures += FirestoreCode.UNAVAILABLE
        val unavailable = transport().push(listOf(TestData.event("evt-1")), emptyList())
        assertEquals(SyncError.Transient(), unavailable.rejected.single().second)

        firestore.commitFailures += FirestoreCode.RESOURCE_EXHAUSTED
        val exhausted = transport().push(listOf(TestData.event("evt-1")), emptyList())
        assertEquals(SyncError.Transient(60_000), exhausted.rejected.single().second)
    }

    @Test
    fun `a commit that never completes times out as transient`() = runTest {
        firestore.hangCommits = true

        val result = transport().push(listOf(TestData.event("evt-1")), emptyList())

        assertEquals(SyncError.Transient(), result.rejected.single().second)
    }

    @Test
    fun `malformed, unsigned, foreign and unknown items are rejected without a write`() = runTest {
        val noEnvelope = JsonObject(TestData.event("evt-1") - "envelope")
        val badHash = TestData.event("evt-2", hash = "nothex")
        val foreignEvent = TestData.event("evt-3", hid = "h2")
        val extraEnvelopeKey = TestData.event("evt-4").let { event ->
            val envelope = event.getValue("envelope").jsonObject
            JsonObject(event + ("envelope" to JsonObject(envelope + ("future" to envelope.getValue("hash")))))
        }
        val unsigned = TestData.document(signature = null)
        val foreignDocument = TestData.document(hid = HouseholdId("h2"))
        val unknown = TestData.document(docType = "gadget")

        val result = transport().push(
            listOf(noEnvelope, badHash, foreignEvent, extraEnvelopeKey),
            listOf(unsigned, foreignDocument, unknown),
        )

        assertEquals(
            listOf(
                "malformed",
                "malformed",
                "wrong-household",
                "malformed",
                "unsigned",
                "wrong-household",
                "unknown-doc-type",
            ),
            result.rejected.map { (it.second as SyncError.Permanent).code },
        )
        assertTrue(firestore.commits.isEmpty())
    }

    @Test
    fun `without a household the transport is unavailable and calls fail unauthenticated`() = runTest {
        hid = null

        assertFalse(transport().isAvailable())
        val failure = assertFailsWith<SyncException> { transport().push(emptyList(), emptyList()) }
        assertEquals(SyncError.Unauthenticated, failure.error)
        hid = HID
        assertTrue(transport().isAvailable())
    }

    @Test
    fun `an empty push sends nothing`() = runTest {
        val result = transport().push(emptyList(), emptyList())

        assertTrue(result.accepted.isEmpty() && result.rejected.isEmpty())
        assertTrue(firestore.commits.isEmpty())
        assertNull(firestore.docs["households/h1"])
    }
}
