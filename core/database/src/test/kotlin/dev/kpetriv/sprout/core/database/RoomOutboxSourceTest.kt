package dev.kpetriv.sprout.core.database

import dev.kpetriv.sprout.core.database.entity.AuditAction
import dev.kpetriv.sprout.core.database.entity.OutboxEntity
import dev.kpetriv.sprout.core.database.entity.OutboxKind
import dev.kpetriv.sprout.core.domainhousehold.Reward
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.model.RewardId
import dev.kpetriv.sprout.core.model.RewardKind
import dev.kpetriv.sprout.core.sync.DocTypes
import dev.kpetriv.sprout.core.sync.OutboxItem
import dev.kpetriv.sprout.core.sync.PushItemId
import dev.kpetriv.sprout.core.sync.PushRejection
import dev.kpetriv.sprout.core.sync.SyncError
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

@RunWith(RobolectricTestRunner::class)
class RoomOutboxSourceTest {
    private val household = parentDevice()
    private val source = household.outbox

    @After
    fun close() = household.close()

    private suspend fun reward(id: String) = household.rewards.upsert(
        Reward(RewardId(id), HOME, "Reward $id", RewardKind.TREAT, Money(100), household.gbp),
    )

    @Test
    fun documentsComeOutAsSignedEnvelopesAndEventsAsTheirTrees() = runTest {
        reward("a")
        val (kid, accounts) = household.addKid()
        val main = with(household) { accounts.money() }
        household.writer.append(kid.id, main.id) {
            dev.kpetriv.sprout.core.domainledger.ManualAdjustment(it, household.gbp, Money(10), "start")
        }

        val due = source.due(nowEpochMs = 0, limit = 100)

        assertEquals(due.map { it.seq }, due.map { it.seq }.sorted())
        val document = due.first { it.kind == OutboxItem.Kind.DOCUMENT }.document
        assertNotNull(document)
        assertEquals(DocTypes.REWARD, document.docType)
        assertTrue(document.verifyWith(household.signatureCheck, household.keyStore.publicKey().encoded))
        val event = due.single { it.kind == OutboxItem.Kind.EVENT }
        assertEquals(household.queuedEvents().single(), event.eventJson)
    }

    @Test
    fun dueStopsAtTheFirstRowWaitingForItsBackoff() = runTest {
        reward("a")
        reward("b")
        reward("c")
        val (first, second, third) = source.due(0, 10).map { it.seq }

        source.fail(second, SyncError.Transient(), nextAttemptAtEpochMs = 5_000)

        assertEquals(listOf(first), source.due(nowEpochMs = 4_999, limit = 10).map { it.seq })
        assertEquals(listOf(first, second, third), source.due(nowEpochMs = 5_000, limit = 10).map { it.seq })
        assertEquals(listOf(first, second), source.due(nowEpochMs = 5_000, limit = 2).map { it.seq })
        val failed = household.database.outboxDao().all().single { it.seq == second }
        assertEquals(1, failed.attempts)
        assertEquals("transient", failed.lastError)
        assertEquals(1, source.due(5_000, 10).single { it.seq == second }.attempts)
    }

    @Test
    fun ackDeletesTheRowAndALaterWriteOfTheDocumentTakesANewSeq() = runTest {
        reward("a")
        val seq = source.due(0, 10).single().seq
        source.ack(seq)
        assertEquals(emptyList(), source.due(0, 10))

        reward("a")
        reward("a")
        val rows = household.database.outboxDao().all()
        assertEquals(1, rows.size)
        assertTrue(rows.single().seq > seq)
    }

    @Test
    fun anUndecodableRowIsDroppedAndAuditedInsteadOfBlockingTheQueue() = runTest {
        household.database.outboxDao().insert(
            OutboxEntity(
                kind = OutboxKind.DOCUMENT,
                refId = "reward/legacy",
                payload = "{\"not\":\"an envelope\"}",
                createdAtEpochMs = 0,
                docType = DocTypes.REWARD,
                docId = "legacy",
                lamport = 1,
            ),
        )
        reward("a")

        val due = source.due(0, 10)

        assertEquals(listOf("a"), due.map { it.document?.docId })
        assertEquals(listOf("reward/a"), household.database.outboxDao().all().map { it.refId })
        val audit = household.database.auditLogDao().all().single { it.action == AuditAction.SYNC_REJECTED }
        assertEquals("reward/legacy", audit.target)
        assertTrue(RoomOutboxSource.UNDECODABLE in audit.detailsJson)
    }

    @Test
    fun permanentRejectionsAreAuditedWithTheirCodeOnly() = runTest {
        source.auditRejections(listOf(PushRejection(7, PushItemId("document/reward/a"), "permission-denied")))

        val audit = household.database.auditLogDao().all().single { it.action == AuditAction.SYNC_REJECTED }
        assertEquals("document/reward/a", audit.target)
        assertEquals("{\"code\":\"permission-denied\",\"seq\":7,\"blocking\":false}", audit.detailsJson)
        assertEquals(household.session.uid, audit.actorUid)
    }

    @Test
    fun aBlockingRejectionIsAuditedOncePerSeqAndCodeAndSurfacesUntilAcked() = runTest {
        val (kid, accounts) = household.addKid()
        val main = with(household) { accounts.money() }
        repeat(2) {
            household.writer.append(kid.id, main.id) {
                dev.kpetriv.sprout.core.domainledger.ManualAdjustment(it, household.gbp, Money(10), "start")
            }
        }
        val (first, second) = household.database.outboxDao().all().filter { it.kind == OutboxKind.LEDGER_EVENT }
        val id = PushItemId("event/${first.refId}")
        assertEquals(null, source.observeBlocked().first())

        household.clock.advanceBy(1.minutes)
        val firstRefusedAt = household.clock.now()
        source.auditRejections(listOf(PushRejection(first.seq, id, "permission-denied", blocking = true)))
        // Offered again an hour later and refused again with the same code: not audited twice.
        household.clock.advanceBy(1.hours)
        source.auditRejections(listOf(PushRejection(first.seq, id, "permission-denied", blocking = true)))
        suspend fun rejections() =
            household.database.auditLogDao().all().filter { it.action == AuditAction.SYNC_REJECTED }
        assertEquals(1, rejections().size)
        assertEquals(
            BlockedPush(first.seq, "permission-denied", firstRefusedAt, OutboxKind.LEDGER_EVENT, first.refId),
            source.observeBlocked().first(),
        )

        // A new code for the same seq is audited, and becomes the code shown; "since" stays the first refusal.
        source.auditRejections(listOf(PushRejection(first.seq, id, "invalid-argument", blocking = true)))
        assertEquals(2, rejections().size)
        assertEquals(
            BlockedPush(first.seq, "invalid-argument", firstRefusedAt, OutboxKind.LEDGER_EVENT, first.refId),
            source.observeBlocked().first(),
        )

        // A refused document never blocks; once the event is accepted (acked) nothing blocks any more.
        source.auditRejections(listOf(PushRejection(second.seq + 1, PushItemId("document/reward/a"), "x")))
        source.ack(first.seq)
        assertEquals(null, source.observeBlocked().first())
    }
}
