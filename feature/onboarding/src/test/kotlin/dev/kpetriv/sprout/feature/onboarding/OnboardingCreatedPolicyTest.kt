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
 * A household policy that exists nowhere (D-REL-14, audit M1 and L1): `createHousehold` stores none on the server, so
 * a policy lost on the creating phone before it was stored or pushed is recovered after a pull, through the app's
 * recovery checks (nothing held, no policy document on the server) and with the currency the server stored at
 * creation, never one kept on this phone.
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

    init {
        // Every registry row belongs to the signed-in account unless a test says otherwise (D-REL-27).
        household.ownerOf = { auth.lastClaims?.let { RegisteredOwner(it.uid, it.role ?: Role.PARENT, it.kidId) } }
    }

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
    fun `the creating phone keeps no currency of its own beside the setup marker`() {
        claimsSequence(parentClaims(householdId = null), parentClaims(householdId = null), parentClaims())
        val viewModel = viewModel()
        viewModel.signIn()
        viewModel.onEvent(OnboardingEvent.CurrencyChosen(Currency.Fiat("GBP")))
        viewModel.onEvent(OnboardingEvent.CreateHousehold)

        assertEquals(Currency.Fiat("GBP"), household.policyCurrency, "written right after the session")
        assertEquals(0, household.recoveryChecks, "a fresh create needs no recovery")
        assertEquals(OnboardingStep.ParentLock, viewModel.state.value.step)
    }

    @Test
    fun `a process death between the session and the policy recovers the policy after the resumed pull`() {
        // The creating phone died after saving its session: the policy exists nowhere (D-REL-14, audit M1).
        sessions.session.value =
            Session(PARENT_UID, HOUSEHOLD, Role.PARENT, null, NEW_DEVICE, RegistrationStatus.ACTIVE, clock.now())
        household.setupPending = HOUSEHOLD
        household.serverCurrency = Currency.Fiat("GBP")

        val viewModel = viewModel()

        assertEquals(listOf("refreshClaims", "sync", "recoverPolicy"), log.calls)
        assertEquals(Currency.Fiat("GBP"), household.policyCurrency, "the server's currency, never the locale's")
        assertEquals(OnboardingStep.ParentLock, viewModel.state.value.step)
    }

    @Test
    fun `a resumed phone recovers nothing when its pull failed, a policy is held or stored, or the pull brought one`() {
        sessions.session.value =
            Session(PARENT_UID, HOUSEHOLD, Role.PARENT, null, NEW_DEVICE, RegistrationStatus.ACTIVE, clock.now())
        household.setupPending = HOUSEHOLD
        household.serverCurrency = Currency.Fiat("GBP")

        household.syncSucceeds = false
        viewModel()
        assertEquals(0, household.recoveryChecks, "offline: whether the household has a policy is unknown")

        household.syncSucceeds = true
        household.policyHeld = true
        viewModel()
        assertFalse("recoverPolicy" in log.calls, "a pulled policy waits for its author (audit L1)")
        household.policyHeld = false

        household.serverPolicyStored = true
        viewModel()
        assertFalse("recoverPolicy" in log.calls, "the server holds a policy this phone has not applied (audit L1)")
        household.serverPolicyStored = false

        household.onSync = { household.policyCurrency = Currency.Fiat("CHF") }
        viewModel()
        assertFalse("recoverPolicy" in log.calls, "the pull brought the policy (pushed before the process died)")
        assertEquals(Currency.Fiat("CHF"), household.policyCurrency)
    }

    @Test
    fun `a policy write that failed on create is recovered by the retried Create after its pull`() {
        claimsSequence(parentClaims(householdId = null), parentClaims(householdId = null), parentClaims())
        household.serverCurrency = Currency.Fiat("GBP")
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
        assertTrue(log.calls.indexOf("sync") < log.calls.indexOf("recoverPolicy"))
        assertEquals(Currency.Fiat("GBP"), household.policyCurrency, "the server's currency")
        assertEquals(OnboardingStep.ParentLock, viewModel.state.value.step)
    }
}
