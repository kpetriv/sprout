package dev.kpetriv.sprout.core.database

import dev.kpetriv.sprout.core.database.usecase.ApprovalResult
import dev.kpetriv.sprout.core.database.usecase.ManualAdjustmentResult
import dev.kpetriv.sprout.core.domainhousehold.Chore
import dev.kpetriv.sprout.core.domainhousehold.ChoreReward
import dev.kpetriv.sprout.core.domainhousehold.ChoreScheduler
import dev.kpetriv.sprout.core.domainhousehold.PendingParentAction
import dev.kpetriv.sprout.core.domainledger.AllowancePaid
import dev.kpetriv.sprout.core.domainledger.AppendResult
import dev.kpetriv.sprout.core.domainledger.ChoreRewarded
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.InterestCalculator
import dev.kpetriv.sprout.core.domainledger.ManualAdjustment
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.domainledger.ParentAuthorization
import dev.kpetriv.sprout.core.domainledger.TransferToSavings
import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.model.ChoreId
import dev.kpetriv.sprout.core.model.ChoreSchedule
import dev.kpetriv.sprout.core.model.ChoreState
import dev.kpetriv.sprout.core.model.ChoreSubmission
import dev.kpetriv.sprout.core.model.DayOfWeek
import dev.kpetriv.sprout.core.model.InterestPolicy
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.PhotoRef
import dev.kpetriv.sprout.core.model.SubmissionStatus
import dev.kpetriv.sprout.core.sync.DocTypes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days

/**
 * Data-layer review fixes: partial approvals and rejecting them (R4), unverifiable parent signatures and an
 * invalidated key (audit M1), photo deletion (audit L2), any-time chores (R-low), interest principal per period (R2)
 * and lamports shared between documents and events (R-low).
 */
@RunWith(RobolectricTestRunner::class)
class ReviewFixesTest {
    private val household = testHousehold()
    private val monday = CalendarDate(2026, 11, 2)

    @After
    fun close() = household.close()

    private fun chore(kid: KidId, schedule: ChoreSchedule = ChoreSchedule.Weekly(DayOfWeek.MONDAY)) = Chore(
        ChoreId("tidy"),
        LocalTrial.HOUSEHOLD,
        kid,
        "Tidy your room",
        reward = ChoreReward(money = Money(50), minutes = Money(15)),
        schedule = schedule,
        createdAt = household.clock.now(),
    )

    private suspend fun submitted(kid: KidId, photo: PhotoRef? = null): ChoreSubmission {
        household.chores.upsert(chore(kid))
        return household.chores.submit(ChoreId("tidy"), monday, note = null, photo = photo)
    }

    private fun fileOf(ref: PhotoRef) = File(household.photoDirectory, ref.fileName)

    private suspend fun assertPhotoGone(ref: PhotoRef) {
        assertNull(household.database.photoDao().get(ref.fileName))
        assertFalse(fileOf(ref).exists())
    }

    @Test
    fun anApprovalStoppedHalfwayIsPartialCannotBeRejectedAndCanBeFinished() = runTest {
        val (kid, _) = household.addKid()
        household.registerParentKey()
        val submission = submitted(kid.id)

        // The money reward is signed, then the key is invalidated before the minutes reward.
        val partial = assertIs<ApprovalResult.Partial>(
            household.approve(submission.id) { intent ->
                if (intent.currency == Currency.Minutes) household.parentAuthorizer.invalidateKey()
                household.authorization()
            },
        )
        assertEquals(listOf<Currency>(household.gbp), partial.paid.map { it.intent.currency })
        assertEquals(listOf<Currency>(Currency.Minutes), partial.unpaid.map { it.currency })
        assertIs<ApprovalResult.KeyInvalidated>(partial.stoppedBy)
        assertEquals(SubmissionStatus.SUBMITTED, household.chores.getSubmission(submission.id)?.status)

        // Rejecting now would leave the money paid for a rejected chore: refused.
        val refused = assertIs<DecisionResult.AlreadyPaid>(
            household.chores.decide(submission.id, SubmissionDecision.REJECT, "no"),
        )
        assertEquals(listOf(partial.paid.single().eventId), refused.paidBy)
        assertEquals(SubmissionStatus.SUBMITTED, household.chores.getSubmission(submission.id)?.status)

        // After the key is renewed, "finish approval" pays only the minutes.
        household.parentAuthorizer.resetParentKey()
        household.registerParentKey()
        val finished = assertIs<ApprovalResult.Approved>(household.approve(submission.id) { household.authorization() })
        assertEquals(listOf(true, false), finished.rewards.map { it.alreadyPaid })
        assertEquals(
            2,
            household.ledger.observeEvents(kid.id, limit = 5).first().filterIsInstance<ChoreRewarded>().size,
        )
    }

    @Test
    fun anInvalidatedKeyBeforeAnyRewardIsReportedNotSwallowed() = runTest {
        val (kid, _) = household.addKid()
        household.registerParentKey()
        val submission = submitted(kid.id)
        household.parentAuthorizer.invalidateKey()

        assertIs<ApprovalResult.KeyInvalidated>(household.approve(submission.id) { household.authorization() })
        assertEquals(
            ManualAdjustmentResult.KeyInvalidated,
            household.adjust(kid.id, Money(100), "receipt") { household.authorization() },
        )
        assertEquals(emptyList(), household.ledger.observeEvents(kid.id, limit = 5).first())
    }

    @Test
    fun withoutARegisteredKeyAParentSignatureIsDroppedAndNothingIsReportedSigned() = runTest {
        val (kid, accounts) = household.addKid()
        val main = with(household) { accounts.money() }
        // The device holds a parent key, but it was never registered with the household policy.
        household.parentAuthorizer.createParentKey()

        val appended = household.writer.appendAsParent(kid.id, main.id, household.authorization()) {
            AllowancePaid(it, household.gbp, Money(100), "weekly", "2026-W44")
        }
        assertEquals(AppendResult.Accepted, appended)
        assertNull(household.ledger.observeEvents(kid.id, limit = 1).first().single().envelope.parentSignature)
        assertNull(household.database.ledgerEventDao().all().single().parentSignature)

        // The use cases do not even prompt, and say the event is unsigned.
        assertEquals(
            ManualAdjustmentResult.Unsigned(AppendResult.Accepted),
            household.adjust(kid.id, Money(100), "receipt") { error("no key registered: must not prompt") },
        )
        assertTrue(
            household.ledger.observeEvents(kid.id, limit = 5).first().filterIsInstance<ManualAdjustment>()
                .all { it.envelope.parentSignature == null },
        )
    }

    @Test
    fun decidingASubmissionDeletesItsPhoto() = runTest {
        val (kid, _) = household.addKid()
        household.registerParentKey()
        val first = household.photos.save(kid.id, Random(1).nextBytes(512))
        val replacement = household.photos.save(kid.id, Random(2).nextBytes(512))
        household.chores.upsert(chore(kid.id))
        household.chores.submit(ChoreId("tidy"), monday, photo = first)
        val submission = household.chores.submit(ChoreId("tidy"), monday, photo = replacement)
        assertTrue(fileOf(first).exists() && fileOf(replacement).exists())

        assertIs<ApprovalResult.Approved>(household.approve(submission.id) { household.authorization() })
        // The current photo and the one it replaced are both gone; the submission keeps the reference as a record.
        assertPhotoGone(first)
        assertPhotoGone(replacement)
        assertEquals(replacement, household.chores.getSubmission(submission.id)?.photoRef)
    }

    @Test
    fun rejectingDeletesThePhoto() = runTest {
        val (kid, _) = household.addKid()
        val photo = household.photos.save(kid.id, Random(3).nextBytes(512))
        val submission = submitted(kid.id, photo)

        val decided = household.chores.decide(submission.id, SubmissionDecision.REJECT, "redo")
        assertEquals(SubmissionStatus.REJECTED, assertIs<DecisionResult.Decided>(decided).submission.status)
        assertPhotoGone(photo)
    }

    @Test
    fun archivingAChoreOrAKidDeletesTheirPhotos() = runTest {
        val (kid, _) = household.addKid()
        val pendingPhoto = household.photos.save(kid.id, Random(4).nextBytes(512))
        submitted(kid.id, pendingPhoto)
        assertTrue(household.chores.setState(ChoreId("tidy"), ChoreState.ARCHIVED))
        assertPhotoGone(pendingPhoto)

        // A photo saved but never attached (the submit failed) goes with the kid.
        val loose = household.photos.save(kid.id, Random(5).nextBytes(512))
        assertTrue(household.kids.archive(kid.id))
        assertPhotoGone(loose)
    }

    @Test
    fun anAnyTimeChorePaysOnceWhicheverDaysItIsSubmittedOn() = runTest {
        val (kid, _) = household.addKid()
        household.registerParentKey()
        val anyTime = chore(kid.id, ChoreSchedule.Once(null))
        household.chores.upsert(anyTime)

        val first = household.chores.submit(anyTime.id, monday)
        assertEquals(ChoreScheduler.anyTimeDueOn(anyTime), first.dueOn)
        assertIs<ApprovalResult.Approved>(household.approve(first.id) { household.authorization() })
        // Submitting it again on another day returns the approved occurrence; nothing more can be paid.
        val later = household.chores.submit(anyTime.id, monday.plusDays(3))
        assertEquals(first.id, later.id)
        assertEquals(SubmissionStatus.APPROVED, later.status)
        val again = assertIs<ApprovalResult.Approved>(household.approve(later.id) { error("already paid") })
        assertTrue(again.rewards.all { it.alreadyPaid })
        assertEquals(
            2,
            household.ledger.observeEvents(kid.id, limit = 5).first().filterIsInstance<ChoreRewarded>().size,
        )
    }

    @Test
    fun interestEarnsOnEachPeriodsOpeningBalance() = runTest {
        val (kid, accounts) = household.addKid()
        household.registerParentKey()
        val main = with(household) { accounts.money() }
        val savings = with(household) { accounts.savings() }
        household.adjust(kid.id, Money(50_000), "jar") { household.authorization() }
        household.writer.append(kid.id, main.id) { TransferToSavings(it, household.gbp, Money(10_000), savings.id) }
        household.interest.set(InterestPolicy(kid.householdId, annualRateBasisPoints = 500, accrualDay = 1))
        household.plan(monday) // Interest starts today, 2 November.

        // 10 December: £200 more goes into savings, in the middle of the December period.
        household.clock.advanceBy(38.days)
        household.writer.append(kid.id, main.id) { TransferToSavings(it, household.gbp, Money(20_000), savings.id) }

        val accruals = household.plan(CalendarDate(2027, 2, 1)).queued.map { it as PendingParentAction.AccrueInterest }
        assertEquals(listOf("2026-12", "2027-01", "2027-02"), accruals.map { it.periodKey })
        val (november, december, january) = accruals
        assertEquals(Money(10_000), november.principal)
        assertEquals(InterestCalculator.accrue(Money(10_000), 500, 29), november.amount)
        // The 10 December deposit was not there on 1 December: December still earns on £100 plus November's interest.
        assertEquals(Money(10_000) + november.amount, december.principal)
        // By 1 January it was: January earns on £300 plus the interest planned before it.
        assertEquals(Money(30_000) + november.amount + december.amount, january.principal)
    }

    @Test
    fun aDocumentWriteNeverTakesTheLamportOfAnEventBeingSigned() = runBlocking {
        val (kid, accounts) = household.addKid()
        household.registerParentKey()
        val main = with(household) { accounts.money() }
        val signing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val authorization = household.authorization()
        val slowPrompt = ParentAuthorization { payload ->
            signing.countDown()
            check(release.await(WAIT_SECONDS, TimeUnit.SECONDS)) { "never released" }
            authorization.sign(payload)
        }

        val event = async(Dispatchers.IO) {
            household.writer.appendAsParent(kid.id, main.id, slowPrompt) {
                AllowancePaid(it, household.gbp, Money(100), "weekly", "2026-W44")
            }
        }
        assertTrue(signing.await(WAIT_SECONDS, TimeUnit.SECONDS))
        val document = async(Dispatchers.IO) { household.chores.upsert(chore(kid.id)) }
        delay(BLOCKED_MILLIS)
        assertFalse(document.isCompleted, "the document write waits for the event being signed")
        release.countDown()
        withTimeout(WAIT_SECONDS * 1_000) {
            assertEquals(AppendResult.Accepted, event.await())
            document.await()
        }

        val eventLamport = household.ledger.observeEvents(kid.id, limit = 1).first().single().lamport
        val documentLamport = assertNotNull(
            household.database.outboxDao().all().single { it.docType == DocTypes.CHORE }.lamport,
        )
        assertTrue(documentLamport > eventLamport, "document $documentLamport after event $eventLamport")
    }

    private companion object {
        const val WAIT_SECONDS = 10L
        const val BLOCKED_MILLIS = 300L
    }
}
