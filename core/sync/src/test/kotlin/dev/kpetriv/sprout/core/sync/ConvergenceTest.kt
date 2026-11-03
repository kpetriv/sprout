package dev.kpetriv.sprout.core.sync

import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.model.SubmissionStatus
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Several devices, each with its own engine, outbox and store, over one [FakeBackend]. */
class ConvergenceTest {
    private val backend = FakeBackend(HOUSEHOLD, pageSize = 3)
    private val clock = FakeSyncClock(start = 1_000_000)

    private inner class Device(val identity: TestIdentity, role: Role) {
        val applier = RecordingApplier(SoftwareSignatureCheck)
        val outbox = InMemoryOutboxSource()
        val transport = FakeSyncTransport(backend, identity.deviceId, identity.uid, role)
        val engine = SyncEngine(
            transport,
            outbox,
            applier,
            clock,
            backoff = ExponentialBackoff(jitter = Jitter.None),
        )

        init {
            backend.registerDevice(identity.registered)
        }

        fun write(docType: String, docId: String, payload: JsonObject, deleted: Boolean = false): DocumentEnvelope {
            val envelope = identity.signed(docType, docId, applier.nextLamport(), payload, deleted)
            applier.writeLocal(envelope)
            outbox.enqueueDocument(envelope)
            return envelope
        }

        fun event(eventId: String) {
            val event = identity.event(eventId, applier.nextLamport())
            applier.recordLocalEvent(event)
            outbox.enqueueEvent(event)
        }

        suspend fun sync() = engine.syncOnce()

        fun eventIds() = applier.events().map { PushItemId.ofEvent(it).value }.toSet()
    }

    private val parentA = Device(TestIdentity("parent-a", "parent-uid", Role.PARENT), Role.PARENT)
    private val parentB = Device(TestIdentity("parent-b", "parent-uid", Role.PARENT), Role.PARENT)
    private val kid = Device(TestIdentity("kid-device", "kid-uid", Role.KID, KID_1), Role.KID)
    private val all = listOf(parentA, parentB, kid)

    private fun named(name: String) = buildJsonObject { put("name", name) }

    private suspend fun syncAll(rounds: Int = 2) = repeat(rounds) { all.forEach { it.sync() } }

    private fun assertConverged() {
        val server = backend.documents().associateBy { "${it.docType}/${it.docId}" }
        all.forEach { device -> assertEquals(server, device.applier.documents(), "${device.identity.deviceId}") }
        val events = all.first().eventIds()
        all.forEach { assertEquals(events, it.eventIds()) }
    }

    @Test
    fun partitionedKidConvergesOnSubmissionPrecedenceAndEvents() = runTest {
        parentA.write(DocTypes.REWARD, "reward-1", named("ice cream"))
        syncAll()
        kid.write(DocTypes.CHORE_SUBMISSION, "sub-1", submission().toPayload())
        syncAll()

        backend.partition(kid.identity.deviceId)
        parentA.write(DocTypes.CHORE_SUBMISSION, "sub-1", submission(SubmissionStatus.APPROVED).toPayload())
        parentA.event("parent-event")
        parentA.sync()
        // Offline, the kid keeps editing; its lamport ends up above the approval's.
        repeat(5) { kid.write(DocTypes.CHORE_SUBMISSION, "sub-1", submission(note = "edit $it").toPayload()) }
        kid.event("kid-event")
        val offline = kid.sync()
        assertEquals(SyncError.Transient(), offline.error)
        // The pull failed, so nothing was pushed and the outbox is untouched.
        assertEquals(2, kid.outbox.rows().size)
        assertTrue(kid.outbox.rows().all { it.item.attempts == 0 })

        backend.heal(kid.identity.deviceId)
        clock.advanceBy(1_000)
        syncAll()

        assertConverged()
        val merged = backend.document(DocTypes.CHORE_SUBMISSION, "sub-1")!!
        assertEquals("APPROVED", merged.payload["status"]!!.jsonPrimitive.content)
        assertTrue(kid.outbox.rows().isEmpty())
        assertEquals(setOf("event/parent-event:hash-parent-event", "event/kid-event:hash-kid-event"), kid.eventIds())
    }

    @Test
    fun concurrentParentEditsConvergeOnLastWriterWins() = runTest {
        parentA.write(DocTypes.CHORE, "chore-1", named("v0"))
        syncAll()

        backend.partition(parentA.identity.deviceId)
        backend.partition(parentB.identity.deviceId)
        parentA.write(DocTypes.CHORE, "chore-1", named("from A"))
        repeat(3) { parentB.write(DocTypes.SAVINGS_GOAL, "goal-$it", named("goal")) }
        val fromB = parentB.write(DocTypes.CHORE, "chore-1", named("from B"))
        parentA.sync()
        parentB.sync()

        backend.heal(parentA.identity.deviceId)
        backend.heal(parentB.identity.deviceId)
        clock.advanceBy(1_000)
        // B reaches the server first, then A: the later lamport (B's) still wins everywhere.
        parentB.sync()
        parentA.sync()
        syncAll()

        assertConverged()
        assertEquals(fromB, parentA.applier.document(DocTypes.CHORE, "chore-1"))
    }

    @Test
    fun tombstoneIsNotResurrectedByAConcurrentUpdate() = runTest {
        parentA.write(DocTypes.REWARD, "reward-1", named("v0"))
        syncAll()

        backend.partition(parentB.identity.deviceId)
        parentA.write(DocTypes.REWARD, "reward-1", named("v0"), deleted = true)
        parentA.sync()
        repeat(4) { parentB.write(DocTypes.CHORE, "chore-$it", named("bump")) }
        parentB.write(DocTypes.REWARD, "reward-1", named("late update"))

        backend.heal(parentB.identity.deviceId)
        clock.advanceBy(1_000)
        syncAll()

        assertConverged()
        assertTrue(backend.document(DocTypes.REWARD, "reward-1")!!.deleted)
        assertTrue(parentB.applier.document(DocTypes.REWARD, "reward-1")!!.deleted)
    }

    @Test
    fun kidCannotWriteParentDocuments() = runTest {
        kid.write(DocTypes.REWARD, "reward-1", named("free stuff"))
        kid.write(DocTypes.CHORE_SUBMISSION, "sub-1", submission(SubmissionStatus.APPROVED).toPayload())

        val report = kid.sync()

        assertEquals(
            listOf(FakeBackend.PERMISSION_DENIED, FakeBackend.PERMISSION_DENIED),
            report.rejections.map { it.code },
        )
        assertTrue(kid.outbox.rows().isEmpty(), "permanent rejections are acked")
        assertNull(backend.document(DocTypes.REWARD, "reward-1"))
    }

    @Test
    fun forgedDocumentsThatBypassTheServerAreRejectedByReceivers() = runTest {
        syncAll()
        // A document claiming the parent device but signed by the kid's key, and a kid-signed reward.
        val forged = parentA.identity.envelope(DocTypes.REWARD, "reward-1", 50, named("free"))
            .signWith(kid.identity.key::sign)
        backend.injectDocument(forged)
        backend.injectDocument(kid.identity.signed(DocTypes.REWARD, "reward-2", 50, named("free")))

        val report = parentB.sync()

        assertEquals(2, report.quarantined)
        assertEquals(
            mapOf(DocumentRejection.BAD_SIGNATURE to 1, DocumentRejection.ROLE_NOT_ALLOWED to 1),
            report.pulled.documents.rejected,
        )
        assertTrue(parentB.applier.documents().isEmpty())
    }

    @Test
    fun revokedDeviceWritesAreRefusedFromTheRevocationLamport() = runTest {
        parentB.write(DocTypes.CHORE, "chore-1", named("before"))
        syncAll()
        backend.revoke(parentB.identity.deviceId, atLamport = parentB.applier.localMaxLamport() + 1)
        parentB.write(DocTypes.CHORE, "chore-2", named("after"))

        val report = parentB.sync()

        assertEquals(listOf(FakeBackend.DEVICE_NOT_ACTIVE), report.rejections.map { it.code })
        parentA.sync()
        assertEquals(setOf("chore/chore-1"), parentA.applier.documents().keys)
    }

    @Test
    fun householdDeletionReachesEveryDevice() = runTest {
        syncAll()
        backend.deleteHousehold()
        all.forEach { assertTrue(it.sync().householdGone) }
    }

    @Test
    fun pagedPullsDeliverEverything() = runTest {
        repeat(10) { parentA.write(DocTypes.CHORE, "chore-$it", named("c$it")) }
        repeat(7) { parentA.event("e$it") }
        parentA.sync()

        val report = parentB.sync()

        assertTrue(report.pulled.pages >= 4, "page size 3 forces several pages")
        assertEquals(10, parentB.applier.documents().size)
        assertEquals(7, parentB.applier.events().size)
    }
}
