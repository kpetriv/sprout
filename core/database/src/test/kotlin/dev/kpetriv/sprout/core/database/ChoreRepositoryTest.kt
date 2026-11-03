package dev.kpetriv.sprout.core.database

import dev.kpetriv.sprout.core.database.entity.AuditAction
import dev.kpetriv.sprout.core.database.entity.OutboxKind
import dev.kpetriv.sprout.core.domainhousehold.Chore
import dev.kpetriv.sprout.core.domainhousehold.ChoreReward
import dev.kpetriv.sprout.core.domainhousehold.SubmissionIds
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.model.ChoreId
import dev.kpetriv.sprout.core.model.ChoreSchedule
import dev.kpetriv.sprout.core.model.ChoreState
import dev.kpetriv.sprout.core.model.DayOfWeek
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.PhotoRef
import dev.kpetriv.sprout.core.model.SubmissionId
import dev.kpetriv.sprout.core.model.SubmissionStatus
import dev.kpetriv.sprout.core.sync.DocTypes
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

@RunWith(RobolectricTestRunner::class)
class ChoreRepositoryTest {
    private val household = testHousehold()
    private val chores = household.chores
    private val database = household.database
    private val kid = KidId("kid-leo")
    private val monday = CalendarDate(2026, 11, 2)

    private fun chore(id: String = "dishes", schedule: ChoreSchedule = ChoreSchedule.Weekly(DayOfWeek.MONDAY)) = Chore(
        id = ChoreId(id),
        householdId = LocalTrial.HOUSEHOLD,
        kidId = kid,
        title = "Do the dishes",
        description = "After dinner",
        reward = ChoreReward(money = Money(50), minutes = Money(10)),
        schedule = schedule,
        createdAt = household.clock.now(),
    )

    @After
    fun close() = household.close()

    @Test
    fun choresRoundTripAndEveryWriteQueuesADocumentAndAnAudit() = runTest {
        val dishes = chore()
        chores.upsert(dishes)
        chores.upsert(chore("bins", ChoreSchedule.Daily(setOf(DayOfWeek.TUESDAY, DayOfWeek.FRIDAY))))

        assertEquals(dishes, chores.get(dishes.id))
        // Same creation time, so ordered by id.
        assertEquals(listOf("bins", "dishes"), chores.observeChores(kid).first().map { it.id.value })
        assertEquals(emptyList(), chores.observeChores(KidId("someone-else")).first())

        assertTrue(chores.setState(dishes.id, ChoreState.ARCHIVED))
        assertFalse(chores.setState(ChoreId("missing"), ChoreState.ARCHIVED))
        assertEquals(listOf("bins"), chores.observeChores().first().map { it.id.value })
        assertEquals(2, chores.observeChores(includeArchived = true).first().size)

        val documents = database.outboxDao().all().filter { it.kind == OutboxKind.DOCUMENT }
        // One row per document; the archive replaced the dishes row with the newer state.
        assertEquals(setOf("chore/dishes", "chore/bins"), documents.map { it.refId }.toSet())
        val archived = documents.single { it.docId == "dishes" }
        assertEquals(DocTypes.CHORE, archived.docType)
        assertTrue("ARCHIVED" in archived.payload)
        val lamports = documents.map { assertNotNull(it.lamport) }
        assertEquals(lamports.distinct(), lamports)
        assertEquals(3L, lamports.max())

        val audit = database.auditLogDao().all()
        assertEquals(2, audit.count { it.action == AuditAction.CHORE_UPSERTED })
        assertEquals(1, audit.count { it.action == AuditAction.CHORE_STATE_CHANGED })
        // Titles and descriptions never reach the audit trail.
        assertTrue(audit.none { "Do the dishes" in it.detailsJson || "After dinner" in it.detailsJson })
    }

    @Test
    fun submittingIsIdempotentPerOccurrenceAndDecisionsFollowTheDomain() = runTest {
        val dishes = chore()
        chores.upsert(dishes)

        val first = chores.submit(dishes.id, monday, note = "done!")
        assertEquals(SubmissionIds.deterministic(dishes.id, monday), first.id)
        assertEquals(SubmissionStatus.SUBMITTED, first.status)
        household.clock.advanceBy(5.minutes)
        // Submitting again while it waits replaces the note; the id stays the same.
        val again = chores.submit(dishes.id, monday, note = "really done")
        assertEquals(first.id, again.id)
        assertEquals("really done", chores.getSubmission(first.id)?.note)
        assertEquals(
            listOf(first.id),
            chores.observeSubmissions(SubmissionFilter.PendingForParent).first().map {
                it.id
            },
        )

        val rejected = chores.decide(first.id, SubmissionDecision.REJECT, reason = "still dirty").submission
        assertEquals(SubmissionStatus.REJECTED, rejected.status)
        assertEquals("still dirty", rejected.rejectionReason)
        assertEquals(LocalTrial.PARENT, rejected.decidedBy)
        assertEquals(emptyList(), chores.observeSubmissions(SubmissionFilter.PendingForParent).first())
        // A decided occurrence is not re-opened by submitting again.
        assertEquals(rejected, chores.submit(dishes.id, monday, note = "please"))

        val approved = chores.decide(first.id, SubmissionDecision.APPROVE).submission
        assertEquals(SubmissionStatus.APPROVED, approved.status)
        assertNull(approved.rejectionReason)
        assertEquals(DecisionResult.Decided(approved), chores.decide(first.id, SubmissionDecision.APPROVE))
        assertFailsWith<IllegalStateException> { chores.decide(first.id, SubmissionDecision.REJECT) }
        assertEquals(listOf(approved), chores.observeSubmissions(SubmissionFilter.ForKid(kid)).first())

        val document = database.outboxDao().all().single { it.docType == DocTypes.CHORE_SUBMISSION }
        assertEquals(first.id.value, document.docId)
        assertTrue("APPROVED" in document.payload)
        // Notes and reasons are in the synced document but never in the audit trail.
        database.auditLogDao().all().forEach { row ->
            listOf("done", "dirty", "please").forEach { assertFalse(it in row.detailsJson, row.detailsJson) }
        }
    }

    @Test
    fun submittingChecksTheChoreTheDateAndThePhoto() = runTest {
        chores.upsert(chore())
        assertFailsWith<IllegalArgumentException> { chores.submit(ChoreId("missing"), monday) }
        assertFailsWith<IllegalArgumentException> { chores.submit(ChoreId("dishes"), monday.plusDays(1)) }
        assertFailsWith<IllegalArgumentException> { chores.decide(SubmissionId("missing"), SubmissionDecision.APPROVE) }
        val unknownPhoto = PhotoRef("missing.photo", "0".repeat(64))
        assertFailsWith<IllegalArgumentException> { chores.submit(ChoreId("dishes"), monday, photo = unknownPhoto) }
        assertEquals(emptyList(), chores.observeSubmissions(SubmissionFilter.ForKid(kid)).first())

        chores.upsert(chore("proposal").copy(state = ChoreState.PROPOSED, schedule = ChoreSchedule.Once()))
        assertFailsWith<IllegalArgumentException> { chores.submit(ChoreId("proposal"), monday) }
    }
}
