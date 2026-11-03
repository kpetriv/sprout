package dev.kpetriv.sprout.feature.settings

import dev.kpetriv.sprout.core.domainhousehold.AllowanceSchedule
import dev.kpetriv.sprout.core.domainledger.AccountKind
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.model.AccountId
import dev.kpetriv.sprout.core.model.AllowanceCadence
import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.model.DayOfWeek
import dev.kpetriv.sprout.core.model.Kid
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.model.ScheduleId
import dev.kpetriv.sprout.core.model.YearMonth
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AllowanceSettingsViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val leo = Kid(KidId("leo"), HOUSEHOLD, "Leo", "avatar-2", YearMonth(2017, 6), KidUiTier.SPROUT)
    private val mia = Kid(KidId("mia"), HOUSEHOLD, "Mia", "avatar-1", YearMonth(2020, 3), KidUiTier.SEEDLING)
    private val kids = FakeKidRepository(leo, mia)
    private val accounts = FakeAccountRepository()
    private val allowances = FakeAllowanceRepository()

    private val leoWeekly = AllowanceSchedule(
        id = ScheduleId("leo-weekly"),
        kidId = leo.id,
        accountId = AccountId("leo-main"),
        amount = Money(500),
        currency = GBP,
        cadence = AllowanceCadence.Weekly(DayOfWeek.SATURDAY),
        startsOn = CalendarDate(2026, 10, 1),
    )

    private fun TestScope.viewModel(): AllowanceSettingsViewModel {
        val vm = AllowanceSettingsViewModel(kids, accounts, allowances, householdContext(), fixedToday())
        backgroundScope.launch(main.dispatcher) { vm.state.collect {} }
        return vm
    }

    private fun test(block: suspend TestScope.() -> Unit) = runTest(main.dispatcher) { block() }

    @Test
    fun `rows show each kid's allowance and the next due date on or after today`() = test {
        val miaMonthly = leoWeekly.copy(
            id = ScheduleId("mia-monthly"),
            kidId = mia.id,
            cadence = AllowanceCadence.Monthly(3),
        )
        allowances.schedules.value = listOf(leoWeekly, miaMonthly)

        val rows = viewModel().state.value.rows

        // Today is Tuesday 3 November 2026.
        assertEquals(CalendarDate(2026, 11, 7), rows.single { it.kid.id == leo.id }.nextDue)
        assertEquals(TODAY, rows.single { it.kid.id == mia.id }.nextDue)
    }

    @Test
    fun `a paused allowance or a kid without one has no next due date`() = test {
        allowances.schedules.value = listOf(leoWeekly.copy(active = false))

        val rows = viewModel().state.value.rows

        assertNull(rows.single { it.kid.id == leo.id }.nextDue)
        assertNull(rows.single { it.kid.id == mia.id }.schedule)
    }

    @Test
    fun `the editor starts from the existing schedule`() = test {
        allowances.schedules.value = listOf(leoWeekly)
        val vm = viewModel()

        vm.onEvent(AllowanceSettingsEvent.Edit(leo.id))

        val editor = assertNotNull(vm.state.value.editor)
        assertEquals("5.00", editor.amountText)
        assertEquals(CadenceKind.WEEKLY, editor.cadence)
        assertEquals(DayOfWeek.SATURDAY, editor.dayOfWeek)
        assertEquals(CalendarDate(2026, 11, 7), editor.nextDue)
    }

    @Test
    fun `amount is validated without floating point`() = test {
        val vm = viewModel()
        vm.onEvent(AllowanceSettingsEvent.Edit(mia.id))

        fun errorFor(text: String): AmountError? {
            vm.onEvent(AllowanceSettingsEvent.AmountChanged(text))
            vm.onEvent(AllowanceSettingsEvent.Save)
            return vm.state.value.editor?.amountError
        }

        assertEquals(AmountError.REQUIRED, errorFor(""))
        assertEquals(AmountError.MUST_BE_POSITIVE, errorFor("0"))
        assertEquals(AmountError.TOO_MANY_DECIMALS, errorFor("1.234"))
        assertEquals(AmountError.NOT_A_NUMBER, errorFor("five"))
        assertEquals(AmountError.TOO_LARGE, errorFor("99999999999999"))
        assertTrue(allowances.schedules.value.isEmpty())
    }

    @Test
    fun `the next due preview follows the cadence, day and active toggle`() = test {
        val vm = viewModel()
        vm.onEvent(AllowanceSettingsEvent.Edit(mia.id))
        vm.onEvent(AllowanceSettingsEvent.AmountChanged("2.50"))
        assertEquals(CalendarDate(2026, 11, 7), vm.state.value.editor?.nextDue)

        vm.onEvent(AllowanceSettingsEvent.CadenceChanged(CadenceKind.MONTHLY))
        vm.onEvent(AllowanceSettingsEvent.DayOfMonthChanged(31))
        assertEquals(28, vm.state.value.editor?.dayOfMonth)
        assertEquals(CalendarDate(2026, 11, 28), vm.state.value.editor?.nextDue)

        vm.onEvent(AllowanceSettingsEvent.StartsOnChanged(CalendarDate(2027, 1, 1)))
        assertEquals(CalendarDate(2027, 1, 28), vm.state.value.editor?.nextDue)

        vm.onEvent(AllowanceSettingsEvent.ActiveChanged(false))
        assertNull(vm.state.value.editor?.nextDue)
    }

    @Test
    fun `saving a new allowance creates the kid's accounts and pays into the main account`() = test {
        val vm = viewModel()
        vm.onEvent(AllowanceSettingsEvent.Edit(mia.id))
        vm.onEvent(AllowanceSettingsEvent.AmountChanged("3"))
        vm.onEvent(AllowanceSettingsEvent.CadenceChanged(CadenceKind.MONTHLY))
        vm.onEvent(AllowanceSettingsEvent.DayOfMonthChanged(15))
        vm.onEvent(AllowanceSettingsEvent.Save)

        val saved = allowances.schedules.value.single()
        val main = accounts.accounts.value.single {
            it.kidId == mia.id && it.kind == AccountKind.MAIN && it.currency == GBP
        }
        assertEquals(mia.id, saved.kidId)
        assertEquals(main.id, saved.accountId)
        assertEquals(Money(300), saved.amount)
        assertEquals(AllowanceCadence.Monthly(15), saved.cadence)
        assertEquals(TODAY, saved.startsOn)
        assertNull(vm.state.value.editor)
        assertEquals(SettingsMessage.SAVED, vm.state.value.message)
        assertEquals(CalendarDate(2026, 11, 15), vm.state.value.rows.single { it.kid.id == mia.id }.nextDue)
    }

    @Test
    fun `editing keeps the schedule id and account`() = test {
        allowances.schedules.value = listOf(leoWeekly)
        accounts.ensureDefaultAccounts(leo.id, GBP)
        val vm = viewModel()
        vm.onEvent(AllowanceSettingsEvent.Edit(leo.id))
        vm.onEvent(AllowanceSettingsEvent.AmountChanged("6.25"))
        vm.onEvent(AllowanceSettingsEvent.ActiveChanged(false))
        vm.onEvent(AllowanceSettingsEvent.Save)

        val saved = allowances.schedules.value.single()
        assertEquals(leoWeekly.id, saved.id)
        assertEquals(leoWeekly.accountId, saved.accountId)
        assertEquals(Money(625), saved.amount)
        assertEquals(false, saved.active)
    }

    @Test
    fun `a failed write keeps the sheet open`() = test {
        allowances.failWrites = true
        val vm = viewModel()
        vm.onEvent(AllowanceSettingsEvent.Edit(mia.id))
        vm.onEvent(AllowanceSettingsEvent.AmountChanged("1"))
        vm.onEvent(AllowanceSettingsEvent.Save)

        assertTrue(vm.state.value.editor?.saveFailed == true)
    }
}
