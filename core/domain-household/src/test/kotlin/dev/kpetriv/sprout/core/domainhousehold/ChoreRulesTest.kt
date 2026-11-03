package dev.kpetriv.sprout.core.domainhousehold

import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.model.ChoreSchedule
import dev.kpetriv.sprout.core.model.ChoreState
import dev.kpetriv.sprout.core.model.DayOfWeek
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.model.MAX_DESCRIPTION_LENGTH
import dev.kpetriv.sprout.core.model.MAX_TITLE_LENGTH
import dev.kpetriv.sprout.core.model.SubmissionStatus
import dev.kpetriv.sprout.core.model.UserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class ChoreRulesTest {
    private val weekly = chore(ChoreSchedule.Weekly(DayOfWeek.MONDAY))
    private val parent = UserId("p1")

    @Test
    fun onlySaplingKidsPropose() {
        assertTrue(ChoreProposalRules.canPropose(KidUiTier.SAPLING))
        assertFalse(ChoreProposalRules.canPropose(KidUiTier.SPROUT))
        assertFalse(ChoreProposalRules.canPropose(KidUiTier.SEEDLING))
    }

    @Test
    fun aProposalCannotBeSubmittedUntilAccepted() {
        val proposal = ChoreProposalRules.propose(weekly)
        assertEquals(ChoreState.PROPOSED, proposal.state)
        assertFalse(ChoreProposalRules.canSubmit(proposal))
        assertFailsWith<IllegalArgumentException> { ChoreSubmissions.submit(proposal, date("2026-11-02"), CREATED_AT) }
        val accepted = ChoreProposalRules.accept(proposal)
        assertEquals(ChoreState.ACTIVE, accepted.state)
        assertTrue(ChoreProposalRules.canSubmit(accepted))
        ChoreSubmissions.submit(accepted, date("2026-11-02"), CREATED_AT)
    }

    @Test
    fun declinedProposalsAreArchived() {
        val declined = ChoreProposalRules.decline(ChoreProposalRules.propose(weekly))
        assertEquals(ChoreState.ARCHIVED, declined.state)
        assertTrue(declined.archived)
        assertFalse(ChoreProposalRules.canSubmit(declined))
        assertFailsWith<IllegalArgumentException> { ChoreProposalRules.accept(weekly) }
        assertFailsWith<IllegalArgumentException> { ChoreProposalRules.decline(declined) }
    }

    @Test
    fun submitChecksTheDueDate() {
        val submission = ChoreSubmissions.submit(weekly, date("2026-11-02"), CREATED_AT, note = "done")
        assertEquals(SubmissionStatus.SUBMITTED, submission.status)
        assertEquals(KID, submission.kidId)
        assertEquals(SubmissionIds.deterministic(weekly.id, date("2026-11-02")), submission.id)
        assertFailsWith<IllegalArgumentException> { ChoreSubmissions.submit(weekly, date("2026-11-03"), CREATED_AT) }
        // A "whenever" chore can be submitted on any date.
        ChoreSubmissions.submit(chore(ChoreSchedule.Once()), date("2026-11-03"), CREATED_AT)
    }

    @Test
    fun anAnyTimeChoreIsOneSubmissionWhateverDayItIsSubmittedOn() {
        val anyTime = chore(ChoreSchedule.Once())
        val monday = ChoreSubmissions.submit(anyTime, date("2026-11-02"), CREATED_AT)
        val friday = ChoreSubmissions.submit(anyTime, date("2026-11-06"), CREATED_AT)
        assertEquals(monday.id, friday.id)
        assertEquals(ChoreScheduler.anyTimeDueOn(anyTime), monday.dueOn)
        assertEquals(ChoreScheduler.anyTimeDueOn(anyTime), friday.dueOn)
        // So it has one reward claim: approving it pays once.
        val intents = ChoreSubmissions.rewardIntents(
            anyTime,
            ChoreSubmissions.approve(friday, CREATED_AT, parent),
            GBP,
        )
        assertEquals(listOf(ChoreScheduler.anyTimeDueOn(anyTime)), intents.map { it.dueOn })
    }

    @Test
    fun approvalIsIdempotentAndRejectionCanBeOverturned() {
        val submitted = ChoreSubmissions.submit(weekly, date("2026-11-02"), CREATED_AT)
        val approved = ChoreSubmissions.approve(submitted, CREATED_AT + 1.minutes, parent)
        assertSame(approved, ChoreSubmissions.approve(approved, CREATED_AT + 9.minutes, UserId("p2")))
        assertFailsWith<IllegalStateException> { ChoreSubmissions.reject(approved, CREATED_AT + 2.minutes, parent) }
        val rejected = ChoreSubmissions.reject(submitted, CREATED_AT + 1.minutes, parent, "try again")
        assertEquals("try again", rejected.rejectionReason)
        val overturned = ChoreSubmissions.approve(rejected, CREATED_AT + 3.minutes, parent)
        assertEquals(SubmissionStatus.APPROVED, overturned.status)
        assertEquals(null, overturned.rejectionReason)
    }

    @Test
    fun anApprovalPaysOneIntentPerRewardCurrency() {
        val both = chore(ChoreSchedule.Weekly(DayOfWeek.MONDAY), reward = ChoreReward(Money(50), Money(15)))
        val submitted = ChoreSubmissions.submit(both, date("2026-11-02"), CREATED_AT)
        assertFailsWith<IllegalArgumentException> { ChoreSubmissions.rewardIntents(both, submitted, GBP) }
        val approved = ChoreSubmissions.approve(submitted, CREATED_AT + 1.minutes, parent)
        val intents = ChoreSubmissions.rewardIntents(both, approved, GBP)
        assertEquals(listOf(GBP to Money(50), Currency.Minutes to Money(15)), intents.map { it.currency to it.amount })
        assertTrue(intents.all { it.submissionId == approved.id && it.choreId == both.id && it.kidId == KID })
        assertTrue(intents.all { it.dueOn == date("2026-11-02") })
        val minutesOnly = both.copy(reward = ChoreReward(minutes = Money(10)))
        assertEquals(
            listOf(Currency.Minutes),
            ChoreSubmissions.rewardIntents(minutesOnly, approved, GBP).map {
                it.currency
            },
        )
        assertFailsWith<IllegalArgumentException> { ChoreSubmissions.rewardIntents(both, approved, Currency.Minutes) }
        assertFailsWith<IllegalArgumentException> {
            ChoreSubmissions.rewardIntents(
                both.copy(id = dev.kpetriv.sprout.core.model.ChoreId("other")),
                approved,
                GBP,
            )
        }
    }

    @Test
    fun formValidationReportsEveryProblem() {
        val today = date("2026-11-02")
        val ok = ChoreDraft("Tidy room", money = Money(50), schedule = ChoreSchedule.Daily.EVERY_DAY)
        assertEquals(emptyList(), ChoreValidation.validate(ok, today))
        assertEquals(
            listOf(ChoreViolation.TITLE_BLANK, ChoreViolation.NO_REWARD, ChoreViolation.DUE_DATE_IN_PAST),
            ChoreValidation.validate(ChoreDraft(" ", "", schedule = ChoreSchedule.Once(date("2026-11-01"))), today),
        )
        val tooMuch = ChoreDraft(
            title = "t".repeat(MAX_TITLE_LENGTH + 1),
            description = "d".repeat(MAX_DESCRIPTION_LENGTH + 1),
            money = Money(0),
            minutes = Money(5),
            schedule = ChoreSchedule.Once(today),
        )
        assertEquals(
            listOf(
                ChoreViolation.TITLE_TOO_LONG,
                ChoreViolation.DESCRIPTION_TOO_LONG,
                ChoreViolation.REWARD_NOT_POSITIVE,
            ),
            ChoreValidation.validate(tooMuch, today),
        )
        assertEquals(
            listOf(ChoreViolation.DUE_DATE_IN_PAST),
            ChoreValidation.validate(chore(ChoreSchedule.Once(date("2026-10-31"))), today),
        )
        assertEquals(emptyList(), ChoreValidation.validate(chore(ChoreSchedule.Once()), today))
        assertFalse("secret" in ok.copy(description = "secret").toString())
    }
}
