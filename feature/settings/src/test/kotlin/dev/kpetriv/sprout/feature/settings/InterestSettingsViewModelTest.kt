package dev.kpetriv.sprout.feature.settings

import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.model.InterestPolicy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class InterestSettingsViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val repository = FakeInterestPolicyRepository(InterestPolicy(HOUSEHOLD, 250, 1))

    private fun viewModel() = InterestSettingsViewModel(repository, householdContext())

    private fun test(block: suspend TestScope.() -> Unit) = runTest(main.dispatcher) { block() }

    @Test
    fun `percent text maps to basis points and back`() {
        fun bps(text: String) = (InterestRate.parse(text) as? InterestRate.Parsed.Valid)?.basisPoints
        fun error(text: String) = (InterestRate.parse(text) as? InterestRate.Parsed.Invalid)?.error

        assertEquals(250, bps("2.5"))
        assertEquals(250, bps("2,50"))
        assertEquals(5, bps("0.05"))
        assertEquals(0, bps("0"))
        assertEquals(2000, bps("20"))
        assertEquals(RateError.TOO_HIGH, error("20.01"))
        assertEquals(RateError.TOO_MANY_DECIMALS, error("2.505"))
        assertEquals(RateError.NOT_A_NUMBER, error("-1"))
        assertEquals(RateError.REQUIRED, error(" "))
        assertEquals("2.50", InterestRate.format(250))
        assertEquals("0.05", InterestRate.format(5))
        assertEquals("20.00", InterestRate.format(2000))
        assertEquals("0.00", InterestRate.format(0))
    }

    @Test
    fun `the stored policy fills the form and the preview uses the interest calculator`() = test {
        val state = viewModel().state.value

        assertFalse(state.isLoading)
        assertEquals("2.50", state.rateText)
        assertEquals(1, state.accrualDay)
        assertEquals(GBP, state.currency)
        // £100.00 × 250 bps × 30 days / 3 650 000 = 20.55p, half-even to 21p.
        assertEquals(InterestPreview(Money(10_000), Money(21)), state.preview)
        assertFalse(state.canSave)
    }

    @Test
    fun `the currency follows a household policy that arrives after the screen opened`() = test {
        val policies = FakeHouseholdPolicyRepository(initial = null)
        val vm = InterestSettingsViewModel(repository, householdContext(policies))
        assertEquals(Currency.Fiat("EUR"), vm.state.value.currency, "the locale's until the first pull")

        policies.policy.value = FakeHouseholdPolicyRepository.policy(hasKey = false)

        assertEquals(GBP, vm.state.value.currency)
    }

    @Test
    fun `an invalid rate shows an error, no preview and cannot be saved`() = test {
        val vm = viewModel()
        vm.onEvent(InterestSettingsEvent.RateChanged("25"))

        assertEquals(RateError.TOO_HIGH, vm.state.value.rateError)
        assertNull(vm.state.value.preview)
        assertFalse(vm.state.value.canSave)
        vm.onEvent(InterestSettingsEvent.Save)
        assertTrue(repository.writes.isEmpty())
    }

    @Test
    fun `saving writes basis points and the accrual day`() = test {
        val vm = viewModel()
        vm.onEvent(InterestSettingsEvent.RateChanged("3.75"))
        vm.onEvent(InterestSettingsEvent.AccrualDayChanged(40))
        assertEquals(28, vm.state.value.accrualDay)
        assertTrue(vm.state.value.canSave)

        vm.onEvent(InterestSettingsEvent.Save)

        assertEquals(listOf(InterestPolicy(HOUSEHOLD, 375, 28)), repository.writes)
        assertEquals(SettingsMessage.SAVED, vm.state.value.message)
        assertFalse(vm.state.value.canSave)
    }

    @Test
    fun `zero turns interest off and earns nothing`() = test {
        val vm = viewModel()
        vm.onEvent(InterestSettingsEvent.RateChanged("0"))

        assertEquals(Money.ZERO, vm.state.value.preview?.earned)
        vm.onEvent(InterestSettingsEvent.Save)
        assertEquals(0, repository.policy.value.annualRateBasisPoints)
    }

    @Test
    fun `a policy change from elsewhere does not overwrite the parent's edits`() = test {
        val vm = viewModel()
        vm.onEvent(InterestSettingsEvent.RateChanged("1"))
        repository.policy.value = InterestPolicy(HOUSEHOLD, 500, 2)

        assertEquals("1", vm.state.value.rateText)
        assertEquals(500, vm.state.value.saved?.annualRateBasisPoints)
    }

    @Test
    fun `a failed write reports it`() = test {
        repository.failWrites = true
        val vm = viewModel()
        vm.onEvent(InterestSettingsEvent.RateChanged("1"))
        vm.onEvent(InterestSettingsEvent.Save)

        assertEquals(SettingsMessage.SAVE_FAILED, vm.state.value.message)
        assertFalse(vm.state.value.isSaving)
    }
}
