package dev.kpetriv.sprout.feature.settings

import dev.kpetriv.sprout.core.domainhousehold.SavingsGoal
import dev.kpetriv.sprout.core.domainledger.Account
import dev.kpetriv.sprout.core.domainledger.AccountKind
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.model.AccountId
import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.model.GoalId
import dev.kpetriv.sprout.core.model.Kid
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.KidUiTier
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
class SavingsGoalsViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val leo = Kid(KidId("leo"), HOUSEHOLD, "Leo", "avatar-2", YearMonth(2017, 6), KidUiTier.SPROUT)
    private val zoe = Kid(KidId("zoe"), HOUSEHOLD, "Zoe", "avatar-3", YearMonth(2013, 9), KidUiTier.SAPLING)
    private val kids = FakeKidRepository(leo, zoe)
    private val accounts = FakeAccountRepository()
    private val goals = FakeSavingsGoalRepository()
    private val bike = SavingsGoal(GoalId("bike"), zoe.id, "New bike", Money(12_000), CalendarDate(2026, 9, 1))

    private fun TestScope.viewModel(): SavingsGoalsViewModel {
        val vm = SavingsGoalsViewModel(kids, accounts, goals, householdContext(), fixedToday())
        backgroundScope.launch(main.dispatcher) { vm.state.collect {} }
        return vm
    }

    private fun test(block: suspend TestScope.() -> Unit) = runTest(main.dispatcher) { block() }

    @Test
    fun `the first kid is selected and the currency falls back to the household's`() = test {
        goals.goals.value = listOf(bike)
        val state = viewModel().state.value

        assertEquals(leo.id, state.selectedKidId)
        assertTrue(state.goals.isEmpty())
        assertEquals(GBP, state.currency)
    }

    @Test
    fun `selecting a kid shows their goals in their savings account currency`() = test {
        goals.goals.value = listOf(bike)
        val eur = Currency.Fiat("EUR")
        accounts.accounts.value = listOf(Account(AccountId("zoe-savings"), zoe.id, eur, AccountKind.SAVINGS))
        val vm = viewModel()

        vm.onEvent(SavingsGoalsEvent.SelectKid(zoe.id))

        assertEquals(listOf(bike), vm.state.value.goals)
        assertEquals(eur, vm.state.value.currency)
    }

    @Test
    fun `title and target are validated`() = test {
        val vm = viewModel()
        vm.onEvent(SavingsGoalsEvent.AddGoal)

        vm.onEvent(SavingsGoalsEvent.Save)
        assertEquals(NameError.REQUIRED, vm.state.value.editor?.titleError)
        assertEquals(AmountError.REQUIRED, vm.state.value.editor?.targetError)

        vm.onEvent(SavingsGoalsEvent.TitleChanged("x".repeat(81)))
        vm.onEvent(SavingsGoalsEvent.TargetChanged("0.00"))
        vm.onEvent(SavingsGoalsEvent.Save)
        assertEquals(NameError.TOO_LONG, vm.state.value.editor?.titleError)
        assertEquals(AmountError.MUST_BE_POSITIVE, vm.state.value.editor?.targetError)
        assertTrue(goals.goals.value.isEmpty())
    }

    @Test
    fun `saving a new goal stores minor units and today's date`() = test {
        val vm = viewModel()
        vm.onEvent(SavingsGoalsEvent.AddGoal)
        vm.onEvent(SavingsGoalsEvent.TitleChanged(" Lego set "))
        vm.onEvent(SavingsGoalsEvent.TargetChanged("45.5"))
        vm.onEvent(SavingsGoalsEvent.Save)

        val goal = goals.goals.value.single()
        assertEquals(leo.id, goal.kidId)
        assertEquals("Lego set", goal.title)
        assertEquals(Money(4_550), goal.target)
        assertEquals(TODAY, goal.createdOn)
        assertNull(vm.state.value.editor)
        assertEquals(listOf(goal), vm.state.value.goals)
    }

    @Test
    fun `editing keeps the goal's id and creation date, delete removes it`() = test {
        goals.goals.value = listOf(bike)
        val vm = viewModel()
        vm.onEvent(SavingsGoalsEvent.SelectKid(zoe.id))
        vm.onEvent(SavingsGoalsEvent.EditGoal(bike.id))
        assertEquals("120.00", vm.state.value.editor?.targetText)

        vm.onEvent(SavingsGoalsEvent.TargetChanged("150"))
        vm.onEvent(SavingsGoalsEvent.Save)
        assertEquals(bike.copy(target = Money(15_000)), goals.goals.value.single())

        vm.onEvent(SavingsGoalsEvent.EditGoal(bike.id))
        vm.onEvent(SavingsGoalsEvent.Delete)
        assertTrue(goals.goals.value.isEmpty())
        assertEquals(SettingsMessage.GOAL_DELETED, vm.state.value.message)
    }

    @Test
    fun `a failed delete keeps the sheet open`() = test {
        goals.goals.value = listOf(bike)
        val vm = viewModel()
        vm.onEvent(SavingsGoalsEvent.SelectKid(zoe.id))
        vm.onEvent(SavingsGoalsEvent.EditGoal(bike.id))
        goals.failWrites = true
        vm.onEvent(SavingsGoalsEvent.Delete)

        assertTrue(assertNotNull(vm.state.value.editor).saveFailed)
    }
}
