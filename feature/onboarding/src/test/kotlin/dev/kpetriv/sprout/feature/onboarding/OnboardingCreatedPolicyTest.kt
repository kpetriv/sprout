package dev.kpetriv.sprout.feature.onboarding

import dev.kpetriv.sprout.core.crypto.InMemoryDeviceKeyStore
import dev.kpetriv.sprout.core.database.Session
import dev.kpetriv.sprout.core.database.entity.RegistrationStatus
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.time.FakeClock
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The creating phone's household policy (D-REL-14, audit M1): `createHousehold` stores none on the server, so a policy
 * lost on this phone before it was stored is written again from the currency kept with the setup marker, after a pull.
 */
class OnboardingCreatedPolicyTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val log = CallLog()
    private val clock = FakeClock()
    private val auth = FakeOnboardingAuth(log)
    private val backend = FakeRegistrationBackend(log)
    private val sessions = FakeSessionStore(log)
    private val household = FakeOnboardingHousehold(log, sessions)
    private val keys = InMemoryDeviceKeyStore(clock)

    private fun viewModel() = OnboardingViewModel(auth, backend, household, keys, sessions, clock)

    private fun currentDevice(): DeviceId = keys.publicKey().deviceId()

    private fun claimsSequence(vararg claims: AccountClaims) {
        auth.claims.clear()
        auth.claims += claims
    }

    private fun OnboardingViewModel.signIn() {
        onEvent(OnboardingEvent.ChooseParent)
        onEvent(OnboardingEvent.EmailChanged("parent@example.test"))
        onEvent(OnboardingEvent.PasswordChanged("secret1"))
        onEvent(OnboardingEvent.SignInWithEmail)
    }

    @Test
    fun `the creating phone keeps the chosen currency with the setup marker`() {
        claimsSequence(parentClaims(householdId = null), parentClaims(householdId = null), parentClaims())
        val viewModel = viewModel()
        viewModel.signIn()
        viewModel.onEvent(OnboardingEvent.CurrencyChosen(Currency.Fiat("GBP")))
        viewModel.onEvent(OnboardingEvent.CreateHousehold)

        assertEquals(Currency.Fiat("GBP"), household.setupCurrency, "kept before the session is saved (D-REL-14)")
        viewModel.onEvent(OnboardingEvent.ParentLockDone)
        assertNull(household.setupCurrency, "dropped with the marker")
    }

    @Test
    fun `a process death between the session and the policy writes the policy after the resumed pull`() {
        // The creating phone died after saving its session: the policy exists nowhere (D-REL-14, audit M1).
        sessions.session.value =
            Session(PARENT_UID, HOUSEHOLD, Role.PARENT, null, NEW_DEVICE, RegistrationStatus.ACTIVE, clock.now())
        household.setupPending = HOUSEHOLD
        household.setupCurrency = Currency.Fiat("GBP")

        val viewModel = viewModel()

        assertEquals(listOf("refreshClaims", "sync", "ensurePolicy"), log.calls)
        assertEquals(Currency.Fiat("GBP"), household.policyCurrency, "the chosen currency, never the locale's")
        assertEquals(OnboardingStep.ParentLock, viewModel.state.value.step)
    }

    @Test
    fun `a resumed creating phone writes no policy when its pull failed or brought one`() {
        sessions.session.value =
            Session(PARENT_UID, HOUSEHOLD, Role.PARENT, null, NEW_DEVICE, RegistrationStatus.ACTIVE, clock.now())
        household.setupPending = HOUSEHOLD
        household.setupCurrency = Currency.Fiat("GBP")
        household.syncSucceeds = false

        viewModel()
        assertFalse("ensurePolicy" in log.calls, "offline: whether the household has a policy is unknown")

        household.syncSucceeds = true
        household.onSync = { household.policyCurrency = Currency.Fiat("CHF") }
        log.calls.clear()
        viewModel()
        assertFalse("ensurePolicy" in log.calls, "the pull brought the policy (pushed before the process died)")
        assertEquals(Currency.Fiat("CHF"), household.policyCurrency)
    }

    @Test
    fun `a policy write that failed on create is written by the retried Create after its pull`() {
        claimsSequence(parentClaims(householdId = null), parentClaims(householdId = null), parentClaims())
        household.ensureFailure = IllegalStateException("storage")
        val viewModel = viewModel()
        viewModel.signIn()
        viewModel.onEvent(OnboardingEvent.CurrencyChosen(Currency.Fiat("GBP")))
        viewModel.onEvent(OnboardingEvent.CreateHousehold)
        assertEquals(OnboardingFailure.UNKNOWN, viewModel.state.value.failure)
        assertNull(household.policyCurrency)

        // "Create" again: the household exists, so this phone is restored through the registry, then pulls.
        household.ensureFailure = null
        backend.statuses[RegistrationKey.CURRENT] = RegistryStatus.ACTIVE
        val device = currentDevice()
        household.onSync = { household.activeDevice = device }
        log.calls.clear()
        viewModel.onEvent(OnboardingEvent.CreateHousehold)

        assertEquals(1, keys.registrations, "no second household, no second key")
        assertTrue(log.calls.indexOf("sync") < log.calls.indexOf("ensurePolicy"))
        assertEquals(Currency.Fiat("GBP"), household.policyCurrency)
        assertEquals(OnboardingStep.ParentLock, viewModel.state.value.step)
    }
}
