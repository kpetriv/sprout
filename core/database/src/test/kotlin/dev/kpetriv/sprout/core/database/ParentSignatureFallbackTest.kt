package dev.kpetriv.sprout.core.database

import dev.kpetriv.sprout.core.crypto.ParentAuthAvailability
import dev.kpetriv.sprout.core.crypto.ParentAuthUnavailableException
import dev.kpetriv.sprout.core.database.usecase.ApprovalResult
import dev.kpetriv.sprout.core.database.usecase.ManualAdjustmentResult
import dev.kpetriv.sprout.core.domainhousehold.AllowanceSchedule
import dev.kpetriv.sprout.core.domainhousehold.Chore
import dev.kpetriv.sprout.core.domainhousehold.ChoreReward
import dev.kpetriv.sprout.core.domainledger.AllowancePaid
import dev.kpetriv.sprout.core.domainledger.AppendResult
import dev.kpetriv.sprout.core.domainledger.ChoreRewarded
import dev.kpetriv.sprout.core.domainledger.ManualAdjustment
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.domainledger.ParentAuthorization
import dev.kpetriv.sprout.core.model.AccountId
import dev.kpetriv.sprout.core.model.AllowanceCadence
import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.model.ChoreId
import dev.kpetriv.sprout.core.model.ChoreSchedule
import dev.kpetriv.sprout.core.model.ChoreSubmission
import dev.kpetriv.sprout.core.model.DayOfWeek
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.ScheduleId
import dev.kpetriv.sprout.core.model.SubmissionStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * D-M2-1 in the data layer: on a device without a usable strong biometric, the parent-only use cases append unsigned
 * when the household policy has parent signatures off and report it; when the policy requires signatures they write
 * nothing and say so.
 */
@RunWith(RobolectricTestRunner::class)
class ParentSignatureFallbackTest {
    private val household = testHousehold()
    private val today = CalendarDate(2026, 11, 2)
    private var prompts = 0

    @After
    fun close() = household.close()

    /** The requester throws, like a device that reports no strong biometric before prompting. */
    private val throwingProvider: suspend () -> ParentAuthorization = {
        prompts += 1
        throw ParentAuthUnavailableException(ParentAuthAvailability.NO_HARDWARE)
    }

    /** `feature/lock`'s shape: an authorisation whose `sign` throws. */
    private val unavailableProvider: suspend () -> ParentAuthorization = {
        prompts += 1
        ParentAuthorization { throw ParentAuthUnavailableException(ParentAuthAvailability.NOT_ENROLLED) }
    }

    private suspend fun signaturesOff() = household.policies.ensure(household.gbp)

    private suspend fun signaturesRequired() {
        assertTrue(household.registerParentKey().requireParentSignature)
    }

    private suspend fun events(kid: KidId) = household.ledger.observeEvents(kid, limit = 20).first()

    private suspend fun submission(kid: KidId): ChoreSubmission {
        val chore = Chore(
            ChoreId("tidy"),
            LocalTrial.HOUSEHOLD,
            kid,
            "Tidy your room",
            reward = ChoreReward(money = Money(50), minutes = Money(15)),
            schedule = ChoreSchedule.Weekly(DayOfWeek.MONDAY),
            createdAt = household.clock.now(),
        )
        household.chores.upsert(chore)
        return household.chores.submit(chore.id, today, note = null)
    }

    private suspend fun queueAllowance(kid: KidId, main: AccountId) {
        household.allowances.upsert(
            AllowanceSchedule(
                ScheduleId("weekly"),
                kid,
                main,
                Money(300),
                household.gbp,
                AllowanceCadence.Weekly(DayOfWeek.SATURDAY),
                startsOn = CalendarDate(2026, 10, 17),
            ),
        )
        assertEquals(3, household.plan(today).queued.size)
    }

    @Test
    fun anAdjustmentGoesInUnsignedWhenSignaturesAreOff() = runTest {
        val (kid, _) = household.addKid()
        signaturesOff()

        assertEquals(
            ManualAdjustmentResult.Unsigned(AppendResult.Accepted),
            household.adjust(kid.id, Money(250), "receipt", throwingProvider),
        )
        assertEquals(
            ManualAdjustmentResult.Unsigned(AppendResult.Accepted),
            household.adjust(kid.id, Money(100), "receipt", unavailableProvider),
        )
        val adjustments = events(kid.id).filterIsInstance<ManualAdjustment>()
        assertEquals(2, adjustments.size)
        assertTrue(adjustments.all { it.envelope.parentSignature == null })
    }

    @Test
    fun anAdjustmentIsRefusedWhenTheHouseholdRequiresSignatures() = runTest {
        val (kid, _) = household.addKid()
        signaturesRequired()

        assertEquals(
            ManualAdjustmentResult.SignatureRequired,
            household.adjust(kid.id, Money(250), "receipt", unavailableProvider),
        )
        assertEquals(
            ManualAdjustmentResult.SignatureRequired,
            household.adjust(kid.id, Money(250), "receipt", throwingProvider),
        )
        assertTrue(events(kid.id).isEmpty())
    }

    @Test
    fun anApprovalPaysEveryRewardUnsignedWithOneCheckWhenSignaturesAreOff() = runTest {
        val (kid, _) = household.addKid()
        signaturesOff()
        val submission = submission(kid.id)

        val approved = assertIs<ApprovalResult.Approved>(household.approve(submission.id) { unavailableProvider() })
        assertTrue(approved.unsigned)
        assertEquals(listOf(true, true), approved.rewards.map { it.unsigned })
        assertEquals(0, prompts, "with no parent key registered nothing could verify a signature: no prompt")
        assertEquals(SubmissionStatus.APPROVED, approved.submission.status)
        val rewards = events(kid.id).filterIsInstance<ChoreRewarded>()
        assertEquals(2, rewards.size)
        assertTrue(rewards.all { it.envelope.parentSignature == null })
    }

    @Test
    fun anApprovalIsRefusedAndStaysPendingWhenTheHouseholdRequiresSignatures() = runTest {
        val (kid, _) = household.addKid()
        signaturesRequired()
        val submission = submission(kid.id)

        assertIs<ApprovalResult.SignatureRequired>(
            household.approve(submission.id) { throwingProvider() },
        )
        assertEquals(SubmissionStatus.SUBMITTED, household.chores.getSubmission(submission.id)?.status)
        assertTrue(events(kid.id).isEmpty())
    }

    @Test
    fun aSignedApprovalIsNotReportedUnsigned() = runTest {
        val (kid, _) = household.addKid()
        household.registerParentKey()
        val approved = assertIs<ApprovalResult.Approved>(
            household.approve(submission(kid.id).id) { household.authorization() },
        )
        assertFalse(approved.unsigned)
    }

    @Test
    fun drainingAppendsUnsignedWhenSignaturesAreOff() = runTest {
        val (kid, accounts) = household.addKid()
        signaturesOff()
        queueAllowance(kid.id, with(household) { accounts.money() }.id)

        val drained = household.drain { unavailableProvider() }
        assertEquals(3, drained.unsigned.size)
        assertEquals(emptyList(), drained.signed)
        assertFalse(drained.signatureRequired)
        assertEquals(0, prompts)
        assertEquals(emptyList(), household.pending.pending())
        val paid = events(kid.id).filterIsInstance<AllowancePaid>()
        assertEquals(3, paid.size)
        assertTrue(paid.all { it.envelope.parentSignature == null })
    }

    @Test
    fun drainingStopsAndKeepsTheQueueWhenTheHouseholdRequiresSignatures() = runTest {
        val (kid, accounts) = household.addKid()
        signaturesRequired()
        queueAllowance(kid.id, with(household) { accounts.money() }.id)

        val drained = household.drain { throwingProvider() }
        assertTrue(drained.signatureRequired)
        assertEquals(emptyList(), drained.signed)
        assertEquals(emptyList(), drained.unsigned)
        assertEquals(3, household.pending.pending().size)
        assertNull(events(kid.id).firstOrNull())
    }
}
