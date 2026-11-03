package dev.kpetriv.sprout.core.syncfirebase

import app.cash.turbine.test
import dev.kpetriv.sprout.core.domainledger.AttestationLevel
import dev.kpetriv.sprout.core.domainledger.DeviceStatus
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.model.UserId
import dev.kpetriv.sprout.core.sync.DocTypes
import dev.kpetriv.sprout.core.sync.RemoteChange
import dev.kpetriv.sprout.core.sync.SyncCursor
import dev.kpetriv.sprout.core.sync.SyncError
import dev.kpetriv.sprout.core.sync.SyncException
import dev.kpetriv.sprout.core.syncfirebase.TestData.HID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FirebaseSyncTransportPullTest {
    private val firestore = FakeFirestore()
    private val foreground = MutableStateFlow(true)
    private var hid: HouseholdId? = HID

    /** What a forced token refresh returns; a member of [HID] by default. */
    private var refreshed: AuthState? = TestData.member()

    private fun transport(pageSize: Int = FirebaseSyncTransport.PAGE_SIZE) = FirebaseSyncTransport(
        firestore = firestore,
        household = object : HouseholdScope {
            override suspend fun currentHouseholdId() = hid

            override suspend fun currentUserId() = UserId(TestData.UID)
        },
        foreground = foreground,
        authProbe = { refreshed },
        pageSize = pageSize,
        batchSize = FirebaseSyncTransport.MAX_BATCH_WRITES,
        callTimeoutMs = 5_000,
    )

    private fun device(
        uid: String,
        role: String,
        status: String,
        kidId: String? = null,
        revokedAtLamport: Long? = null,
    ): Map<String, Any?> = buildMap {
        put("uid", uid)
        put("role", role)
        kidId?.let { put("kidId", it) }
        put("publicKey", "AQID")
        put("attestation", "TEE")
        put("status", status)
        put("registeredAt", ServerTime(1, 0))
        put("lamport", 0L)
        revokedAtLamport?.let { put("revokedAtLamport", it) }
    }

    @Test
    fun `events round-trip to the exact tree that was pushed`() = runTest {
        val events = listOf(TestData.event("evt-1"), TestData.event("evt-2", lamport = 2))
        transport().push(events, emptyList())

        val page = transport().pull(SyncCursor.START)

        assertEquals(events, page.events)
        assertFalse(page.hasMore)
    }

    @Test
    fun `documents round-trip and come back in apply order`() = runTest {
        val documents = listOf(
            TestData.document(docType = DocTypes.REWARD, docId = "r1"),
            TestData.document(docType = DocTypes.CHORE, docId = "c1"),
            TestData.document(docType = DocTypes.KID, docId = "kid-a"),
            TestData.document(docType = DocTypes.ACCOUNT, docId = "acc-1"),
        )
        transport().push(emptyList(), documents)

        val page = transport().pull(SyncCursor.START)

        assertEquals(listOf("kid-a", "acc-1", "c1", "r1"), page.documents.map { it.docId })
        assertEquals(documents.toSet(), page.documents.toSet())
    }

    @Test
    fun `pages follow the cursor across writes that share a server time`() = runTest {
        // One batch: all five events share one serverReceivedAt, so only the document id orders them.
        transport().push((1..5).map { TestData.event("evt-$it") }, emptyList())
        val seen = mutableListOf<String>()
        var cursor = SyncCursor.START
        var pages = 0
        do {
            val page = transport(pageSize = 2).pull(cursor)
            seen +=
                page.events.map {
                    it.getValue("envelope").toString().substringAfter("\"eventId\":\"").substringBefore('"')
                }
            cursor = page.next
            pages++
        } while (page.hasMore)

        assertEquals((1..5).map { "evt-$it" }, seen)
        assertEquals(3, pages)
        assertTrue(transport(pageSize = 2).pull(cursor).events.isEmpty())
    }

    @Test
    fun `the cursor is the ISO instant and id of the last document per collection`() = runTest {
        transport().push(listOf(TestData.event("evt-1")), listOf(TestData.document()))

        val next = transport().pull(SyncCursor.START).next.perCollection

        assertEquals("2023-11-14T22:13:20Z|evt-1", next["events"])
        assertEquals("2023-11-14T22:13:20Z|doc-1", next["chores"])
        assertNull(next["rewards"], "collections without documents keep no cursor")
    }

    @Test
    fun `an unreadable cursor starts the collection over`() = runTest {
        transport().push(listOf(TestData.event("evt-1")), emptyList())

        val page = transport().pull(SyncCursor(mapOf("events" to "garbage")))

        assertEquals(1, page.events.size)
    }

    @Test
    fun `devices map to the registry and are returned again only when they change`() = runTest {
        val parent = "a".repeat(64)
        val kid = "b".repeat(64)
        val revoked = "c".repeat(64)
        val pending = "d".repeat(64)
        firestore.seed("households/h1/devices/$parent", device("p-uid", "PARENT", "ACTIVE"))
        firestore.seed("households/h1/devices/$kid", device("k-uid", "KID", "ACTIVE", kidId = "kid-a"))
        firestore.seed("households/h1/devices/$revoked", device("k-uid", "KID", "REVOKED", "kid-a", 12))
        firestore.seed("households/h1/devices/$pending", device("p-uid", "PARENT", "PENDING"))
        firestore.seed("households/h1/devices/${"e".repeat(64)}", device("x", "ADMIN", "ACTIVE"))

        val first = transport().pull(SyncCursor.START)

        val byId = first.devices.associateBy { it.deviceId.value }
        assertEquals(setOf(parent, kid, revoked, pending), byId.keys)
        assertEquals(Role.PARENT, byId.getValue(parent).role)
        assertEquals(KidId("kid-a"), byId.getValue(kid).kidId)
        assertEquals(DeviceStatus.Revoked(12), byId.getValue(revoked).status)
        assertEquals(DeviceStatus.Pending, byId.getValue(pending).status)
        assertEquals(AttestationLevel.TEE, byId.getValue(parent).attestation)
        assertContentEquals(byteArrayOf(1, 2, 3), byId.getValue(parent).publicKey)

        val unchanged = transport().pull(first.next)
        assertTrue(unchanged.devices.isEmpty())

        firestore.seed("households/h1/devices/$kid", device("k-uid", "KID", "REVOKED", "kid-a", 3))
        val changed = transport().pull(unchanged.next)
        assertEquals(DeviceStatus.Revoked(3), changed.devices.associateBy { it.deviceId.value }.getValue(kid).status)
    }

    @Test
    fun `server-registered parent keys come back as registry entries only`() = runTest {
        val fingerprint = "c".repeat(64)
        firestore.seed(
            "households/h1/parentKeys/$fingerprint",
            mapOf(
                "publicKey" to "AAAA",
                "uid" to TestData.UID,
                "deviceId" to TestData.DEVICE,
                "validFromLamport" to 7L,
                "registeredAt" to ServerTime(1, 0),
                "attestation" to "STRONGBOX",
                "revokedAtLamport" to 40L,
            ),
        )

        val page = transport().pull(SyncCursor.START)

        val registered = page.parentKeys.single()
        assertEquals(fingerprint, registered.fingerprint)
        assertEquals(7, registered.validFromLamport)
        assertEquals(40L, registered.revokedAtLamport)
        assertEquals(TestData.DEVICE, registered.deviceId.value)
        assertEquals(emptyList(), page.documents)
        assertTrue(transport().pull(page.next).parentKeys.isEmpty())
    }

    @Test
    fun `a bare refused read while the household is unreadable is transient`() = runTest {
        firestore.queryFailure = FirestoreCode.PERMISSION_DENIED
        firestore.readFailures["households/h1"] = FirestoreCode.PERMISSION_DENIED

        val failure = assertFailsWith<SyncException> { transport().pull(SyncCursor.START) }

        assertEquals(SyncError.Transient(), failure.error)
    }

    @Test
    fun `a refused read is household gone with positive evidence only`() = runTest {
        firestore.queryFailure = FirestoreCode.PERMISSION_DENIED
        firestore.readFailures["households/h1"] = FirestoreCode.PERMISSION_DENIED

        suspend fun pullError() = assertFailsWith<SyncException> { transport().pull(SyncCursor.START) }.error

        refreshed = AuthState.UserGone
        assertEquals(SyncError.HouseholdGone, pullError())

        refreshed = TestData.member(hid = null)
        assertEquals(SyncError.HouseholdGone, pullError())

        // A refresh that cannot be made (offline) is no evidence.
        refreshed = null
        assertEquals(SyncError.Transient(), pullError())

        // Nor are the claims of another account signed in over the session.
        refreshed = AuthState.SignedIn(UserId("someone-else"), isAnonymous = false, claims = Claims.NONE)
        assertEquals(SyncError.Transient(), pullError())
    }

    @Test
    fun `a refused read of a readable household is permanent, an outage transient`() = runTest {
        firestore.seed("households/h1", mapOf("createdBy" to "x"))
        firestore.queryFailure = FirestoreCode.PERMISSION_DENIED
        assertEquals(
            SyncError.Permanent("permission-denied"),
            assertFailsWith<SyncException> { transport().pull(SyncCursor.START) }.error,
        )

        firestore.queryFailure = FirestoreCode.UNAVAILABLE
        assertEquals(SyncError.Transient(), assertFailsWith<SyncException> { transport().pull(SyncCursor.START) }.error)

        firestore.queryFailure = FirestoreCode.UNAUTHENTICATED
        assertEquals(
            SyncError.Unauthenticated,
            assertFailsWith<SyncException> {
                transport().pull(SyncCursor.START)
            }.error,
        )
    }

    @Test
    fun `changes come from listeners only while the app is in the foreground`() = runTest {
        foreground.value = false
        transport().changes().test {
            expectNoEvents()
            foreground.value = true
            testScheduler.advanceUntilIdle()
            firestore.listeners.getValue("households/h1/events" to true).emit(Unit)
            assertEquals(RemoteChange("events"), awaitItem())
            firestore.listeners.getValue("households/h1/devices" to false).emit(Unit)
            assertEquals(RemoteChange("devices"), awaitItem())
            firestore.listeners.getValue("households/h1/choreSubmissions" to true).emit(Unit)
            assertEquals(RemoteChange("choreSubmissions"), awaitItem())

            foreground.value = false
            testScheduler.advanceUntilIdle()
            assertEquals(0, firestore.listeners.getValue("households/h1/events" to true).subscriptionCount.value)
            expectNoEvents()
        }
        assertEquals(14, firestore.listeners.size)
    }

    @Test
    fun `a failing listener ends the flow with a sync error`() = runTest {
        val failing = object : FirestoreFacade by firestore {
            override fun listen(path: String, latestOnly: Boolean, where: FieldEquals?) =
                kotlinx.coroutines.flow.flow<Unit> { throw FirestoreFailure(FirestoreCode.PERMISSION_DENIED) }
        }
        val transport = FirebaseSyncTransport(failing, { HID }, foreground, { refreshed }, 10, 10, 5_000)

        transport.changes().test {
            assertEquals(SyncError.Permanent("permission-denied"), (awaitError() as SyncException).error)
        }
    }

    @Test
    fun `without a household changes are empty and pulls fail unauthenticated`() = runTest {
        hid = null

        transport().changes().test { expectNoEvents() }
        assertTrue(firestore.listeners.isEmpty())
        assertEquals(
            SyncError.Unauthenticated,
            assertFailsWith<SyncException> { transport().pull(SyncCursor.START) }.error,
        )
    }
}
