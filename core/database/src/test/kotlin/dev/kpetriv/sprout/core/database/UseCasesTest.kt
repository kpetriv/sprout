package dev.kpetriv.sprout.core.database

import dev.kpetriv.sprout.core.database.usecase.ApprovalResult
import dev.kpetriv.sprout.core.database.usecase.ManualAdjustmentResult
import dev.kpetriv.sprout.core.database.usecase.PurchaseResult
import dev.kpetriv.sprout.core.domainhousehold.AllowanceSchedule
import dev.kpetriv.sprout.core.domainhousehold.Chore
import dev.kpetriv.sprout.core.domainhousehold.ChoreReward
import dev.kpetriv.sprout.core.domainhousehold.PendingParentAction
import dev.kpetriv.sprout.core.domainhousehold.Reward
import dev.kpetriv.sprout.core.domainledger.AllowancePaid
import dev.kpetriv.sprout.core.domainledger.AppendResult
import dev.kpetriv.sprout.core.domainledger.ChoreRewarded
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.InterestAccrued
import dev.kpetriv.sprout.core.domainledger.InterestCalculator
import dev.kpetriv.sprout.core.domainledger.ManualAdjustment
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.domainledger.RewardPurchased
import dev.kpetriv.sprout.core.domainledger.TimeBankCredited
import dev.kpetriv.sprout.core.domainledger.TransferToSavings
import dev.kpetriv.sprout.core.domainledger.Violation
import dev.kpetriv.sprout.core.model.AllowanceCadence
import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.model.ChoreId
import dev.kpetriv.sprout.core.model.ChoreSchedule
import dev.kpetriv.sprout.core.model.DayOfWeek
import dev.kpetriv.sprout.core.model.InterestPolicy
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.RewardId
import dev.kpetriv.sprout.core.model.RewardKind
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.model.ScheduleId
import dev.kpetriv.sprout.core.model.SubmissionStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class UseCasesTest {
    private val household = testHousehold()
    private val today = CalendarDate(2026, 11, 2)

    @After
    fun close() = household.close()

    private suspend fun balances(kid: KidId): Map<Currency, Money> =
        household.ledger.observeBalances(kid).first().filter { it.amount != Money.ZERO }
            .associate { it.currency to it.amount }

    private suspend fun fund(kid: KidId, amount: Long) {
        assertEquals(
            ManualAdjustmentResult.Signed(AppendResult.Accepted),
            household.adjust(kid, Money(amount), "pocket money jar") { household.authorization() },
        )
    }

    private suspend fun choreWithSubmission(kid: KidId): dev.kpetriv.sprout.core.model.ChoreSubmission {
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
        return household.chores.submit(chore.id, today, note = "done")
    }

    @Test
    fun approvingPaysOneRewardPerCurrencyExactlyOnceEvenWhenCalledTwice() = runTest {
        val (kid, _) = household.addKid()
        household.registerParentKey()
        val submission = choreWithSubmission(kid.id)
        val prompts = mutableListOf<Currency>()

        val first = assertIs<ApprovalResult.Approved>(
            household.approve(submission.id) { intent ->
                prompts += intent.currency
                household.authorization()
            },
        )
        assertEquals(listOf(household.gbp, Currency.Minutes), prompts)
        assertEquals(SubmissionStatus.APPROVED, first.submission.status)
        assertEquals(listOf(false, false), first.rewards.map { it.alreadyPaid })

        val second = assertIs<ApprovalResult.Approved>(
            household.approve(submission.id) { error("an approved chore must not prompt again") },
        )
        assertEquals(first.rewards.map { it.eventId }, second.rewards.map { it.eventId })
        assertEquals(listOf(true, true), second.rewards.map { it.alreadyPaid })

        val rewards = household.ledger.observeEvents(kid.id, limit = 10).first().filterIsInstance<ChoreRewarded>()
        assertEquals(2, rewards.size)
        assertTrue(rewards.all { it.envelope.parentSignature != null && it.dueOn == today })
        assertEquals(mapOf(household.gbp to Money(50), Currency.Minutes to Money(15)), balances(kid.id))
        assertEquals(2, household.parentAuthorizer.signaturesIssued)
    }

    @Test
    fun aCancelledPromptLeavesTheSubmissionToRetryAndOnlyTheMissingRewardIsPaid() = runTest {
        val (kid, _) = household.addKid()
        household.registerParentKey()
        val submission = choreWithSubmission(kid.id)

        assertFailsWith<IllegalStateException> {
            household.approve(submission.id) { intent ->
                check(intent.currency != Currency.Minutes) { "cancelled" }
                household.authorization()
            }
        }
        assertEquals(SubmissionStatus.SUBMITTED, household.chores.getSubmission(submission.id)?.status)
        assertEquals(mapOf<Currency, Money>(household.gbp to Money(50)), balances(kid.id))

        val retried = assertIs<ApprovalResult.Approved>(household.approve(submission.id) { household.authorization() })
        assertEquals(listOf(true, false), retried.rewards.map { it.alreadyPaid })
        assertEquals(mapOf(household.gbp to Money(50), Currency.Minutes to Money(15)), balances(kid.id))
        assertEquals(
            ApprovalResult.NotFound,
            household.approve(dev.kpetriv.sprout.core.model.SubmissionId("x")) {
                error("")
            },
        )
    }

    @Test
    fun aScreenTimePurchaseDebitsTheCostAndCreditsTheMinutes() = runTest {
        val (kid, _) = household.addKid()
        household.registerParentKey()
        fund(kid.id, 500)
        val screen = Reward(
            RewardId("screen"),
            LocalTrial.HOUSEHOLD,
            "30 minutes",
            RewardKind.SCREEN_TIME,
            Money(100),
            household.gbp,
            minutesGranted = 30,
        )
        household.rewards.upsert(screen)

        val purchased = assertIs<PurchaseResult.Purchased>(household.purchase(kid.id, screen.id))
        assertNotNull(purchased.timeCreditId)
        assertEquals(mapOf(household.gbp to Money(400), Currency.Minutes to Money(30)), balances(kid.id))

        val events = household.ledger.observeEvents(kid.id, limit = 10).first()
        val purchase = events.filterIsInstance<RewardPurchased>().single()
        val credit = events.filterIsInstance<TimeBankCredited>().single()
        assertEquals(purchased.purchaseId, purchase.eventId)
        assertEquals(Money(30), purchase.minutesGranted)
        assertEquals(purchase.eventId, credit.grantedBy)
        assertEquals(purchased.timeCreditId, credit.eventId)
        // Both are the kid's own events, unsigned by the parent.
        assertEquals(listOf(Role.KID, Role.KID), listOf(purchase, credit).map { it.envelope.authorRole })
        // Kid mode on a parent device: the parent's uid and device with role KID (D-M3-12).
        assertEquals(LocalTrial.PARENT, purchase.envelope.authorUid)
        assertEquals(null, credit.envelope.parentSignature)
        assertEquals(1, household.parentAuthorizer.signaturesIssued) // only the funding adjustment
    }

    @Test
    fun aPurchaseThatDoesNotMatchTheCatalogueIsRejected() = runTest {
        val (kid, accounts) = household.addKid()
        val main = with(household) { accounts.money() }
        household.registerParentKey()
        fund(kid.id, 500)
        val treat =
            Reward(
                RewardId("ice-cream"),
                LocalTrial.HOUSEHOLD,
                "Ice cream",
                RewardKind.TREAT,
                Money(150),
                household.gbp,
            )
        household.rewards.upsert(treat)

        val cheap = household.writer.appendAsKid(kid.id, main.id) {
            RewardPurchased(it, household.gbp, Money(1), treat.id.value)
        }
        assertEquals(RoomLedgerRepository.CATALOGUE_REJECTION, assertIs<AppendResult.Rejected>(cheap).reason)
        val withMinutes = household.writer.appendAsKid(kid.id, main.id) {
            RewardPurchased(it, household.gbp, Money(150), treat.id.value, minutesGranted = Money(60))
        }
        assertEquals(RoomLedgerRepository.CATALOGUE_REJECTION, assertIs<AppendResult.Rejected>(withMinutes).reason)
        val unknown = household.writer.appendAsKid(kid.id, main.id) {
            RewardPurchased(it, household.gbp, Money(150), "not-in-catalogue")
        }
        assertEquals(RoomLedgerRepository.CATALOGUE_REJECTION, assertIs<AppendResult.Rejected>(unknown).reason)
        val exact = household.writer.appendAsKid(kid.id, main.id) {
            RewardPurchased(it, household.gbp, Money(150), treat.id.value)
        }
        assertEquals(AppendResult.Accepted, exact)

        household.rewards.setActive(treat.id, active = false)
        assertEquals(PurchaseResult.Unavailable, household.purchase(kid.id, treat.id))
        assertEquals(PurchaseResult.Unavailable, household.purchase(kid.id, RewardId("missing")))
        val expensive = treat.copy(id = RewardId("zoo"), kind = RewardKind.OUTING, cost = Money(10_000))
        household.rewards.upsert(expensive)
        val refused = assertIs<PurchaseResult.Refused>(household.purchase(kid.id, expensive.id))
        assertIs<Violation.InsufficientFunds>(assertIs<AppendResult.Rejected>(refused.result).violations.single())
        assertEquals(mapOf<Currency, Money>(household.gbp to Money(350)), balances(kid.id))
    }

    @Test
    fun aManualAdjustmentIsParentSignedOnTheMoneyAccount() = runTest {
        val (kid, _) = household.addKid()
        household.registerParentKey()

        // The ledger's own rules still apply: a debit cannot overdraw, and it is refused before any prompt.
        val overdraw = assertIs<ManualAdjustmentResult.Refused>(
            household.adjust(kid.id, Money(-1), "oops") { error("an invalid adjustment must not prompt") },
        )
        assertIs<Violation.InsufficientFunds>(overdraw.result.violations.single())
        assertEquals(0, household.parentAuthorizer.signaturesIssued)

        assertEquals(
            ManualAdjustmentResult.Signed(AppendResult.Accepted),
            household.adjust(kid.id, Money(250), "  receipt  ") { household.authorization() },
        )
        val adjustment = household.ledger.observeEvents(kid.id, limit = 1).first().single() as ManualAdjustment
        assertEquals("receipt", adjustment.reason)
        assertEquals(household.gbp, adjustment.currency)
        assertNotNull(adjustment.envelope.parentSignature)

        val noAccounts = household.adjust(KidId("nobody"), Money(5), "x") { error("no account: must not prompt") }
        assertEquals(ManualAdjustmentResult.NoAccount, noAccounts)
    }

    @Test
    fun planningIsIdempotentAndDrainingSignsEachActionOnce() = runTest {
        val (kid, accounts) = household.addKid()
        household.registerParentKey()
        val main = with(household) { accounts.money() }
        // Saturdays from 3 October: five periods are due by Monday 2 November.
        val weekly = AllowanceSchedule(
            ScheduleId("leo-weekly"),
            kid.id,
            main.id,
            Money(300),
            household.gbp,
            AllowanceCadence.Weekly(DayOfWeek.SATURDAY),
            startsOn = CalendarDate(2026, 10, 3),
        )
        household.allowances.upsert(weekly)

        val planned = household.plan()
        assertEquals(5, planned.queued.size)
        assertTrue(planned.queued.all { it is PendingParentAction.PayAllowance })
        assertEquals(emptyList(), household.plan().queued)
        assertEquals(CalendarDate(2026, 10, 31), household.allowances.lastPaidOn(weekly.id))

        val drained = household.drain { household.authorization() }
        assertEquals(5, drained.signed.size)
        assertEquals(0, drained.alreadyPaid)
        assertEquals(emptyList(), drained.failed)
        assertEquals(5, household.parentAuthorizer.signaturesIssued)
        assertEquals(emptyList(), household.pending.pending())
        assertEquals(Money(1500), balances(kid.id)[household.gbp])
        val paid = household.ledger.observeEvents(kid.id, limit = 10).first().filterIsInstance<AllowancePaid>()
        assertEquals(
            setOf("2026-W40", "2026-W41", "2026-W42", "2026-W43", "2026-W44"),
            paid.map {
                it.periodKey
            }.toSet(),
        )

        // Nothing left: no prompt, and re-planning queues nothing.
        assertEquals(emptyList(), household.drain { error("nothing to sign") }.signed)
        assertEquals(emptyList(), household.plan().queued)
        // The next Saturday is a new period.
        assertEquals(1, household.plan(CalendarDate(2026, 11, 7)).queued.size)
    }

    @Test
    fun aPeriodAlreadyInTheLedgerIsMarkedDoneWithoutSigning() = runTest {
        val (kid, accounts) = household.addKid()
        household.registerParentKey()
        val main = with(household) { accounts.money() }
        val action = PendingParentAction.PayAllowance(
            ScheduleId("leo-weekly"),
            kid.id,
            main.id,
            Money(300),
            household.gbp,
            periodKey = "2026-W44",
            dueOn = CalendarDate(2026, 10, 31),
        )
        household.pending.enqueue(action)
        // Another path (a second parent device, say) already paid that week.
        household.writer.appendAsParent(kid.id, main.id, household.authorization()) {
            AllowancePaid(it, household.gbp, Money(300), "leo-weekly", "2026-W44")
        }

        val drained = household.drain { error("must not prompt for a paid period") }
        assertEquals(1, drained.alreadyPaid)
        assertEquals(emptyList(), household.pending.pending())
    }

    @Test
    fun interestStartsOnTheFirstPlanAndAccruesOnTheAccrualDay() = runTest {
        val (kid, accounts) = household.addKid()
        household.registerParentKey()
        val main = with(household) { accounts.money() }
        val savings = with(household) { accounts.savings() }
        fund(kid.id, 20_000)
        household.writer.append(kid.id, main.id) { TransferToSavings(it, household.gbp, Money(10_000), savings.id) }
        household.interest.set(InterestPolicy(kid.householdId, annualRateBasisPoints = 500, accrualDay = 1))

        assertEquals(emptyList(), household.plan().queued)
        assertEquals(today, household.interest.lastAccruedOn(savings.id))

        val december = CalendarDate(2026, 12, 1)
        val accrual = assertIs<PendingParentAction.AccrueInterest>(household.plan(december).queued.single())
        assertEquals(29, accrual.days)
        assertEquals(InterestCalculator.accrue(Money(10_000), 500, 29), accrual.amount)
        assertEquals(emptyList(), household.plan(december).queued)

        assertEquals(1, household.drain { household.authorization() }.signed.size)
        val interest = household.ledger.observeEvents(kid.id, limit = 1).first().single() as InterestAccrued
        assertEquals("2026-12", interest.periodKey)
        assertEquals(
            Money(10_000) + accrual.amount,
            household.ledger.observeBalances(kid.id).first().single {
                it.accountId ==
                    savings.id
            }.amount,
        )
    }
}
