package dev.kpetriv.sprout.feature.onboarding

import dev.kpetriv.sprout.core.crypto.InMemoryDeviceKeyStore
import dev.kpetriv.sprout.core.database.Session
import dev.kpetriv.sprout.core.database.entity.RegistrationStatus
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.time.FakeClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
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
    private val sessions = FakeSessionStore(log)
    private val household = FakeOnboardingHousehold(log, sessions)
    private val keys = InMemoryDeviceKeyStore(clock)

    private fun viewModel() = OnboardingViewModel(auth, backend, household, keys, sessions, clock)

    private fun currentDevice(): DeviceId = keys.publicKey().deviceId()

    private fun claimsSequence(vararg claims: AccountClaims) {
        auth.claims.clear()
        auth.claims += claims
    }

    private fun OnboardingViewModel.signIn(email: String = "parent@example.test", password: String = "secret1") {
        onEvent(OnboardingEvent.ChooseParent)
        onEvent(OnboardingEvent.EmailChanged(email))
        onEvent(OnboardingEvent.PasswordChanged(password))
        onEvent(OnboardingEvent.SignInWithEmail)
    }

    private fun OnboardingViewModel.redeem(code: String = "ABCDEFGH") {
        onEvent(OnboardingEvent.ChooseKidDevice)
        onEvent(OnboardingEvent.CodeChanged(code))
        onEvent(OnboardingEvent.SubmitCode)
    }

    // ---- sign-in ----

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
    fun `a kid's account cannot sign in as a parent`() {
        auth.claims[0] = AccountClaims(PARENT_UID, HOUSEHOLD, Role.KID, LEO)
        val viewModel = viewModel()
        viewModel.signIn()
        assertEquals(OnboardingFailure.NOT_ALLOWED, viewModel.state.value.failure)
        assertTrue("signOut" in log.calls)
        assertNull(sessions.session.value)
    }

    // ---- a new household ----

    @Test
    fun `a new parent creates the household with a pending key promoted only once the server accepted it`() {
        claimsSequence(parentClaims(householdId = null), parentClaims(householdId = null), parentClaims())
        val provisional = currentDevice()
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
                "refreshClaims",
                "begin",
                "createHousehold",
                "setupStarted",
                "saveSession:PARENT:ACTIVE",
                "ensurePolicy",
                "refreshClaims",
            ),
            log.calls,
        )
        assertEquals(1, keys.registrations, "every registration uses a fresh key")
        val registered = backend.registeredKeys.single().publicKey
        assertEquals(registered, keys.publicKey(), "the accepted key is this device's signing key now")
        assertNotEquals(provisional, currentDevice())
        assertNull(keys.pendingSigningKeyBlocking())
        assertEquals(Currency.Fiat("GBP"), backend.createdWith)
        assertEquals(Currency.Fiat("GBP"), household.policyCurrency)
        assertEquals(
            Session(PARENT_UID, HOUSEHOLD, Role.PARENT, null, currentDevice(), RegistrationStatus.ACTIVE, clock.now()),
            sessions.session.value,
        )
        assertEquals(OnboardingStep.ParentLock, viewModel.state.value.step)

        viewModel.onEvent(OnboardingEvent.ParentLockDone)
        assertTrue(viewModel.state.value.finished)
        assertEquals("setupFinished", log.calls.last())
        assertNull(household.setupPending)
    }

    @Test
    fun `a device id that is not the key's own fingerprint is refused and the previous key stays`() {
        claimsSequence(parentClaims(householdId = null))
        backend.deviceIdOverride = NEW_DEVICE
        val provisional = currentDevice()
        val viewModel = viewModel()
        viewModel.signIn()
        viewModel.onEvent(OnboardingEvent.CreateHousehold)

        assertEquals(OnboardingFailure.UNKNOWN, viewModel.state.value.failure)
        assertEquals(provisional, currentDevice())
        assertNull(keys.pendingSigningKeyBlocking(), "the refused key is dropped")
        assertNull(sessions.session.value)
    }

    @Test
    fun `a failed claims refresh after creating keeps the session and Create retries only the refresh`() {
        val noHousehold = parentClaims(householdId = null)
        claimsSequence(noHousehold, noHousehold, noHousehold, parentClaims())
        val viewModel = viewModel()
        viewModel.signIn()
        viewModel.onEvent(OnboardingEvent.CreateHousehold)
        assertEquals(OnboardingFailure.UNKNOWN, viewModel.state.value.failure, "the claims do not name it yet")
        assertEquals(RegistrationStatus.ACTIVE, sessions.session.value?.deviceStatus, "saved right after the server")
        assertTrue(viewModel.state.value.step is OnboardingStep.CreateHousehold)
        log.calls.clear()

        viewModel.onEvent(OnboardingEvent.CreateHousehold)

        assertEquals(listOf("refreshClaims"), log.calls)
        assertEquals(1, keys.registrations, "no second key")
        assertEquals(OnboardingStep.ParentLock, viewModel.state.value.step)
    }

    @Test
    fun `a create whose answer was lost is found again through the pending key, not registered twice`() {
        claimsSequence(parentClaims(householdId = null))
        backend.createFailure = OnboardingException(OnboardingFailure.NETWORK)
        val viewModel = viewModel()
        viewModel.signIn()
        viewModel.onEvent(OnboardingEvent.CreateHousehold)
        assertEquals(OnboardingFailure.NETWORK, viewModel.state.value.failure)
        assertNotNull(keys.pendingSigningKeyBlocking(), "kept: the server may have accepted it")

        // The server did create it: the claims now name the household and the registry has the pending key ACTIVE.
        claimsSequence(parentClaims())
        backend.statuses[RegistrationKey.PENDING] = RegistryStatus.ACTIVE
        val pending = keys.pendingSigningKeyBlocking()!!.publicKey
        household.onSync = { household.activeDevice = pending.deviceId() }
        log.calls.clear()
        viewModel.onEvent(OnboardingEvent.CreateHousehold)

        assertEquals(
            listOf("refreshClaims", "status:PENDING", "setupStarted", "saveSession:PARENT:ACTIVE", "sync"),
            log.calls,
        )
        assertEquals(1, keys.registrations)
        assertEquals(pending, keys.publicKey())
        assertEquals(OnboardingStep.ParentLock, viewModel.state.value.step)
    }

    @Test
    fun `trial data is confirmed before anything is created and purged before the session is saved`() {
        household.trial = true
        claimsSequence(parentClaims(householdId = null), parentClaims(householdId = null), parentClaims())
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
    fun `back is ignored while a call runs`() = runTest {
        claimsSequence(parentClaims(householdId = null), parentClaims(householdId = null), parentClaims())
        val gate = CompletableDeferred<Unit>()
        backend.beforeCreate = { gate.await() }
        val viewModel = viewModel()
        viewModel.signIn()
        viewModel.onEvent(OnboardingEvent.CreateHousehold)
        assertTrue(viewModel.state.value.busy)

        viewModel.onEvent(OnboardingEvent.Back)
        assertTrue(viewModel.state.value.step is OnboardingStep.CreateHousehold, "still creating")
        assertFalse("signOut" in log.calls)

        gate.complete(Unit)
        runCurrent()
        assertEquals(OnboardingStep.ParentLock, viewModel.state.value.step)
    }

    @Test
    fun `a process death on the PIN step comes back to it`() {
        sessions.session.value =
            Session(PARENT_UID, HOUSEHOLD, Role.PARENT, null, NEW_DEVICE, RegistrationStatus.ACTIVE, clock.now())
        household.setupPending = HOUSEHOLD

        val viewModel = viewModel()

        assertEquals(OnboardingStep.ParentLock, viewModel.state.value.step)
        assertEquals(
            listOf("refreshClaims", "sync"),
            log.calls,
            "the token is refreshed for the household's claims, then the household is pulled before the PIN step",
        )
        viewModel.onEvent(OnboardingEvent.ParentLockDone)
        assertTrue(viewModel.state.value.finished)
    }

    @Test
    fun `a finished setup does not resume onboarding`() {
        sessions.session.value =
            Session(PARENT_UID, HOUSEHOLD, Role.PARENT, null, NEW_DEVICE, RegistrationStatus.ACTIVE, clock.now())
        assertEquals(OnboardingStep.Welcome, viewModel().state.value.step)
        assertEquals(emptyList(), log.calls)
    }

    // ---- a parent with a household ----

    @Test
    fun `a second parent phone registers PENDING with its own fingerprint and waits until it is approved`() = runTest {
        auth.claims[0] = parentClaims()
        household.onSync = {
            if (household.syncs == 3) {
                sessions.session.value = sessions.session.value?.copy(deviceStatus = RegistrationStatus.ACTIVE)
            }
        }
        val viewModel = viewModel()
        viewModel.signIn()
        assertEquals(
            listOf("status:PENDING", "status:CURRENT", "begin", "registerParentDevice", "saveSession:PARENT:PENDING"),
            log.calls.drop(2).take(5),
        )
        assertEquals(
            OnboardingStep.WaitingForApproval(displayFingerprint(currentDevice())),
            viewModel.state.value.step,
            "computed from the key, not the server's answer",
        )
        assertEquals(RegistrationStatus.PENDING, sessions.session.value?.deviceStatus)
        assertEquals(currentDevice(), sessions.session.value?.deviceId)
        assertEquals(1, household.syncs)
        advanceTimeBy(OnboardingViewModel.POLL_INTERVAL_MS)
        runCurrent()
        assertEquals(2, household.syncs)
        advanceTimeBy(OnboardingViewModel.POLL_INTERVAL_MS)
        runCurrent()
        // The approval, then one pull of what a PENDING phone could not read, before the PIN step (D-REL-11).
        assertEquals(4, household.syncs)
        assertEquals(OnboardingStep.ParentLock, viewModel.state.value.step)
        assertEquals(HOUSEHOLD, household.setupPending)
        advanceTimeBy(OnboardingViewModel.POLL_INTERVAL_MS * 3)
        runCurrent()
        assertEquals(4, household.syncs, "polling stops once approved")
    }

    @Test
    fun `a second phone revoked instead of approved is told so, and what its sync pulled is erased`() = runTest {
        auth.claims[0] = parentClaims()
        val viewModel = viewModel()
        viewModel.signIn()
        sessions.session.value = sessions.session.value?.copy(deviceStatus = RegistrationStatus.REVOKED)
        runCurrent()
        assertEquals(OnboardingUiState(failure = OnboardingFailure.DEVICE_REJECTED), viewModel.state.value)
        assertNull(sessions.session.value)
        assertTrue("erase" in log.calls, "the poll pulled: the removal owner erases and signs out")
        assertFalse("clearSession" in log.calls)
    }

    @Test
    fun `a rejection before anything was pulled drops the session and signs out`() = runTest {
        household.syncSucceeds = false
        auth.claims[0] = parentClaims()
        val viewModel = viewModel()
        viewModel.signIn()
        sessions.session.value = sessions.session.value?.copy(deviceStatus = RegistrationStatus.REVOKED)
        runCurrent()
        assertEquals(OnboardingUiState(failure = OnboardingFailure.DEVICE_REJECTED), viewModel.state.value)
        assertNull(sessions.session.value)
        assertFalse("erase" in log.calls)
        assertTrue("signOut" in log.calls)
    }

    @Test
    fun `back after a restart erases rows an earlier run pulled, though this run pulled nothing`() {
        household.syncSucceeds = false
        household.householdData = true
        sessions.session.value =
            Session(PARENT_UID, HOUSEHOLD, Role.PARENT, null, NEW_DEVICE, RegistrationStatus.PENDING, clock.now())
        val viewModel = viewModel()
        viewModel.onEvent(OnboardingEvent.Back)
        assertTrue("erase" in log.calls)
        assertFalse("clearSession" in log.calls)
    }

    @Test
    fun `a phone restarted while waiting resumes waiting, and back before anything was pulled drops the session`() {
        household.syncSucceeds = false
        sessions.session.value =
            Session(PARENT_UID, HOUSEHOLD, Role.PARENT, null, NEW_DEVICE, RegistrationStatus.PENDING, clock.now())
        val viewModel = viewModel()
        assertEquals(OnboardingStep.WaitingForApproval("ab12 cd34 ef56 7890"), viewModel.state.value.step)
        viewModel.onEvent(OnboardingEvent.Back)
        assertNull(sessions.session.value, "cancelling the wait drops the PENDING session")
        assertFalse("erase" in log.calls)
        assertTrue("signOut" in log.calls)
    }

    @Test
    fun `back while waiting after the poll pulled household data erases this phone's copy`() {
        sessions.session.value =
            Session(PARENT_UID, HOUSEHOLD, Role.PARENT, null, NEW_DEVICE, RegistrationStatus.PENDING, clock.now())
        val viewModel = viewModel()
        assertEquals(1, household.syncs)
        viewModel.onEvent(OnboardingEvent.Back)
        assertTrue("erase" in log.calls)
        assertFalse("clearSession" in log.calls)
        assertEquals(OnboardingStep.Welcome, viewModel.state.value.step)
    }

    @Test
    fun `a phone the local registry has ACTIVE for this parent restores its session without registering`() {
        auth.claims[0] = parentClaims()
        household.activeDevice = NEW_DEVICE
        val viewModel = viewModel()
        viewModel.signIn()
        assertFalse("begin" in log.calls)
        assertFalse(log.calls.any { it.startsWith("status:") })
        assertTrue(log.calls.indexOf("setupStarted") < log.calls.indexOf("saveSession:PARENT:ACTIVE"))
        assertEquals(NEW_DEVICE, sessions.session.value?.deviceId)
        assertEquals(OnboardingStep.ParentLock, viewModel.state.value.step)
    }

    @Test
    fun `a phone restored from the local registry mirror pulls the household before the PIN step`() {
        auth.claims[0] = parentClaims()
        household.activeDevice = NEW_DEVICE
        lateinit var viewModel: OnboardingViewModel
        val stepsAtSync = mutableListOf<OnboardingStep>()
        household.onSync = { stepsAtSync += viewModel.state.value.step }
        viewModel = viewModel()
        viewModel.signIn()

        // D-REL-11: the enrolment on the PIN step must find the pulled policy, on this path too.
        assertEquals(listOf<OnboardingStep>(OnboardingStep.FirstSync), stepsAtSync)
        assertTrue(log.calls.indexOf("saveSession:PARENT:ACTIVE") < log.calls.indexOf("sync"))
        assertFalse("ensurePolicy" in log.calls, "this phone did not create the household")
        assertEquals(OnboardingStep.ParentLock, viewModel.state.value.step)
    }

    @Test
    fun `after a sign-out wipe the server registry restores the same ACTIVE key without regenerating it`() {
        auth.claims[0] = parentClaims()
        val device = currentDevice()
        backend.statuses[RegistrationKey.CURRENT] = RegistryStatus.ACTIVE
        household.onSync = { household.activeDevice = device }
        val viewModel = viewModel()
        viewModel.signIn()

        assertEquals(0, keys.registrations)
        assertFalse("begin" in log.calls)
        assertEquals(device, sessions.session.value?.deviceId)
        assertEquals(RegistrationStatus.ACTIVE, sessions.session.value?.deviceStatus)
        assertEquals(OnboardingStep.ParentLock, viewModel.state.value.step)
    }

    @Test
    fun `a restored phone pulls the household before the PIN and signing step`() {
        auth.claims[0] = parentClaims()
        val device = currentDevice()
        backend.statuses[RegistrationKey.CURRENT] = RegistryStatus.ACTIVE
        lateinit var viewModel: OnboardingViewModel
        val stepsAtSync = mutableListOf<OnboardingStep>()
        household.onSync = {
            household.activeDevice = device
            stepsAtSync += viewModel.state.value.step
        }
        viewModel = viewModel()
        viewModel.signIn()

        // Enrolment on the PIN step registers the key in the household policy: it must be the pulled one (D-REL-11).
        assertEquals(listOf<OnboardingStep>(OnboardingStep.FirstSync), stepsAtSync)
        assertEquals(OnboardingStep.ParentLock, viewModel.state.value.step)
    }

    @Test
    fun `a restored phone that is offline still reaches the PIN step`() {
        auth.claims[0] = parentClaims()
        household.syncSucceeds = false
        backend.statuses[RegistrationKey.CURRENT] = RegistryStatus.ACTIVE
        val viewModel = viewModel()
        viewModel.signIn()

        assertEquals(1, household.syncs)
        assertFalse("erase" in log.calls)
        assertEquals(RegistrationStatus.ACTIVE, sessions.session.value?.deviceStatus)
        assertEquals(OnboardingStep.ParentLock, viewModel.state.value.step)
        assertNull(viewModel.state.value.failure)
    }

    @Test
    fun `a restored key that belongs to another parent account is erased again`() {
        auth.claims[0] = parentClaims()
        backend.statuses[RegistrationKey.CURRENT] = RegistryStatus.ACTIVE
        val viewModel = viewModel()
        viewModel.signIn()
        assertTrue("erase" in log.calls)
        assertEquals(OnboardingFailure.NOT_ALLOWED, viewModel.state.value.failure)
        assertEquals(OnboardingStep.Welcome, viewModel.state.value.step, "never left on the pull step")
        assertNull(sessions.session.value)
    }

    @Test
    fun `a key still PENDING in the registry goes back to waiting without a new registration`() {
        auth.claims[0] = parentClaims()
        household.syncSucceeds = false
        backend.statuses[RegistrationKey.CURRENT] = RegistryStatus.PENDING
        val viewModel = viewModel()
        viewModel.signIn()
        assertFalse("begin" in log.calls)
        assertEquals(OnboardingStep.WaitingForApproval(displayFingerprint(currentDevice())), viewModel.state.value.step)
    }

    @Test
    fun `a registry status that cannot be read stops before anything is registered`() {
        auth.claims[0] = parentClaims()
        backend.statusFailure = OnboardingException(OnboardingFailure.NETWORK)
        val provisional = currentDevice()
        val viewModel = viewModel()
        viewModel.signIn()
        assertEquals(OnboardingFailure.NETWORK, viewModel.state.value.failure)
        assertFalse("begin" in log.calls)
        assertEquals(provisional, currentDevice())
        assertNull(sessions.session.value)
    }

    // ---- kid device ----

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
    fun `a kid device redeems the code with a pending key, saves the KID session at once and syncs`() {
        claimsSequence(NEW_KID_CLAIMS, AccountClaims(KID_UID, HOUSEHOLD, Role.KID, LEO))
        val viewModel = viewModel()
        viewModel.redeem()
        assertEquals(
            listOf(
                "signInAnonymously",
                "refreshClaims",
                "begin",
                "redeemLinkCode",
                "saveSession:KID:ACTIVE",
                "refreshClaims",
                "sync",
            ),
            log.calls,
        )
        assertEquals(LinkCodeInput.parse("ABCDEFGH"), backend.redeemedCode)
        assertEquals(
            Session(KID_UID, HOUSEHOLD, Role.KID, LEO, currentDevice(), RegistrationStatus.ACTIVE, clock.now()),
            sessions.session.value,
        )
        assertEquals(backend.registeredKeys.single().publicKey, keys.publicKey())
        assertTrue(viewModel.state.value.finished)
    }

    @Test
    fun `a kid device whose earlier redeem the server completed is restored through its pending key`() {
        // The first attempt's answer was lost: the pending key exists and the anonymous user already has KID claims.
        claimsSequence(NEW_KID_CLAIMS)
        backend.redeemFailure = OnboardingException(OnboardingFailure.NETWORK)
        val viewModel = viewModel()
        viewModel.redeem()
        assertEquals(OnboardingFailure.NETWORK, viewModel.state.value.failure)
        val pending = keys.pendingSigningKeyBlocking()!!.publicKey

        claimsSequence(AccountClaims(KID_UID, HOUSEHOLD, Role.KID, LEO))
        backend.statuses[RegistrationKey.PENDING] = RegistryStatus.ACTIVE
        log.calls.clear()
        viewModel.onEvent(OnboardingEvent.SubmitCode)

        assertEquals(
            listOf("signInAnonymously", "refreshClaims", "status:PENDING", "saveSession:KID:ACTIVE", "sync"),
            log.calls,
        )
        assertEquals(pending, keys.publicKey())
        assertEquals(1, keys.registrations)
        assertTrue(viewModel.state.value.finished)
    }

    @Test
    fun `a spent anonymous user that cannot be restored starts again with a fresh one`() {
        claimsSequence(AccountClaims(KID_UID, HouseholdId("old-home"), Role.KID, LEO), NEW_KID_CLAIMS)
        val viewModel = viewModel()
        viewModel.redeem()
        assertEquals(
            listOf(
                "signInAnonymously",
                "refreshClaims",
                "status:PENDING",
                "status:CURRENT",
                "signOut",
                "signInAnonymously",
            ),
            log.calls.take(6),
        )
        assertTrue(viewModel.state.value.finished)
    }

    @Test
    fun `a kid device with trial data confirms first, then purges after redeeming`() {
        household.trial = true
        claimsSequence(NEW_KID_CLAIMS)
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
        claimsSequence(NEW_KID_CLAIMS)
        backend.redeemFailure = OnboardingException(OnboardingFailure.CODE_NOT_ACCEPTED)
        val viewModel = viewModel()
        viewModel.redeem()
        val state = viewModel.state.value
        assertEquals(OnboardingFailure.CODE_NOT_ACCEPTED, state.failure)
        assertTrue(state.step is OnboardingStep.KidCode)
        assertNull(sessions.session.value)
        assertFalse(state.busy)
    }

    @Test
    fun `an unexpected failure is UNKNOWN`() {
        backend.createFailure = IllegalStateException("boom")
        auth.claims[0] = parentClaims(householdId = null)
        val viewModel = viewModel()
        viewModel.signIn()
        viewModel.onEvent(OnboardingEvent.CreateHousehold)
        assertEquals(OnboardingFailure.UNKNOWN, viewModel.state.value.failure)
        assertNull(sessions.session.value)
    }

    private fun InMemoryDeviceKeyStore.pendingSigningKeyBlocking() =
        kotlinx.coroutines.runBlocking { pendingSigningKey() }
}
