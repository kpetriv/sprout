package dev.kpetriv.sprout.feature.parenthome

import dev.kpetriv.sprout.core.database.FailedParentAction
import dev.kpetriv.sprout.core.domainhousehold.AllowanceSchedule
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.domainledger.ParentAuthorization
import dev.kpetriv.sprout.core.model.AccountId
import dev.kpetriv.sprout.core.model.AllowanceCadence
import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.model.DayOfWeek
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.Kid
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.model.ScheduleId
import dev.kpetriv.sprout.core.model.YearMonth
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ParentHomeViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val household = HouseholdId("household-1")
    private val leo = Kid(KidId("leo"), household, "Leo", "avatar-2", YearMonth(2017, 6), KidUiTier.SPROUT)
    private val zoe = Kid(KidId("zoe"), household, "Zoe", "avatar-3", YearMonth(2013, 9), KidUiTier.SAPLING)

    private val kids = FakeKidRepository(leo, zoe)
    private val accounts = FakeAccountRepository()
    private val ledger = FakeLedgerRepository()
    private val chores = FakeChoreRepository()
    private val allowances = FakeAllowanceRepository()
    private val queue = FakePendingParentActionRepository()
    private val signer = FakeSigner(queue)
    private val householdStatus = MutableStateFlow(HouseholdStatus())
    private var statusFails = false

    private val authorization = ParentAuthorization { payload -> payload }

    private fun TestScope.viewModel(): ParentHomeViewModel {
        val status = HouseholdStatusSource {
            if (statusFails) flow { error("registry unreadable") } else householdStatus
        }
        val vm = ParentHomeViewModel(kids, accounts, ledger, chores, allowances, queue, signer, fixedToday(), status)
        backgroundScope.launch(main.dispatcher) { vm.state.collect {} }
        return vm
    }

    private fun ParentHomeViewModel.ready() = assertIs<ParentHomeUiState.Ready>(state.value)

    private fun test(block: suspend TestScope.() -> Unit) = runTest(main.dispatcher) { block() }

    private fun schedule(kidId: KidId, cadence: AllowanceCadence, active: Boolean = true) = AllowanceSchedule(
        id = ScheduleId("${kidId.value}-${cadence::class.simpleName}"),
        kidId = kidId,
        accountId = AccountId("${kidId.value}-main"),
        amount = Money(500),
        currency = GBP,
        cadence = cadence,
        startsOn = CalendarDate(2026, 1, 1),
        active = active,
    )

    @Test
    fun `counts approvals, payments to sign and anomalies`() = test {
        chores.setPending(3, leo.id)
        queue.pending.value = listOf(
            allowanceAction(leo.id, "2026-W44"),
            allowanceAction(zoe.id, "2026-W44"),
            interestAction(zoe.id, "2026-11"),
        )
        ledger.setAnomalies(leo.id, 2)
        ledger.setAnomalies(zoe.id, 1)

        val state = viewModel().ready()

        assertEquals(3, state.pendingApprovals)
        assertEquals(PendingActionsUi(allowances = 2, interest = 1), state.pendingActions)
        assertEquals(3, state.anomaliesTotal)
        assertEquals(leo, state.firstKidWithAnomalies)
    }

    @Test
    fun `each kid shows money and minutes, a missing balance as zero, and the earliest next allowance`() = test {
        accounts.accounts.value = accountsOf(leo.id) + accountsOf(zoe.id)
        ledger.setBalance(leo.id, AccountId("leo-main"), GBP, 1_250)
        ledger.setBalance(leo.id, AccountId("leo-minutes"), Currency.Minutes, 80)
        ledger.setBalance(leo.id, AccountId("leo-savings"), GBP, 9_999)
        allowances.schedules.value = listOf(
            schedule(leo.id, AllowanceCadence.Monthly(1)),
            schedule(leo.id, AllowanceCadence.Weekly(DayOfWeek.SATURDAY)),
            schedule(zoe.id, AllowanceCadence.Weekly(DayOfWeek.WEDNESDAY), active = false),
        )

        val (leoCard, zoeCard) = viewModel().ready().kids

        assertEquals(MoneyUi(Money(1_250), GBP), leoCard.money)
        assertEquals(Money(80), leoCard.minutes)
        // Today is Tuesday 3 November 2026: the weekly Saturday payment comes before the 1 December one.
        assertEquals(CalendarDate(2026, 11, 7), leoCard.nextAllowance)
        assertEquals(MoneyUi(Money.ZERO, GBP), zoeCard.money)
        assertEquals(Money.ZERO, zoeCard.minutes)
        assertNull(zoeCard.nextAllowance)
    }

    @Test
    fun `a kid without accounts and an empty household still load`() = test {
        val vm = viewModel()
        assertNull(vm.ready().kids.first().money)

        kids.kids.value = emptyList()
        assertEquals(emptyList(), vm.ready().kids)
        assertEquals(0, vm.ready().pendingActions.total)
    }

    @Test
    fun `a read failure shows the error state`() = test {
        chores.failReads = true
        assertEquals(ParentHomeUiState.Error, viewModel().state.value)
    }

    @Test
    fun `sign now reports progress per prompt and the final count`() = test {
        queue.pending.value = listOf(
            allowanceAction(leo.id, "2026-W44"),
            allowanceAction(zoe.id, "2026-W44"),
            interestAction(zoe.id, "2026-11"),
        )
        val prompts = Channel<Unit>()
        val vm = viewModel()

        vm.onEvent(
            ParentHomeEvent.SignNow {
                prompts.receive()
                authorization
            },
        )
        assertEquals(SigningProgress(0, 3), vm.ready().signing)
        prompts.send(Unit)
        assertEquals(SigningProgress(1, 3), vm.ready().signing)
        prompts.send(Unit)
        assertEquals(SigningProgress(2, 3), vm.ready().signing)
        prompts.send(Unit)

        val state = vm.ready()
        assertNull(state.signing)
        assertEquals(SignResult.Finished(signed = 3, alreadyPaid = 0, failed = 0, total = 3), state.signResult)
        assertEquals(0, state.pendingActions.total)

        vm.onEvent(ParentHomeEvent.SignResultShown)
        assertNull(vm.ready().signResult)
    }

    @Test
    fun `a cancelled prompt stops signing and keeps the rest queued`() = test {
        queue.pending.value = listOf(
            allowanceAction(leo.id, "2026-W44"),
            allowanceAction(zoe.id, "2026-W44"),
            interestAction(zoe.id, "2026-11"),
        )
        var calls = 0
        val vm = viewModel()

        vm.onEvent(
            ParentHomeEvent.SignNow {
                calls++
                if (calls == 2) error("cancelled") else authorization
            },
        )

        val state = vm.ready()
        assertEquals(SignResult.Stopped(signed = 1, total = 3), state.signResult)
        assertNull(state.signing)
        assertEquals(2, state.pendingActions.total)
    }

    @Test
    fun `already paid and refused payments are reported`() = test {
        val paid = allowanceAction(leo.id, "2026-W43")
        val refused = interestAction(zoe.id, "2026-11")
        queue.pending.value = listOf(paid, allowanceAction(leo.id, "2026-W44"), refused)
        signer.alreadyPaidKeys = setOf(paid.key)
        signer.failKeys = setOf(refused.key)
        val vm = viewModel()

        vm.onEvent(ParentHomeEvent.SignNow { authorization })

        assertEquals(SignResult.Finished(signed = 1, alreadyPaid = 1, failed = 1, total = 3), vm.ready().signResult)
        assertEquals(PendingActionsUi(allowances = 0, interest = 1), vm.ready().pendingActions)
    }

    @Test
    fun `sign now does nothing with an empty queue or while already signing`() = test {
        val vm = viewModel()
        vm.onEvent(ParentHomeEvent.SignNow { authorization })
        assertEquals(0, signer.calls)

        queue.pending.value = listOf(allowanceAction(leo.id, "2026-W44"))
        val prompts = Channel<Unit>()
        vm.onEvent(
            ParentHomeEvent.SignNow {
                prompts.receive()
                authorization
            },
        )
        vm.onEvent(ParentHomeEvent.SignNow { authorization })
        assertEquals(1, signer.calls)
        prompts.send(Unit)
        assertEquals(SignResult.Finished(1, 0, 0, 1), vm.ready().signResult)
    }

    @Test
    fun `each prompt carries the reason the drain gives for its payment`() = test {
        val first = allowanceAction(leo.id, "2026-W44")
        val second = interestAction(zoe.id, "2026-11")
        queue.pending.value = listOf(first, second)
        val shown = mutableListOf<String>()
        val vm = viewModel()

        vm.onEvent(
            ParentHomeEvent.SignNow { reason ->
                shown += reason
                authorization
            },
        )

        assertEquals(listOf("Pay ${first.key}", "Pay ${second.key}"), shown)
        assertEquals(signer.reasons, shown)
    }

    @Test
    fun `an invalidated parent key stops signing and asks for renewal`() = test {
        val second = allowanceAction(zoe.id, "2026-W44")
        queue.pending.value = listOf(allowanceAction(leo.id, "2026-W44"), second, interestAction(zoe.id, "2026-11"))
        signer.keyInvalidatedAt = second.key
        val vm = viewModel()

        vm.onEvent(ParentHomeEvent.SignNow { authorization })

        assertEquals(SignResult.KeyInvalidated(signed = 1, total = 3), vm.ready().signResult)
        assertEquals(2, vm.ready().pendingActions.total)
    }

    @Test
    fun `a drain that is already running is reported and changes nothing`() = test {
        queue.pending.value = listOf(allowanceAction(leo.id, "2026-W44"))
        signer.running = true
        val vm = viewModel()

        vm.onEvent(ParentHomeEvent.SignNow { authorization })

        assertEquals(SignResult.AlreadyRunning, vm.ready().signResult)
        assertNull(vm.ready().signing)
        assertEquals(1, vm.ready().pendingActions.total)
    }

    @Test
    fun `an invalid payment is reported as needing attention and listed on the dashboard`() = test {
        val invalid = interestAction(zoe.id, "2026-11")
        queue.pending.value = listOf(allowanceAction(leo.id, "2026-W44"), invalid)
        signer.invalidKeys = setOf(invalid.key)
        val vm = viewModel()

        vm.onEvent(ParentHomeEvent.SignNow { authorization })

        assertEquals(
            SignResult.Finished(signed = 1, alreadyPaid = 0, failed = 0, total = 2, needsAttention = 1),
            vm.ready().signResult,
        )
        val item = vm.ready().attention.single()
        assertEquals(
            AttentionItemUi(
                key = invalid.key,
                kidName = "Zoe",
                kind = PaymentKind.INTEREST,
                amount = MoneyUi(Money(21), GBP),
                period = "2026-11",
                reason = AttentionReason.FAILED,
            ),
            item,
        )
    }

    @Test
    fun `failed and skipped payments are listed and the parent can dismiss them`() = test {
        val skipped = allowanceAction(leo.id, "2026-W30")
        queue.failed.value = listOf(
            FailedParentAction(interestAction(zoe.id, "2026-10"), "rule", 3),
        )
        queue.skipped.value = listOf(skipped)
        val vm = viewModel()

        val listed = vm.ready().attention
        assertEquals(listOf(AttentionReason.FAILED, AttentionReason.SKIPPED), listed.map { it.reason })
        assertEquals(listOf("Zoe", "Leo"), listed.map { it.kidName })
        assertEquals(PaymentKind.ALLOWANCE, listed.last().kind)

        vm.onEvent(ParentHomeEvent.Dismiss(skipped.key))

        assertEquals(listOf(skipped.key), queue.dismissed)
        assertEquals(listOf(AttentionReason.FAILED), vm.ready().attention.map { it.reason })
    }

    @Test
    fun `the trial banner, sync line, waiting phones and refused events come from the household status`() = test {
        val vm = viewModel()
        assertEquals(HouseholdStatus(), vm.ready().household)
        val waiting = PendingDevice(DeviceId("ab".repeat(32)), "abab abab abab abab")
        val rejected = RejectedEvent(
            "PARENT_SIGNATURE_MISSING",
            DeviceLabel(Role.KID, "Leo", "c0ff ee00 d00d feed"),
            Instant.fromEpochSeconds(10),
        )
        householdStatus.value = HouseholdStatus(
            sync = SyncLine(Instant.fromEpochSeconds(20), blockedCode = "permission-denied", failing = false),
            rejected = listOf(rejected),
            pendingDevices = listOf(waiting),
        )
        val ready = vm.ready()
        assertEquals(listOf(waiting), ready.household.pendingDevices)
        assertEquals(listOf(rejected), ready.household.rejected)
        assertEquals("permission-denied", ready.household.sync?.blockedCode)
        householdStatus.value = HouseholdStatus(trial = true)
        assertEquals(true, vm.ready().household.trial)
    }

    @Test
    fun `a failing household status hides the cards instead of failing the dashboard`() = test {
        statusFails = true
        assertEquals(HouseholdStatus(), viewModel().ready().household)
    }

    @Test
    fun `quarantine codes map to short reasons, unknown ones to OTHER`() {
        assertEquals(RejectionReason.PARENT_SIGNATURE_MISSING, RejectionReason.of("PARENT_SIGNATURE_MISSING"))
        assertEquals(RejectionReason.DEVICE_NOT_ACTIVE, RejectionReason.of("DEVICE_NOT_ACTIVE"))
        assertEquals(RejectionReason.UNREADABLE, RejectionReason.of("SCHEMA_UNSUPPORTED"))
        assertEquals(RejectionReason.OTHER, RejectionReason.of("INSUFFICIENT_FUNDS"))
        assertEquals(RejectionReason.OTHER, RejectionReason.of("unknown"))
    }
}
