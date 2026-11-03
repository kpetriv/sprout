package dev.kpetriv.sprout.core.database

import dev.kpetriv.sprout.core.crypto.FakeParentAuthorizer
import dev.kpetriv.sprout.core.crypto.ParentSigner
import dev.kpetriv.sprout.core.database.entity.PendingActionStatus
import dev.kpetriv.sprout.core.database.usecase.DrainPendingParentActionsUseCase
import dev.kpetriv.sprout.core.database.usecase.DrainResult
import dev.kpetriv.sprout.core.database.usecase.ParentEventAppender
import dev.kpetriv.sprout.core.domainhousehold.AllowanceSchedule
import dev.kpetriv.sprout.core.domainhousehold.PendingParentAction
import dev.kpetriv.sprout.core.domainledger.AllowancePaid
import dev.kpetriv.sprout.core.domainledger.AppendResult
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.domainledger.ParentAuthorization
import dev.kpetriv.sprout.core.domainledger.ParentAuthorizationRequester
import dev.kpetriv.sprout.core.domainledger.Violation
import dev.kpetriv.sprout.core.model.AccountId
import dev.kpetriv.sprout.core.model.AllowanceCadence
import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.model.DayOfWeek
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.ScheduleId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Draining the queue at a parent unlock: validate before prompting (R3), say what each prompt signs (R6), re-read the
 * schedule (R7), one drain at a time (R8), stuck and failed actions, and an invalidated parent key (audit M1).
 */
@RunWith(RobolectricTestRunner::class)
class PendingActionsDrainTest {
    private val household = testHousehold()
    private val saturday = CalendarDate(2026, 10, 31)
    private var prompts = 0

    @After
    fun close() = household.close()

    private suspend fun kidWithKey(): Pair<KidId, AccountId> {
        val (kid, accounts) = household.addKid()
        household.registerParentKey()
        return kid.id to with(household) { accounts.money() }.id
    }

    private fun schedule(kid: KidId, main: AccountId, amount: Long = 300, active: Boolean = true) = AllowanceSchedule(
        ScheduleId("leo-weekly"),
        kid,
        main,
        Money(amount),
        household.gbp,
        AllowanceCadence.Weekly(DayOfWeek.SATURDAY),
        startsOn = CalendarDate(2026, 10, 3),
        active = active,
    )

    private fun allowance(kid: KidId, main: AccountId, amount: Long = 300) = PendingParentAction.PayAllowance(
        ScheduleId("leo-weekly"),
        kid,
        main,
        Money(amount),
        household.gbp,
        periodKey = "2026-W44",
        dueOn = saturday,
    )

    private val counting: suspend (PendingParentAction) -> ParentAuthorization = {
        prompts += 1
        household.authorization()
    }

    @Test
    fun anInvalidActionFailsBeforeAnyPromptAndLeavesTheQueue() = runTest {
        val (kid, _) = kidWithKey()
        val savings = with(household) { household.accounts.accountsFor(kid).first().savings() }
        // The amount does not match its own terms, so the ledger would refuse it: InterestMismatch.
        val broken = PendingParentAction.AccrueInterest(
            kid,
            savings.id,
            household.gbp,
            principal = Money(10_000),
            annualRateBasisPoints = 500,
            days = 30,
            periodKey = "2026-11",
            amount = Money(999),
        )
        household.pending.enqueue(broken)

        val drained = household.drain(counting)
        assertEquals(0, prompts, "an invalid action is never prompted for")
        val failed = drained.failed.single()
        assertTrue(failed.permanent)
        val rejection = assertIs<AppendResult.Rejected>(failed.result)
        assertEquals(ParentEventAppender.INVALID_BEFORE_PROMPT, rejection.reason)
        assertIs<Violation.InterestMismatch>(rejection.violations.single())
        assertEquals(emptyList(), household.pending.observePending().first())
        val listed = household.pending.observeFailed().first().single()
        assertEquals(broken, listed.action)
        assertTrue(listed.reason.orEmpty().contains("InterestMismatch"))
    }

    @Test
    fun anActionTheLedgerKeepsRefusingFailsOnTheThirdRefusal() = runTest {
        val (kid, main) = kidWithKey()
        household.allowances.upsert(schedule(kid, main))
        household.pending.enqueue(allowance(kid, main))
        // Signed by a key that is not the registered one: valid content, refused after signing.
        val stranger = FakeParentAuthorizer().also { it.createParentKey() }
        val forged: suspend (PendingParentAction) -> ParentAuthorization = {
            ParentAuthorizations(ParentSigner()).from(stranger.initSignature())
        }

        repeat(2) {
            val failed = household.drain(forged).failed.single()
            assertEquals(false, failed.permanent)
            assertEquals(1, household.pending.pending().size)
        }
        val third = household.drain(forged).failed.single()
        assertTrue(third.permanent)
        assertEquals(RoomLedgerRepository.PARENT_SIGNATURE_REJECTION, (third.result as AppendResult.Rejected).reason)
        assertEquals(emptyList(), household.pending.pending())
        assertEquals(3, household.pending.observeFailed().first().single().refusals)
        assertEquals(emptyList(), household.drain { error("a failed action is not prompted for") }.failed)
    }

    @Test
    fun eachPromptSaysWhoIsPaidHowMuchAndForWhichPeriod() = runTest {
        val (kid, main) = kidWithKey()
        household.allowances.upsert(schedule(kid, main))
        household.pending.enqueue(allowance(kid, main))
        val reasons = mutableListOf<String>()
        val requester = ParentAuthorizationRequester { reason ->
            reasons += reason
            household.authorization()
        }

        val drained = assertIs<DrainResult.Drained>(household.drainUseCase.drainWith(requester))
        assertEquals(1, drained.signed.size)
        assertEquals(listOf("Pay Leo 3.00 GBP allowance for 2026-W44"), reasons)
    }

    @Test
    fun aDeactivatedScheduleOrAnArchivedKidCancelsTheActionWithoutAPrompt() = runTest {
        val (kid, main) = kidWithKey()
        household.allowances.upsert(schedule(kid, main, active = false))
        household.pending.enqueue(allowance(kid, main))

        val inactive = household.drain(counting)
        assertEquals(listOf<PendingParentAction>(allowance(kid, main)), inactive.cancelled)
        assertEquals(0, prompts)
        assertEquals(emptyList(), household.pending.pending())

        // A cancelled key can be queued again; an archived kid cancels it too.
        household.allowances.upsert(schedule(kid, main))
        assertTrue(household.pending.enqueue(allowance(kid, main)))
        household.kids.archive(kid)
        assertEquals(1, household.drain(counting).cancelled.size)
        assertEquals(0, prompts)
    }

    @Test
    fun aChangedAmountIsReplannedSoTheParentSignsTheCurrentAmount() = runTest {
        val (kid, main) = kidWithKey()
        household.allowances.upsert(schedule(kid, main, amount = 500))
        household.pending.enqueue(allowance(kid, main, amount = 300))
        val reasons = mutableListOf<String>()

        val drained = assertIs<DrainResult.Drained>(
            household.drainUseCase.drainWith { reason ->
                reasons += reason
                household.authorization()
            },
        )
        assertEquals(listOf<PendingParentAction>(allowance(kid, main, amount = 300)), drained.cancelled)
        assertEquals(listOf<PendingParentAction>(allowance(kid, main, amount = 500)), drained.replanned)
        assertEquals(listOf("Pay Leo 5.00 GBP allowance for 2026-W44"), reasons)
        val paid = household.ledger.observeEvents(kid, limit = 5).first().filterIsInstance<AllowancePaid>().single()
        assertEquals(Money(500), paid.amount)
        assertEquals(emptyList(), household.pending.pending())
    }

    @Test
    fun aSecondDrainWhileOneIsRunningReturnsAlreadyRunning() = runTest {
        val (kid, main) = kidWithKey()
        household.allowances.upsert(schedule(kid, main))
        household.pending.enqueue(allowance(kid, main))
        val promptShown = CompletableDeferred<Unit>()
        val parentTouched = CompletableDeferred<Unit>()

        val first = async {
            household.drainUseCase { _ ->
                promptShown.complete(Unit)
                parentTouched.await()
                household.authorization()
            }
        }
        promptShown.await()
        assertEquals(DrainResult.AlreadyRunning, household.drainUseCase { error("must not prompt") })
        parentTouched.complete(Unit)
        yield()
        assertEquals(1, assertIs<DrainResult.Drained>(first.await()).signed.size)
        // Free again once the first drain ended.
        assertIs<DrainResult.Drained>(household.drainUseCase { error("nothing left to sign") })
    }

    @Test
    fun anInvalidatedParentKeyStopsTheDrainAndKeepsTheQueue() = runTest {
        val (kid, main) = kidWithKey()
        household.allowances.upsert(schedule(kid, main))
        household.pending.enqueue(allowance(kid, main))
        household.parentAuthorizer.invalidateKey()

        val drained = household.drain { household.authorization() }
        assertTrue(drained.keyInvalidated)
        assertEquals(emptyList(), drained.signed)
        assertEquals(1, household.pending.pending().size)
        assertEquals(emptyList(), household.ledger.observeEvents(kid, limit = 5).first())
    }

    @Test
    fun skippedAllowancePeriodsAreRecordedForTheDashboard() = runTest {
        val (kid, main) = kidWithKey()
        // Saturdays from 1 August: fourteen are due by 2 November, the oldest six are past the catch-up window.
        household.allowances.upsert(schedule(kid, main).copy(startsOn = CalendarDate(2026, 8, 1)))

        val planned = household.plan(CalendarDate(2026, 11, 2))
        assertEquals(8, planned.queued.size)
        val skipped = household.pending.observeSkipped().first()
        val skippedDates = skipped.map { (it as PendingParentAction.PayAllowance).dueOn }
        assertEquals(planned.skippedAllowance.values.single(), skippedDates)
        assertEquals(6, skipped.size)
        // Skipped periods are neither pending nor queued again.
        assertEquals(8, household.pending.pending().size)
        assertEquals(emptyList(), household.plan(CalendarDate(2026, 11, 2)).queued)
        assertEquals(
            PendingActionStatus.SKIPPED,
            household.database.pendingParentActionDao().get(skipped.first().key)?.status,
        )
    }

    @Test
    fun theParentDismissesFailedAndSkippedActionsFromTheDashboard() = runTest {
        val (kid, main) = kidWithKey()
        household.allowances.upsert(schedule(kid, main).copy(startsOn = CalendarDate(2026, 8, 1)))
        household.plan(CalendarDate(2026, 11, 2))
        val skipped = household.pending.observeSkipped().first().first()
        val pendingKey = household.pending.pending().first().key
        household.pending.markFailed(pendingKey, "test")

        assertTrue(household.pending.dismiss(skipped.key))
        assertTrue(household.pending.dismiss(pendingKey))
        assertEquals(false, household.pending.dismiss(pendingKey), "already dismissed")
        val stillPending = household.pending.pending().first().key
        assertEquals(false, household.pending.dismiss(stillPending), "a pending action is not dismissable")
        assertEquals(5, household.pending.observeSkipped().first().size)
        assertEquals(emptyList(), household.pending.observeFailed().first())
        assertEquals(
            PendingActionStatus.CANCELLED,
            household.database.pendingParentActionDao().get(skipped.key)?.status,
        )
    }
}
