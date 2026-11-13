package dev.kpetriv.sprout.feature.onboarding

import android.content.ContextWrapper
import androidx.lifecycle.viewModelScope
import dev.kpetriv.sprout.core.crypto.InMemoryDeviceKeyStore
import dev.kpetriv.sprout.core.database.Session
import dev.kpetriv.sprout.core.database.entity.RegistrationStatus
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.time.FakeClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** ADR-0013 §3: restoring the account from the waiting screen, with the PIN or after the wait. */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("LargeClass") // One flow end to end; every case shares the same fakes.
class OnboardingRecoveryTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val log = CallLog()
    private val clock = FakeClock()
    private val auth = FakeOnboardingAuth(log, usesEmailPassword = false)
    private val backend = FakeRegistrationBackend(log)
    private val sessions = FakeSessionStore(log)
    private val household = FakeOnboardingHousehold(log, sessions)
    private val keys = InMemoryDeviceKeyStore(clock)
    private val recovery = FakeAccountRecovery(log)
    private val keeper = FakeRecoveredPinKeeper(log)
    private val created = mutableListOf<OnboardingViewModel>()

    init {
        household.ownerOf = { auth.lastClaims?.let { RegisteredOwner(it.uid, it.role ?: Role.PARENT, it.kidId) } }
        auth.claims[0] = parentClaims()
    }

    /** A waiting phone polls for ever: every view model's scope is cancelled when the test ends. */
    private fun test(block: suspend TestScope.() -> Unit) = runTest {
        try {
            block()
        } finally {
            created.forEach { it.viewModelScope.cancel() }
        }
    }

    private fun viewModel(onboardingAuth: OnboardingAuth = auth) =
        OnboardingViewModel(onboardingAuth, backend, household, keys, sessions, clock, recovery, keeper)
            .also { created += it }

    /** A phone whose key is gone: Google sign-in, registered PENDING, waiting. */
    private fun waitingPhone(): OnboardingViewModel = viewModel().apply {
        onEvent(OnboardingEvent.ChooseParent)
        onEvent(OnboardingEvent.SignInWithGoogle(ContextWrapper(null)))
    }

    private val OnboardingViewModel.waiting: OnboardingStep.WaitingForApproval
        get() = state.value.step as OnboardingStep.WaitingForApproval

    private val OnboardingViewModel.ready: RecoveryUi.Ready get() = assertIs<RecoveryUi.Ready>(waiting.recovery)

    private fun OnboardingViewModel.type(pin: String) = pin.forEach { onEvent(OnboardingEvent.RecoveryPinDigit(it)) }

    private fun OnboardingViewModel.restoreWithPin(pin: String) {
        onEvent(OnboardingEvent.ChooseRecovery(RecoveryChoice.PIN))
        onEvent(OnboardingEvent.ConfirmRecovery)
        type(pin)
    }

    @Test
    fun `the waiting screen loads the recovery options and keeps polling for approval`() = test {
        val viewModel = waitingPhone()
        assertEquals(1, recovery.statusCalls.size)
        assertEquals(
            RecoveryUi.Ready(pinAvailable = true, attemptsLeft = 10, waitEndsAt = null, waitOver = false),
            viewModel.waiting.recovery,
        )
        assertFalse(viewModel.ready.showWait, "the wait waits behind \"Forgot your PIN?\"")
        viewModel.onEvent(OnboardingEvent.ShowRecoveryWait)
        assertTrue(viewModel.ready.showWait)
        assertTrue(viewModel.ready.waitExpanded)
        val syncs = household.syncs
        advanceTimeBy(OnboardingViewModel.POLL_INTERVAL_MS)
        runCurrent()
        assertEquals(syncs + 1, household.syncs)
    }

    @Test
    fun `a PIN restore keeps the PIN, signs in again, then pulls and goes on like an approved phone`() = test {
        recovery.answers += restored(attemptsLeft = 8)
        val viewModel = waitingPhone()
        viewModel.onEvent(OnboardingEvent.ChooseRecovery(RecoveryChoice.PIN))
        assertEquals(RecoveryChoice.PIN, viewModel.ready.confirming)
        viewModel.onEvent(OnboardingEvent.ConfirmRecovery)
        assertEquals(RecoveryPinEntry(), viewModel.ready.pinEntry)
        viewModel.type("48291")
        assertEquals(5, viewModel.ready.pinEntry?.enteredDigits)
        assertFalse("48291" in viewModel.state.value.toString())
        viewModel.onEvent(OnboardingEvent.RecoveryPinBackspace)
        viewModel.type("35")
        runCurrent()

        assertEquals(listOf<String?>("482935"), recovery.verifiers)
        assertTrue(recovery.pins.single().all { it == '\u0000' }, "the PIN copy is zeroed after the call")
        assertEquals(listOf("482935"), keeper.kept)
        assertEquals(listOf(8), keeper.attempts)
        assertEquals(RegistrationStatus.ACTIVE, sessions.session.value?.deviceStatus)
        assertEquals(OnboardingStep.ParentLock, viewModel.state.value.step)
        assertEquals(HOUSEHOLD, household.setupPending)
        val calls = log.calls
        val keep = calls.indexOf("keepPin")
        val active = calls.indexOf("saveSession:PARENT:ACTIVE")
        val signIn = calls.indexOf("signInAgain")
        assertTrue(keep in 0 until active && active < signIn, "PIN and session first, then the sign-in: $calls")
        assertTrue(calls.lastIndexOf("sync") > signIn, "the first pull runs with the new session")
        val syncs = household.syncs
        advanceTimeBy(OnboardingViewModel.POLL_INTERVAL_MS * 3)
        runCurrent()
        assertEquals(syncs, household.syncs, "approval polling stopped")
    }

    @Test
    fun `a PIN restore stores the PIN and the session before the second sign-in, so a restart goes on`() = test {
        recovery.signInGate = CompletableDeferred()
        val viewModel = waitingPhone()
        viewModel.restoreWithPin("482935")
        runCurrent()
        // The app's "Sign in again" screen is up; the parent cancels it and closes the app.
        assertEquals(OnboardingStep.FirstSync, viewModel.state.value.step)
        assertEquals(listOf("482935"), keeper.kept, "the PIN is this phone's already")
        assertEquals(RegistrationStatus.ACTIVE, sessions.session.value?.deviceStatus)
        assertEquals(HOUSEHOLD, household.setupPending)
        viewModel.viewModelScope.cancel()

        val restarted = viewModel()
        runCurrent()
        assertEquals(OnboardingStep.ParentLock, restarted.state.value.step)
        assertEquals(1, recovery.verifiers.size, "nothing is restored twice")
        assertEquals(1, keeper.kept.size)
    }

    @Test
    fun `back closes the confirmation and the PIN pad before it leaves the waiting screen`() = test {
        val viewModel = waitingPhone()
        viewModel.onEvent(OnboardingEvent.ChooseRecovery(RecoveryChoice.PIN))
        viewModel.onEvent(OnboardingEvent.Back)
        assertNull(viewModel.ready.confirming)
        viewModel.onEvent(OnboardingEvent.ChooseRecovery(RecoveryChoice.PIN))
        viewModel.onEvent(OnboardingEvent.ConfirmRecovery)
        viewModel.type("12")
        viewModel.onEvent(OnboardingEvent.Back)
        assertNull(viewModel.ready.pinEntry)
        assertIs<OnboardingStep.WaitingForApproval>(viewModel.state.value.step)
        assertEquals(RegistrationStatus.PENDING, sessions.session.value?.deviceStatus)
        // Reopened, the pad starts empty.
        viewModel.onEvent(OnboardingEvent.ChooseRecovery(RecoveryChoice.PIN))
        viewModel.onEvent(OnboardingEvent.ConfirmRecovery)
        assertEquals(RecoveryPinEntry(), viewModel.ready.pinEntry)
    }

    @Test
    fun `a wrong PIN shakes the pad with the tries left, and the last one closes the PIN path`() = test {
        recovery.answers += refused(RecoveryProblem.WrongPin(attemptsLeft = 9))
        recovery.answers += refused(RecoveryProblem.WrongPin(attemptsLeft = 0))
        val viewModel = waitingPhone()
        viewModel.restoreWithPin("111222")
        runCurrent()
        val wrong = viewModel.ready
        assertEquals(RecoveryPinEntry(enteredDigits = 0, rejections = 1), wrong.pinEntry)
        assertEquals(9, wrong.wrongPinAttemptsLeft)
        assertEquals(9, wrong.attemptsLeft)
        assertFalse(wrong.restoring)

        viewModel.type("333444")
        runCurrent()
        val closed = viewModel.ready
        assertFalse(closed.pinAvailable)
        assertNull(closed.pinEntry)
        assertTrue(closed.showWait, "the wait replaces the PIN path")
        assertEquals(0, closed.wrongPinAttemptsLeft)
        viewModel.onEvent(OnboardingEvent.ChooseRecovery(RecoveryChoice.PIN))
        assertNull(viewModel.ready.confirming, "the PIN path is closed")
        assertEquals(emptyList(), keeper.kept)
        assertEquals(RegistrationStatus.PENDING, sessions.session.value?.deviceStatus)
    }

    @Test
    fun `no check value, or one this phone cannot compute, shows only the wait`() = test {
        recovery.options = Result.success(RecoveryOptions(pin = null, attemptsLeft = 0, waitEndsAt = null))
        val none = waitingPhone().ready
        assertFalse(none.pinAvailable)
        assertTrue(none.showWait)
        assertFalse(none.pinUnsupported)

        recovery.options = Result.success(RecoveryOptions(pinParameters(supported = false), 10, null))
        val unsupported = waitingPhone().ready
        assertFalse(unsupported.pinAvailable)
        assertTrue(unsupported.pinUnsupported)
        assertTrue(unsupported.showWait)
    }

    @Test
    fun `starting the wait is answered with its end date, which is not an error`() = test {
        recovery.answers += refused(RecoveryProblem.Wait(WAIT_ENDS))
        val viewModel = waitingPhone()
        viewModel.onEvent(OnboardingEvent.ChooseRecovery(RecoveryChoice.WAIT))
        assertNull(viewModel.ready.confirming, "nothing to restore before the wait")
        viewModel.onEvent(OnboardingEvent.ShowRecoveryWait)
        viewModel.onEvent(OnboardingEvent.StartRecoveryWait)
        runCurrent()
        assertEquals(listOf<String?>(null), recovery.verifiers)
        val started = viewModel.ready
        assertEquals(WAIT_ENDS, started.waitEndsAt)
        assertFalse(started.waitOver)
        assertNull(started.failure)
        assertFalse(started.restoring)
        // Once started, the button is gone; nothing more is sent before the date.
        viewModel.onEvent(OnboardingEvent.StartRecoveryWait)
        viewModel.onEvent(OnboardingEvent.ChooseRecovery(RecoveryChoice.WAIT))
        runCurrent()
        assertEquals(1, recovery.verifiers.size)
        assertIs<OnboardingStep.WaitingForApproval>(viewModel.state.value.step)
    }

    @Test
    fun `after the wait, the restore needs no PIN, signs in again, and the lock step chooses a new PIN`() = test {
        clock.advanceBy(8.days)
        recovery.options = Result.success(RecoveryOptions(pin = null, attemptsLeft = 0, waitEndsAt = WAIT_ENDS))
        recovery.answers += restored(attemptsLeft = 0)
        val viewModel = waitingPhone()
        assertTrue(viewModel.ready.waitOver)
        viewModel.onEvent(OnboardingEvent.ChooseRecovery(RecoveryChoice.WAIT))
        assertEquals(RecoveryChoice.WAIT, viewModel.ready.confirming)
        viewModel.onEvent(OnboardingEvent.ConfirmRecovery)
        runCurrent()
        assertEquals(listOf<String?>(null), recovery.verifiers)
        assertEquals(emptyList(), keeper.kept, "no PIN to keep: the lock step asks for a new one")
        assertTrue("signInAgain" in log.calls)
        assertEquals(OnboardingStep.ParentLock, viewModel.state.value.step)
        assertEquals(RegistrationStatus.ACTIVE, sessions.session.value?.deviceStatus)
    }

    @Test
    fun `a wait the server says is not over updates the date`() = test {
        clock.advanceBy(8.days)
        val later = Instant.fromEpochMilliseconds(WAIT_ENDS.toEpochMilliseconds() + 2.days.inWholeMilliseconds)
        recovery.options = Result.success(RecoveryOptions(pinParameters(), 10, waitEndsAt = WAIT_ENDS))
        recovery.answers += refused(RecoveryProblem.Wait(later))
        val viewModel = waitingPhone()
        viewModel.onEvent(OnboardingEvent.ShowRecoveryWait)
        viewModel.onEvent(OnboardingEvent.ChooseRecovery(RecoveryChoice.WAIT))
        viewModel.onEvent(OnboardingEvent.ConfirmRecovery)
        runCurrent()
        assertEquals(later, viewModel.ready.waitEndsAt)
        assertFalse(viewModel.ready.waitOver)
        assertIs<OnboardingStep.WaitingForApproval>(viewModel.state.value.step)
    }

    @Test
    fun `an old sign-in is proved again with Google and the call retried once with the same check value`() = test {
        recovery.answers += refused(RecoveryProblem.RecentSignIn)
        recovery.answers += restored()
        val viewModel = waitingPhone()
        viewModel.restoreWithPin("482935")
        runCurrent()
        assertTrue(viewModel.ready.signInNeeded)
        assertTrue(viewModel.ready.restoring)
        val signIns = log.calls.count { it == "signInWithGoogle" }

        viewModel.onEvent(OnboardingEvent.RecoverySignIn(ContextWrapper(null)))
        runCurrent()
        assertEquals(1, log.calls.count { it == "reauthenticateWithGoogle" }, "the same account, proved again")
        assertEquals(signIns, log.calls.count { it == "signInWithGoogle" }, "never a sign-in that could switch")
        assertEquals(listOf<String?>("482935", "482935"), recovery.verifiers)
        assertEquals(listOf("482935"), keeper.kept)
        assertEquals(OnboardingStep.ParentLock, viewModel.state.value.step)
    }

    @Test
    fun `a second old sign-in says to sign in again`() = test {
        recovery.answers += refused(RecoveryProblem.RecentSignIn)
        val viewModel = waitingPhone()
        viewModel.restoreWithPin("482935")
        runCurrent()
        viewModel.onEvent(OnboardingEvent.RecoverySignIn(ContextWrapper(null)))
        runCurrent()
        assertEquals(OnboardingFailure.SIGN_IN_AGAIN, viewModel.ready.failure)
        assertFalse(viewModel.ready.restoring)
        assertFalse(viewModel.ready.signInNeeded)
        assertEquals(2, recovery.verifiers.size, "retried once only")
    }

    @Test
    fun `the debug build cannot sign in again in place`() = test {
        recovery.answers += refused(RecoveryProblem.RecentSignIn)
        val debugAuth = FakeOnboardingAuth(log, usesEmailPassword = true).apply { claims[0] = parentClaims() }
        val viewModel = viewModel(debugAuth)
        viewModel.onEvent(OnboardingEvent.ChooseParent)
        viewModel.onEvent(OnboardingEvent.EmailChanged("parent@example.test"))
        viewModel.onEvent(OnboardingEvent.PasswordChanged("secret1"))
        viewModel.onEvent(OnboardingEvent.SignInWithEmail)
        viewModel.restoreWithPin("482935")
        runCurrent()
        assertEquals(OnboardingFailure.SIGN_IN_AGAIN, viewModel.ready.failure)
        assertFalse(viewModel.ready.signInNeeded)
    }

    @Test
    fun `approved meanwhile, the phone carries on without keeping the unchecked PIN`() = test {
        recovery.answers += refused(RecoveryProblem.NotPending(active = true))
        val viewModel = waitingPhone()
        viewModel.restoreWithPin("482935")
        runCurrent()
        assertEquals(emptyList(), keeper.kept)
        assertFalse("signInAgain" in log.calls, "an approval revokes no session")
        assertEquals(OnboardingStep.ParentLock, viewModel.state.value.step)
        assertEquals(RegistrationStatus.ACTIVE, sessions.session.value?.deviceStatus)
    }

    @Test
    fun `removed meanwhile, the phone is told so and starts again`() = test {
        recovery.answers += refused(RecoveryProblem.NotPending(active = false))
        val viewModel = waitingPhone()
        viewModel.restoreWithPin("482935")
        runCurrent()
        assertEquals(OnboardingUiState(failure = OnboardingFailure.DEVICE_REJECTED), viewModel.state.value)
        assertNull(sessions.session.value)
    }

    @Test
    fun `offline, the options fail with a retry, and approval still works`() = test {
        recovery.options = Result.failure(OnboardingException(OnboardingFailure.NETWORK))
        val viewModel = waitingPhone()
        assertEquals(RecoveryUi.Unavailable(OnboardingFailure.NETWORK), viewModel.waiting.recovery)
        recovery.options = Result.success(RecoveryOptions(pinParameters(), 10, null))
        viewModel.onEvent(OnboardingEvent.RetryRecoveryStatus)
        assertIs<RecoveryUi.Ready>(viewModel.waiting.recovery)

        recovery.answers += Result.failure(OnboardingException(OnboardingFailure.NETWORK))
        viewModel.restoreWithPin("482935")
        runCurrent()
        assertEquals(OnboardingFailure.NETWORK, viewModel.ready.failure)
        assertEquals(RegistrationStatus.PENDING, sessions.session.value?.deviceStatus)

        sessions.session.value = sessions.session.value?.copy(deviceStatus = RegistrationStatus.ACTIVE)
        runCurrent()
        assertEquals(OnboardingStep.ParentLock, viewModel.state.value.step)
    }

    @Test
    fun `another parent with a working phone approves there instead`() = test {
        recovery.options = Result.failure(RecoveryException(RecoveryProblem.OtherParent))
        assertEquals(RecoveryUi.OtherParent, waitingPhone().waiting.recovery)
    }

    @Test
    fun `a phone reopened while waiting shows the recovery options again`() = test {
        sessions.session.value =
            Session(PARENT_UID, HOUSEHOLD, Role.PARENT, null, NEW_DEVICE, RegistrationStatus.PENDING, clock.now())
        val viewModel = viewModel()
        assertIs<RecoveryUi.Ready>(viewModel.waiting.recovery)
        viewModel.restoreWithPin("482935")
        runCurrent()
        assertEquals(OnboardingStep.ParentLock, viewModel.state.value.step)
    }

    // ---- fix pass 1 ----

    @Test
    fun `the approval poll leaves a restore in flight alone, and the restore hands over`() = test {
        val viewModel = waitingPhone()
        recovery.onRecover = {
            // The server admits the phone before it answers; the poll or a pull mirrors ACTIVE meanwhile.
            sessions.session.value = sessions.session.value?.copy(deviceStatus = RegistrationStatus.ACTIVE)
        }
        val syncs = household.syncs
        viewModel.restoreWithPin("482935")
        runCurrent()
        assertEquals(listOf("482935"), keeper.kept, "the PIN is adopted")
        assertTrue("signInAgain" in log.calls, "the second sign-in runs")
        assertEquals(OnboardingStep.ParentLock, viewModel.state.value.step)
        assertEquals("482935", keeper.localPin, "the lock step finds a PIN: no \"choose a PIN\"")
        assertEquals(syncs + 1, household.syncs, "one pull after the sign-in, none from the poll")
    }

    @Test
    fun `while a restore runs the poll does not sync, and a transport failure after admission still goes on`() = test {
        val viewModel = waitingPhone()
        val gate = CompletableDeferred<Unit>()
        recovery.recoverGate = gate
        viewModel.restoreWithPin("482935")
        runCurrent()
        val syncs = household.syncs
        advanceTimeBy(OnboardingViewModel.POLL_INTERVAL_MS * 3)
        runCurrent()
        assertEquals(syncs, household.syncs, "no sync while the restore call runs")

        sessions.session.value = sessions.session.value?.copy(deviceStatus = RegistrationStatus.ACTIVE)
        recovery.answers += Result.failure(OnboardingException(OnboardingFailure.NETWORK))
        gate.complete(Unit)
        runCurrent()
        assertEquals(emptyList(), keeper.kept, "nothing proved the PIN: the lock step asks for one")
        assertEquals(OnboardingStep.ParentLock, viewModel.state.value.step)
    }

    @Test
    fun `the old sign-in is renewed for the same account only, and another account changes nothing`() = test {
        recovery.answers += refused(RecoveryProblem.RecentSignIn)
        auth.reauthResult = Result.failure(OnboardingException(OnboardingFailure.WRONG_ACCOUNT))
        val viewModel = waitingPhone()
        viewModel.restoreWithPin("482935")
        runCurrent()
        val syncs = household.syncs
        viewModel.onEvent(OnboardingEvent.RecoverySignIn(ContextWrapper(null)))
        runCurrent()
        assertTrue("reauthenticateWithGoogle" in log.calls)
        assertEquals(1, log.calls.count { it == "signInWithGoogle" }, "only the first sign-in, never a switch")
        assertEquals(OnboardingFailure.WRONG_ACCOUNT, viewModel.ready.failure)
        assertEquals(1, recovery.verifiers.size, "nothing sent under another account")
        assertEquals(syncs, household.syncs)
        assertEquals(RegistrationStatus.PENDING, sessions.session.value?.deviceStatus)
    }

    @Test
    fun `restore this account appears when the wait ends, without a reload`() = test {
        val ends = Instant.fromEpochMilliseconds(clock.now().toEpochMilliseconds() + 2.days.inWholeMilliseconds)
        recovery.options = Result.success(RecoveryOptions(pin = null, attemptsLeft = 0, waitEndsAt = ends))
        val viewModel = waitingPhone()
        assertFalse(viewModel.ready.waitOver)
        assertTrue(viewModel.ready.showWait, "a started wait is always shown")
        advanceTimeBy(1.days)
        runCurrent()
        assertFalse(viewModel.ready.waitOver)
        advanceTimeBy(1.days)
        runCurrent()
        assertTrue(viewModel.ready.waitOver)
        assertEquals(1, recovery.statusCalls.size, "no reload")
    }

    @Test
    fun `back on screen after the end of the wait shows the button at once`() = test {
        val ends = Instant.fromEpochMilliseconds(clock.now().toEpochMilliseconds() + 2.days.inWholeMilliseconds)
        recovery.options = Result.success(RecoveryOptions(pin = null, attemptsLeft = 0, waitEndsAt = ends))
        val viewModel = waitingPhone()
        clock.advanceBy(3.days)
        viewModel.onEvent(OnboardingEvent.RecoveryResumed)
        assertTrue(viewModel.ready.waitOver)
    }

    @Test
    fun `a phone that registers to wait removes the PIN already on it, and a PIN restore then adopts the server's`() =
        test {
            keeper.localPin = "135790"
            val viewModel = waitingPhone()
            assertEquals(1, keeper.forgotten)
            assertEquals(null, keeper.localPin, "the trial PIN is gone before the phone waits")
            viewModel.restoreWithPin("482935")
            runCurrent()
            assertEquals("482935", keeper.localPin)
        }

    @Test
    fun `a PIN that cannot be removed stops the registration before the phone waits`() = test {
        keeper.forgetFailure = java.io.IOException("disk")
        val viewModel = waitingPhone()
        assertEquals(OnboardingFailure.UNKNOWN, viewModel.state.value.failure)
        assertFalse(viewModel.state.value.step is OnboardingStep.WaitingForApproval)
        assertNull(sessions.session.value, "no PENDING session")
    }

    @Test
    fun `the wait is always offered, collapsed under the PIN way, open without it or once started`() {
        val pinWay = RecoveryUi.Ready(pinAvailable = true, attemptsLeft = 10, waitEndsAt = null, waitOver = false)
        assertFalse(pinWay.showWait)
        assertTrue(pinWay.copy(waitExpanded = true).showWait)
        assertTrue(pinWay.copy(waitEndsAt = WAIT_ENDS).showWait)
        assertTrue(pinWay.copy(pinAvailable = false).showWait)
    }
}
