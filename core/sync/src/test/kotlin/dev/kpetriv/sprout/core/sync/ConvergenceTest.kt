package dev.kpetriv.sprout.core.sync

import dev.kpetriv.sprout.core.domainledger.Balance
import dev.kpetriv.sprout.core.domainledger.ChoreRewarded
import dev.kpetriv.sprout.core.domainledger.DeviceStatus
import dev.kpetriv.sprout.core.domainledger.DeviceWriter
import dev.kpetriv.sprout.core.domainledger.EventAuthor
import dev.kpetriv.sprout.core.domainledger.EventId
import dev.kpetriv.sprout.core.domainledger.Fixtures
import dev.kpetriv.sprout.core.domainledger.GamePlayed
import dev.kpetriv.sprout.core.domainledger.LamportClock
import dev.kpetriv.sprout.core.domainledger.LedgerAnomaly
import dev.kpetriv.sprout.core.domainledger.LedgerEvent
import dev.kpetriv.sprout.core.domainledger.LedgerJson
import dev.kpetriv.sprout.core.domainledger.LedgerPolicy
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.domainledger.QuarantineReason
import dev.kpetriv.sprout.core.domainledger.RegisteredParentKey
import dev.kpetriv.sprout.core.domainledger.SoftwareKey
import dev.kpetriv.sprout.core.model.AccountId
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.model.SubmissionStatus
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The household's synced policy in these tests: parent signatures required, a small lamport-jump bound. */
private val POLICY = LedgerPolicy(maxLamportJump = 1_000, requireParentSignature = true)

/** One household: a [FakeBackend] and devices syncing with it, each with its own engine, outbox and store. */
private class World(pageSize: Int = 3) {
    val backend = FakeBackend(HOUSEHOLD, pageSize)
    val clock = FakeSyncClock(start = 1_000_000)

    fun parent(id: String, status: DeviceStatus = DeviceStatus.Active) =
        Device(TestIdentity(id, "parent-uid", Role.PARENT, status = status), SoftwareKey())

    fun kid(id: String = "kid-device", uid: String = "kid-uid", kidId: dev.kpetriv.sprout.core.model.KidId = KID_1) =
        Device(TestIdentity(id, uid, Role.KID, kidId))

    inner class Device(val identity: TestIdentity, val parentKey: SoftwareKey? = null) {
        val lamport = LamportClock(maxJump = POLICY.maxLamportJump)
        val applier = LedgerApplier(HOUSEHOLD, POLICY, lamport)
        val outbox = InMemoryOutboxSource()
        val transport = FakeSyncTransport(backend, identity.deviceId, identity.uid, identity.role)
        val engine = SyncEngine(transport, outbox, applier, clock, backoff = ExponentialBackoff(jitter = Jitter.None))
        val author = EventAuthor(HOUSEHOLD, identity.uid, identity.role, identity.deviceId)
        val writer = DeviceWriter(author, lamport = lamport, parentKey = parentKey, deviceKey = identity.key::sign)

        init {
            backend.registerDevice(identity.registered)
            parentKey?.let {
                backend.addParentKey(
                    RegisteredParentKey(it.fingerprint, it.publicKey, identity.uid, identity.deviceId, 0, null),
                )
            }
        }

        val id get() = identity.deviceId

        fun write(docType: String, docId: String, payload: JsonObject, deleted: Boolean = false): DocumentEnvelope {
            val envelope = identity.signed(docType, docId, applier.documents.nextLamport(), payload, deleted)
            applier.documents.writeLocal(envelope)
            outbox.enqueueDocument(envelope)
            return envelope
        }

        /** Writes a ledger event with this device's writer: stored locally and queued, as the real write path does. */
        fun <E : LedgerEvent> ledger(write: DeviceWriter.() -> E): E = writer.write().also {
            applier.recordLocal(it)
            outbox.enqueueEvent(LedgerJson.encodeToJsonObject(it))
        }

        /** Queues [event] for push without storing it locally. */
        fun send(event: LedgerEvent) {
            outbox.enqueueEvent(LedgerJson.encodeToJsonObject(event))
        }

        /** Queues a chat message (and the chunks of its blob first), as `DocumentWrites.appendMessage` will. */
        fun chat(message: JsonObject, vararg chunks: BlobChunkJson) {
            chunks.forEach { outbox.enqueueBlobChunk(it) }
            outbox.enqueueMessage(message)
        }

        suspend fun sync() = engine.syncOnce()

        suspend fun balances(): Map<AccountId, Balance> = applier.projection().balances
    }
}

class ConvergenceTest {
    private val world = World()
    private val backend = world.backend
    private val clock = world.clock
    private val parentA = world.parent("parent-a")
    private val parentB = world.parent("parent-b")
    private val kid = world.kid()
    private val all = listOf(parentA, parentB, kid)

    private fun named(name: String) = buildJsonObject { put("name", name) }

    private suspend fun syncAll(rounds: Int = 2) = repeat(rounds) { all.forEach { it.sync() } }

    @Test
    fun partitionedKidConvergesOnSubmissionRankAndEvents() = runTest {
        parentA.write(DocTypes.REWARD, "reward-1", named("ice cream"))
        syncAll()
        kid.write(DocTypes.CHORE_SUBMISSION, "sub-1", submission().toPayload())
        syncAll()

        backend.partition(kid.id)
        parentA.write(DocTypes.CHORE_SUBMISSION, "sub-1", submission(SubmissionStatus.APPROVED).toPayload())
        parentA.ledger { chore(150) }
        parentA.sync()
        // Offline, the kid keeps editing; its lamport ends up above the approval's.
        repeat(5) { kid.write(DocTypes.CHORE_SUBMISSION, "sub-1", submission(note = "edit $it").toPayload()) }
        kid.ledger { game(10) }
        val offline = kid.sync()
        assertEquals(SyncError.Transient(), offline.error)
        // The pull failed, so nothing was pushed and the outbox is untouched.
        assertEquals(2, kid.outbox.rows().size)
        assertTrue(kid.outbox.rows().all { it.item.attempts == 0 })

        backend.heal(kid.id)
        clock.advanceBy(1_000)
        syncAll()

        assertConverged(all)
        val merged = backend.document(DocTypes.CHORE_SUBMISSION, "sub-1")!!
        assertEquals("APPROVED", merged.payload["status"]!!.jsonPrimitive.content)
        assertTrue(kid.outbox.rows().isEmpty(), "the stale SUBMITTED edit counts as delivered: the server copy wins")
        assertEquals(Money(160), kid.balances().getValue(Fixtures.main.id).amount)
    }

    @Test
    fun concurrentParentEditsConvergeOnLastWriterWins() = runTest {
        parentA.write(DocTypes.CHORE, "chore-1", named("v0"))
        syncAll()

        backend.partition(parentA.id)
        backend.partition(parentB.id)
        parentA.write(DocTypes.CHORE, "chore-1", named("from A"))
        repeat(3) { parentB.write(DocTypes.SAVINGS_GOAL, "goal-$it", named("goal")) }
        val fromB = parentB.write(DocTypes.CHORE, "chore-1", named("from B"))
        parentA.sync()
        parentB.sync()

        backend.heal(parentA.id)
        backend.heal(parentB.id)
        clock.advanceBy(1_000)
        // B reaches the server first, then A: the later lamport (B's) still wins everywhere, and A's stale write
        // counts as delivered rather than rejected.
        parentB.sync()
        assertTrue(parentA.sync().rejections.isEmpty())
        syncAll()

        assertConverged(all)
        assertEquals(fromB, parentA.applier.documents.document(DocTypes.CHORE, "chore-1"))
    }

    @Test
    fun aTombstonePushedWithALowerLamportStillReachesTheServer() = runTest {
        parentA.write(DocTypes.REWARD, "reward-1", named("v0"))
        syncAll()
        // A keeps editing; B, whose clock is behind, deletes the reward.
        repeat(4) { parentA.write(DocTypes.CHORE, "chore-$it", named("bump")) }
        val live = parentA.write(DocTypes.REWARD, "reward-1", named("v1"))
        parentA.sync()
        val tombstone = parentB.write(DocTypes.REWARD, "reward-1", named("v0"), deleted = true)
        assertTrue(tombstone.lamport < live.lamport)

        val report = parentB.sync()

        assertTrue(report.rejections.isEmpty())
        assertEquals(tombstone, backend.document(DocTypes.REWARD, "reward-1"))
        syncAll()
        assertConverged(all)
        assertTrue(parentA.applier.documents.document(DocTypes.REWARD, "reward-1")!!.deleted)
    }

    @Test
    fun aLiveEditAfterATombstoneIsRefusedAndTheClientKeepsTheTombstone() = runTest {
        parentA.write(DocTypes.REWARD, "reward-1", named("v0"))
        syncAll()
        val tombstone = parentA.write(DocTypes.REWARD, "reward-1", named("v0"), deleted = true)
        parentA.sync()
        // B has not seen the deletion; its edit carries a higher lamport.
        repeat(4) { parentB.write(DocTypes.CHORE, "chore-$it", named("bump")) }
        val edit = parentB.write(DocTypes.REWARD, "reward-1", named("late update"))
        assertTrue(edit.lamport > tombstone.lamport)

        val report = parentB.sync()

        assertEquals(tombstone, backend.document(DocTypes.REWARD, "reward-1"), "the server never stored the edit")
        assertTrue(report.rejections.isEmpty(), "the tombstone already beats the edit, so the push is settled")
        assertTrue(parentB.outbox.rows().isEmpty())
        assertEquals(tombstone, parentB.applier.documents.document(DocTypes.REWARD, "reward-1"))
        syncAll()
        assertConverged(all)
    }

    @Test
    fun kidCannotWriteParentDocuments() = runTest {
        kid.write(DocTypes.REWARD, "reward-1", named("free stuff"))
        kid.write(DocTypes.CHORE_SUBMISSION, "sub-1", submission(SubmissionStatus.APPROVED).toPayload())
        kid.write(DocTypes.CHORE_SUBMISSION, "sub-2", submission(id = "sub-2").toPayload(), deleted = true)

        val report = kid.sync()

        assertEquals(List(3) { FakeBackend.PERMISSION_DENIED }, report.rejections.map { it.code })
        assertTrue(report.rejections.none { it.blocking })
        assertTrue(kid.outbox.rows().isEmpty(), "permanent document rejections are acked")
        assertTrue(backend.documents().isEmpty())
    }

    @Test
    fun kidCannotOverwriteADecidedSubmission() = runTest {
        kid.write(DocTypes.CHORE_SUBMISSION, "sub-1", submission().toPayload())
        syncAll()
        parentA.write(DocTypes.CHORE_SUBMISSION, "sub-1", submission(SubmissionStatus.REJECTED).toPayload())
        parentA.sync()
        // The kid has not pulled the decision and resubmits with a later lamport.
        repeat(3) { kid.write(DocTypes.CHORE_SUBMISSION, "sub-x$it", submission(id = "sub-x$it").toPayload()) }
        kid.write(DocTypes.CHORE_SUBMISSION, "sub-1", submission(note = "please").toPayload())

        kid.sync()
        syncAll()

        val decided = backend.document(DocTypes.CHORE_SUBMISSION, "sub-1")!!
        assertEquals("REJECTED", decided.payload["status"]!!.jsonPrimitive.content)
        assertConverged(all)
    }

    @Test
    fun aKidsConflictingEventCopyIsRefusedButAParentsIsKeptAsEvidence() = runTest {
        val allowance = parentA.ledger { allowance(500) }
        syncAll()
        kid.ledger {
            writeWithId(allowance.eventId, Fixtures.main) {
                GamePlayed(it, Fixtures.gbp, Money(5), "g", 1, Fixtures.today)
            }
        }

        val report = kid.sync()

        val rejection = report.rejections.single()
        assertEquals(FakeBackend.PERMISSION_DENIED, rejection.code)
        assertTrue(rejection.blocking, "an event is never dropped from the outbox")
        assertEquals(1, kid.outbox.rows().size)
        assertEquals(listOf(allowance.envelope.hash), serverCopiesOf(allowance.eventId))

        // A parent device's conflicting copy is stored under eventId__hash; every parent device applies the same copy.
        val copy = parentB.ledger {
            writeWithId(allowance.eventId, Fixtures.main) {
                ChoreRewarded(it, Fixtures.gbp, Money(7), "c", Fixtures.today)
            }
        }
        repeat(2) { listOf(parentA, parentB).forEach { it.sync() } }
        assertEquals(setOf(allowance.envelope.hash, copy.envelope.hash), serverCopiesOf(allowance.eventId).toSet())
        assertEquals(parentA.balances(), parentB.balances())
        val conflict = parentA.applier.projection().anomalies.filterIsInstance<LedgerAnomaly.ConflictingDuplicate>()
        assertEquals(allowance.envelope.hash, conflict.single().applied?.hash, "the lower lamport wins")
        assertEquals(Money(500), parentB.balances().getValue(Fixtures.main.id).amount)
    }

    @Test
    fun aPendingDevicesPushFailsUntilItIsApproved() = runTest {
        val newPhone = world.parent("parent-new", status = DeviceStatus.Pending)
        val chore = newPhone.ledger { chore(100) }

        val refused = newPhone.sync()

        assertEquals(listOf(FakeBackend.PERMISSION_DENIED), refused.rejections.map { it.code })
        assertTrue(refused.rejections.single().blocking)
        assertEquals(1, newPhone.outbox.rows().size, "kept for a later attempt")

        backend.approve(newPhone.id)
        assertEquals(0, newPhone.sync().pushed, "waits for its retry time")
        clock.advanceBy(SyncEngine.EVENT_REJECTION_RETRY_MS)
        assertEquals(1, newPhone.sync().pushed)
        assertTrue(newPhone.outbox.rows().isEmpty())

        parentA.sync()
        assertTrue(chore.key in parentA.applier.appliedKeys())
        assertEquals(Money(100), parentA.balances().getValue(Fixtures.main.id).amount)
    }

    @Test
    fun anEventFromAnUnknownDeviceIsHeldUntilTheRegistryArrives() = runTest {
        // A second kid device whose registry entry has not reached the receivers yet.
        val phone = TestIdentity("kid-phone", "kid-uid", Role.KID, KID_1)
        val game = DeviceWriter(
            EventAuthor(HOUSEHOLD, phone.uid, Role.KID, phone.deviceId),
            deviceKey = phone.key::sign,
        ).game(10)
        backend.injectEvent(LedgerJson.encodeToJsonObject(game))

        val before = parentA.sync()

        assertEquals(1, before.held)
        assertEquals(setOf(game.key), parentA.applier.heldKeys())
        assertTrue(parentA.applier.quarantined().isEmpty(), "an unknown device is not a forgery")

        backend.registerDevice(phone.registered)
        val after = parentA.sync()

        assertEquals(0, after.held)
        assertTrue(game.key in parentA.applier.appliedKeys())
        assertEquals(Money(10), parentA.balances().getValue(Fixtures.main.id).amount)
    }

    @Test
    fun forgedEventsAreQuarantinedOnTheReceiver() = runTest {
        parentA.ledger { allowance(300) }
        syncAll()
        // Slipped past the rules: a kid device claiming PARENT for a chore reward, a parent-only event without a
        // parent signature, and an event signed by another device's key.
        val claimsParent = DeviceWriter(kid.author.copy(role = Role.PARENT), deviceKey = kid.identity.key::sign)
            .chore(900)
        val unsigned = DeviceWriter(parentA.author, seed = 5, deviceKey = parentA.identity.key::sign).allowance(500)
        val wrongKey = DeviceWriter(kid.author, seed = 6, deviceKey = parentB.identity.key::sign).game(20)
        listOf(claimsParent, unsigned, wrongKey).forEach { backend.injectEvent(LedgerJson.encodeToJsonObject(it)) }

        val report = parentB.sync()

        assertEquals(3, report.quarantined)
        assertEquals(
            mapOf(
                claimsParent.key to QuarantineReason.ROLE_NOT_ALLOWED,
                unsigned.key to QuarantineReason.PARENT_SIGNATURE_MISSING,
                wrongKey.key to QuarantineReason.BAD_DEVICE_SIGNATURE,
            ),
            parentB.applier.quarantined(),
        )
        assertEquals(Money(300), parentB.balances().getValue(Fixtures.main.id).amount)
    }

    @Test
    fun anEventTooFarAheadIsQuarantined() = runTest {
        parentA.ledger { chore(100) }
        syncAll()
        val far = DeviceWriter(
            parentA.author,
            seed = 11,
            lamport = LamportClock(start = 10_000),
            parentKey = parentA.parentKey,
            deviceKey = parentA.identity.key::sign,
        ).chore(50)
        // Lamports are not a rules matter: the server takes it.
        parentA.send(far)
        assertEquals(1, parentA.sync().pushed)

        parentB.sync()

        assertEquals(QuarantineReason.LAMPORT_TOO_FAR_AHEAD, parentB.applier.quarantined()[far.key])
        assertTrue(parentB.applier.localMaxLamport() < 10_000)
        assertTrue(parentB.lamport.current < 10_000, "the clock did not jump")
        assertEquals(Money(100), parentB.balances().getValue(Fixtures.main.id).amount)
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
        assertTrue(parentB.applier.documents.documents().isEmpty())
    }

    @Test
    fun aRevokedDeviceWritesNothingButItsEarlierWritesStay() = runTest {
        parentB.write(DocTypes.CHORE, "chore-1", named("before"))
        syncAll()
        backend.revoke(parentB.id, atLamport = parentB.applier.documents.localMaxLamport() + 1)
        parentB.write(DocTypes.CHORE, "chore-2", named("after"))
        val event = parentB.ledger { chore(10) }

        val report = parentB.sync()

        assertEquals(List(2) { FakeBackend.PERMISSION_DENIED }, report.rejections.map { it.code }.sorted())
        parentA.sync()
        assertEquals(setOf("chore/chore-1"), parentA.applier.documents.documents().keys)
        assertTrue(event.key !in parentA.applier.appliedKeys())
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
        repeat(7) { parentA.ledger { chore(10) } }
        parentA.sync()

        val report = parentB.sync()

        assertTrue(report.pulled.pages >= 4, "page size 3 forces several pages")
        assertEquals(10, parentB.applier.documents.documents().size)
        assertEquals(7, parentB.applier.appliedKeys().size)
        assertEquals(Money(70), parentB.balances().getValue(Fixtures.main.id).amount)
    }

    /**
     * Property: whatever the interleaving of writes, syncs, partitions and clock steps, once every device is
     * reachable and has synced a few rounds, all devices hold the same events, the server's, and the same balances.
     */
    @Test
    fun randomInterleavingsConvergeOnBalances() = runTest {
        repeat(RUNS) { run ->
            val random = Random(run)
            val world = World(pageSize = 1 + random.nextInt(4))
            val devices = listOf(world.parent("parent-a"), world.parent("parent-b"), world.kid())
            repeat(random.nextInt(10, 40)) {
                val device = devices.random(random)
                when (random.nextInt(5)) {
                    0, 1 -> device.randomWrite(random)

                    2 -> device.sync()

                    3 -> if (world.backend.isPartitioned(device.id)) {
                        world.backend.heal(device.id)
                    } else {
                        world.backend.partition(device.id)
                    }

                    else -> world.clock.advanceBy(random.nextLong(0, 5_000))
                }
            }
            devices.forEach { world.backend.heal(it.id) }
            world.clock.advanceBy(BACKOFF_MAX_MS)
            repeat(3) { devices.forEach { it.sync() } }

            val server = world.backend.events().map { LedgerJson.decodeOrNull(it)!!.key }.toSet()
            devices.forEach { device ->
                assertEquals(server, device.applier.appliedKeys(), "run $run ${device.id}")
                assertTrue(device.applier.heldKeys().isEmpty(), "run $run ${device.id} held")
                assertTrue(device.applier.quarantined().isEmpty(), "run $run ${device.id} quarantined")
                assertTrue(device.outbox.rows().isEmpty(), "run $run ${device.id} outbox")
            }
            val balances = devices.first().balances()
            devices.forEach { assertEquals(balances, it.balances(), "run $run ${it.id} balances") }
        }
    }

    private fun World.Device.randomWrite(random: Random) {
        if (identity.role == Role.PARENT) {
            when (random.nextInt(3)) {
                0 -> ledger { chore(random.nextLong(1, 100)) }
                1 -> ledger { adjust(random.nextLong(-50, 50).takeIf { it != 0L } ?: 1) }
                else -> ledger { allowance(random.nextLong(1, 100)) }
            }
        } else {
            when (random.nextInt(2)) {
                0 -> ledger { game(random.nextLong(1, 20)) }
                else -> ledger { purchase(random.nextLong(1, 40)) }
            }
        }
    }

    private suspend fun assertConverged(devices: List<World.Device>) {
        val serverDocuments = backend.documents().associateBy { "${it.docType}/${it.docId}" }
        devices.forEach { device ->
            assertEquals(serverDocuments, device.applier.documents.documents(), "${device.id} documents")
        }
        val serverEvents = backend.events().map { LedgerJson.decodeOrNull(it)!!.key }.toSet()
        devices.forEach { assertEquals(serverEvents, it.applier.appliedKeys(), "${it.id} events") }
        val balances = devices.first().balances()
        devices.forEach { assertEquals(balances, it.balances(), "${it.id} balances") }
    }

    private fun serverCopiesOf(eventId: EventId): List<String> = backend.events()
        .mapNotNull { LedgerJson.decodeOrNull(it) }
        .filter { it.eventId == eventId }
        .map { it.envelope.hash }

    private val LedgerEvent.key: Pair<EventId, String> get() = eventId to envelope.hash

    private companion object {
        const val RUNS = 50
        const val BACKOFF_MAX_MS = 3_600_000L
    }

    @Test
    fun chatMessagesReachTheThreadsMembersOnly() = runTest {
        val world = World()
        val parent = world.parent("parent-device")
        val kid = world.kid()
        val otherKid = world.kid("kid2-device", "kid2-uid", KID_2)
        // Blob chunks name a kid the household has (the rules' exists(kids/<kidId>)).
        world.backend.injectDocument(parent.identity.signed(DocTypes.KID, KID_1.value, lamport = 1))
        val photo = parent.identity.blobChunk("blob-p1", thread = KID_1)
        parent.chat(
            parent.identity.message("p1", kind = dev.kpetriv.sprout.core.domainledger.chat.MessageKind.PHOTO),
            photo,
        )
        parent.chat(parent.identity.message("p2", seq = 1, lamport = 2, thread = KID_2))
        kid.chat(kid.identity.message("k1", seq = 1))
        kid.chat(kid.identity.message("k2", seq = 2))

        repeat(2) { listOf(parent, kid, otherKid).forEach { it.sync() } }

        fun ids(device: World.Device) = device.applier.documents.messages().map { it.messageId.value }.toSet()
        assertEquals(setOf("p1", "p2", "k1", "k2"), ids(parent))
        assertEquals(setOf("p1", "k1", "k2"), ids(kid))
        assertEquals(setOf("p2"), ids(otherKid))
        val blobId = dev.kpetriv.sprout.core.model.BlobId("blob-p1")
        assertEquals(listOf(photo), kid.transport.fetchBlob(blobId).getOrThrow())
        assertTrue(otherKid.transport.fetchBlob(blobId).isFailure)
        assertTrue(listOf(parent, kid, otherKid).all { it.outbox.rows().isEmpty() })
    }
}
