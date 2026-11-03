package dev.kpetriv.sprout.feature.onboarding

import dev.kpetriv.sprout.core.crypto.InMemoryDeviceKeyStore
import dev.kpetriv.sprout.core.database.Session
import dev.kpetriv.sprout.core.database.entity.RegistrationStatus
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.time.FakeClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val log = CallLog()
    private val clock = FakeClock()
    private val auth = FakeOnboardingAuth(log)
    private val backend = FakeRegistrationBackend(log)
    private val household = FakeOnboardingHousehold(log)
    private val keys = InMemoryDeviceKeyStore(clock)
    private val sessions = FakeSessionStore(log)

    private fun viewModel() = OnboardingViewModel(auth, backend, household, keys, sessions, clock)

    private fun OnboardingViewModel.signIn(email: String = "parent@example.test", password: String = "secret1") {
        onEvent(OnboardingEvent.ChooseParent)
        onEvent(OnboardingEvent.EmailChanged(email))
        onEvent(OnboardingEvent.PasswordChanged(password))
        onEvent(OnboardingEvent.SignInWithEmail)
    }

    @Test
    fun `the debug sign-in takes e-mail and password, never shows them in toString, and needs both`() {
        val viewModel = viewModel()
        viewModel.onEvent(OnboardingEvent.ChooseParent)
        viewModel.onEvent(OnboardingEvent.EmailChanged("parent@example.test"))
        viewModel.onEvent(OnboardingEvent.PasswordChanged("123"))
        val step = viewModel.state.value.step as OnboardingStep.ParentSignIn
        assertTrue(step.emailPassword)
        assertFalse(step.canSubmit, "a password shorter than 6 characters cannot be submitted")
        viewModel.onEvent(OnboardingEvent.SignInWithEmail)
        assertEquals(emptyList(), log.calls)
        assertFalse("parent@example.test" in viewModel.state.value.toString())
        assertFalse("123" in step.toString())
    }

    @Test
    fun `a refused sign-in shows the typed failure and stays on the sign-in step`() {
        auth.signInResult = Result.failure(OnboardingException(OnboardingFailure.INVALID_CREDENTIAL))
        val viewModel = viewModel()
        viewModel.signIn()
        val state = viewModel.state.value
        assertEquals(OnboardingFailure.INVALID_CREDENTIAL, state.failure)
        assertTrue(state.step is OnboardingStep.ParentSignIn)
        assertFalse(state.busy)
    }

    @Test
    fun `release builds offer Google sign-in`() {
        val viewModel =
            OnboardingViewModel(
                FakeOnboardingAuth(log, usesEmailPassword = false),
                backend,
                household,
                keys,
                sessions,
                clock,
            )
        viewModel.onEvent(OnboardingEvent.ChooseParent)
        assertEquals(OnboardingStep.ParentSignIn(emailPassword = false), viewModel.state.value.step)
    }

    @Test
    fun `a new parent picks the currency, registers a fresh key, creates the household and saves an ACTIVE session`() {
        auth.claims.clear()
        auth.claims += parentClaims(householdId = null)
        auth.claims += parentClaims()
        val viewModel = viewModel()
        viewModel.signIn()
        val create = viewModel.state.value.step as OnboardingStep.CreateHousehold
        assertEquals(Currency.Fiat("EUR"), create.currency, "the locale's currency comes first")
        viewModel.onEvent(OnboardingEvent.CurrencyChosen(Currency.Fiat("GBP")))
        viewModel.onEvent(OnboardingEvent.CreateHousehold)

        assertEquals(
            listOf(
                "signInWithEmail",
                "refreshClaims",
                "begin",
                "createHousehold",
                "refreshClaims",
                "saveSession:PARENT:ACTIVE",
                "ensurePolicy",
            ),
            log.calls,
        )
        assertEquals(1, keys.registrations, "every registration uses a fresh key")
        assertEquals(Currency.Fiat("GBP"), backend.createdWith)
        assertEquals(Currency.Fiat("GBP"), household.policyCurrency)
        val session = sessions.session.value!!
        assertEquals(
            Session(PARENT_UID, HOUSEHOLD, Role.PARENT, null, NEW_DEVICE, RegistrationStatus.ACTIVE, clock.now()),
            session,
        )
        assertEquals(OnboardingStep.ParentLock, viewModel.state.value.step)

        viewModel.onEvent(OnboardingEvent.ParentLockDone)
        assertTrue(viewModel.state.value.finished)
    }

    @Test
    fun `trial data is confirmed before anything is created and purged before the session is saved`() {
        household.trial = true
        auth.claims.clear()
        auth.claims += parentClaims(householdId = null)
        auth.claims += parentClaims()
        val viewModel = viewModel()
        viewModel.signIn()
        viewModel.onEvent(OnboardingEvent.CreateHousehold)
        assertEquals(OnboardingStep.ConfirmPurge(forKidDevice = false), viewModel.state.value.step)
        assertFalse("begin" in log.calls, "nothing happens on the server before the parent confirms")

        viewModel.onEvent(OnboardingEvent.ConfirmPurge)
        val calls = log.calls
        assertTrue(calls.indexOf("createHousehold") < calls.indexOf("purge"))
        assertTrue(calls.indexOf("purge") < calls.indexOf("saveSession:PARENT:ACTIVE"))
        assertEquals(OnboardingStep.ParentLock, viewModel.state.value.step)
    }

    @Test
    fun `cancelling the trial-data screen goes back to the welcome screen and signs out, creating nothing`() {
        household.trial = true
        auth.claims[0] = parentClaims(householdId = null)
        val viewModel = viewModel()
        viewModel.signIn()
        viewModel.onEvent(OnboardingEvent.CreateHousehold)
        viewModel.onEvent(OnboardingEvent.Back)
        assertEquals(OnboardingStep.Welcome, viewModel.state.value.step)
        assertEquals(listOf("signInWithEmail", "refreshClaims", "signOut"), log.calls)
        assertTrue(household.trial)
        assertNull(sessions.session.value)
    }

    @Test
    fun `a second parent phone registers PENDING and waits, syncing every 5 s, until it is approved`() = runTest {
        auth.claims[0] = parentClaims()
        household.onSync = {
            if (household.syncs == 3) {
                sessions.session.value = sessions.session.value?.copy(deviceStatus = RegistrationStatus.ACTIVE)
            }
        }
        val viewModel = viewModel()
        viewModel.signIn()
        assertEquals(
            OnboardingStep.WaitingForApproval(displayFingerprint(NEW_DEVICE)),
            viewModel.state.value.step,
        )
        assertEquals("ab12 cd34 ef56 7890", displayFingerprint(NEW_DEVICE))
        assertEquals(RegistrationStatus.PENDING, sessions.session.value?.deviceStatus)
        assertEquals(1, household.syncs)
        advanceTimeBy(OnboardingViewModel.POLL_INTERVAL_MS)
        runCurrent()
        assertEquals(2, household.syncs)
        advanceTimeBy(OnboardingViewModel.POLL_INTERVAL_MS)
        runCurrent()
        assertEquals(3, household.syncs)
        assertEquals(OnboardingStep.ParentLock, viewModel.state.value.step)
        advanceTimeBy(OnboardingViewModel.POLL_INTERVAL_MS * 3)
        runCurrent()
        assertEquals(3, household.syncs, "polling stops once approved")
        assertTrue(log.calls.indexOf("begin") < log.calls.indexOf("registerParentDevice"))
    }

    @Test
    fun `a second phone that is revoked instead of approved is told so and goes back to the start`() = runTest {
        auth.claims[0] = parentClaims()
        val viewModel = viewModel()
        viewModel.signIn()
        sessions.session.value = sessions.session.value?.copy(deviceStatus = RegistrationStatus.REVOKED)
        runCurrent()
        assertEquals(OnboardingUiState(failure = OnboardingFailure.DEVICE_REJECTED), viewModel.state.value)
        assertNull(sessions.session.value)
        assertTrue("signOut" in log.calls)
    }

    @Test
    fun `a phone restarted while waiting resumes waiting`() {
        sessions.session.value =
            Session(PARENT_UID, HOUSEHOLD, Role.PARENT, null, NEW_DEVICE, RegistrationStatus.PENDING, clock.now())
        val viewModel = viewModel()
        assertEquals(OnboardingStep.WaitingForApproval("ab12 cd34 ef56 7890"), viewModel.state.value.step)
        viewModel.onEvent(OnboardingEvent.Back)
        assertNull(sessions.session.value, "cancelling the wait drops the PENDING session")
    }

    @Test
    fun `a phone whose key is still ACTIVE for this parent restores its session without registering`() {
        auth.claims[0] = parentClaims()
        household.activeDevice = NEW_DEVICE
        val viewModel = viewModel()
        viewModel.signIn()
        assertFalse("begin" in log.calls)
        assertEquals(RegistrationStatus.ACTIVE, sessions.session.value?.deviceStatus)
        assertEquals(OnboardingStep.ParentLock, viewModel.state.value.step)
    }

    @Test
    fun `a kid's account cannot sign in as a parent`() {
        auth.claims[0] = AccountClaims(PARENT_UID, HOUSEHOLD, Role.KID, LEO)
        val viewModel = viewModel()
        viewModel.signIn()
        assertEquals(OnboardingFailure.NOT_ALLOWED, viewModel.state.value.failure)
        assertTrue("signOut" in log.calls)
        assertNull(sessions.session.value)
    }

    @Test
    fun `the code field keeps 8 upper-case alphabet characters and never shows them in toString`() {
        val viewModel = viewModel()
        viewModel.onEvent(OnboardingEvent.ChooseKidDevice)
        viewModel.onEvent(OnboardingEvent.CodeChanged("abcd-0o1i-efghjk"))
        val step = viewModel.state.value.step as OnboardingStep.KidCode
        assertEquals("ABCDEFGH", step.code, "separators and the look-alikes 0, O, 1 and I are dropped")
        assertTrue(step.complete)
        assertFalse("ABCD" in step.toString())
        assertFalse("ABCD" in viewModel.state.value.toString())
        assertFalse("ABCD" in LinkCodeInput.parse("ABCDEFGH").toString())
    }

    @Test
    fun `an incomplete code is refused locally`() {
        val viewModel = viewModel()
        viewModel.onEvent(OnboardingEvent.ChooseKidDevice)
        viewModel.onEvent(OnboardingEvent.CodeChanged("ABC"))
        viewModel.onEvent(OnboardingEvent.SubmitCode)
        assertEquals(OnboardingFailure.CODE_NOT_ACCEPTED, viewModel.state.value.failure)
        assertEquals(emptyList(), log.calls)
        viewModel.onEvent(OnboardingEvent.CodeChanged("ABCD"))
        assertNull(viewModel.state.value.failure, "typing clears the error")
    }

    @Test
    fun `a kid device signs in anonymously, redeems the code with a fresh key, saves the KID session and syncs`() {
        auth.claims[0] = AccountClaims(KID_UID, HOUSEHOLD, Role.KID, LEO)
        val viewModel = viewModel()
        viewModel.onEvent(OnboardingEvent.ChooseKidDevice)
        viewModel.onEvent(OnboardingEvent.CodeChanged("ABCDEFGH"))
        viewModel.onEvent(OnboardingEvent.SubmitCode)
        assertEquals(
            listOf("signInAnonymously", "begin", "redeemLinkCode", "refreshClaims", "saveSession:KID:ACTIVE", "sync"),
            log.calls,
        )
        assertEquals(LinkCodeInput.parse("ABCDEFGH"), backend.redeemedCode)
        assertEquals(
            Session(KID_UID, HOUSEHOLD, Role.KID, LEO, NEW_DEVICE, RegistrationStatus.ACTIVE, clock.now()),
            sessions.session.value,
        )
        assertTrue(viewModel.state.value.finished)
    }

    @Test
    fun `a kid device with trial data confirms first, then purges after redeeming`() {
        household.trial = true
        auth.claims[0] = AccountClaims(KID_UID, HOUSEHOLD, Role.KID, LEO)
        val viewModel = viewModel()
        viewModel.onEvent(OnboardingEvent.ChooseKidDevice)
        assertEquals(OnboardingStep.ConfirmPurge(forKidDevice = true), viewModel.state.value.step)
        viewModel.onEvent(OnboardingEvent.ConfirmPurge)
        viewModel.onEvent(OnboardingEvent.CodeChanged("ABCDEFGH"))
        viewModel.onEvent(OnboardingEvent.SubmitCode)
        assertTrue(log.calls.indexOf("redeemLinkCode") < log.calls.indexOf("purge"))
        assertTrue(log.calls.indexOf("purge") < log.calls.indexOf("saveSession:KID:ACTIVE"))
    }

    @Test
    fun `a refused code keeps the kid on the code step with the failure and saves nothing`() {
        backend.redeemResult = Result.failure(OnboardingException(OnboardingFailure.CODE_NOT_ACCEPTED))
        val viewModel = viewModel()
        viewModel.onEvent(OnboardingEvent.ChooseKidDevice)
        viewModel.onEvent(OnboardingEvent.CodeChanged("ABCDEFGH"))
        viewModel.onEvent(OnboardingEvent.SubmitCode)
        val state = viewModel.state.value
        assertEquals(OnboardingFailure.CODE_NOT_ACCEPTED, state.failure)
        assertTrue(state.step is OnboardingStep.KidCode)
        assertNull(sessions.session.value)
        assertFalse(state.busy)
    }

    @Test
    fun `an unexpected failure is UNKNOWN`() {
        backend.createResult = Result.failure(IllegalStateException("boom"))
        auth.claims[0] = parentClaims(householdId = null)
        val viewModel = viewModel()
        viewModel.signIn()
        viewModel.onEvent(OnboardingEvent.CreateHousehold)
        assertEquals(OnboardingFailure.UNKNOWN, viewModel.state.value.failure)
        assertNull(sessions.session.value)
    }
}
