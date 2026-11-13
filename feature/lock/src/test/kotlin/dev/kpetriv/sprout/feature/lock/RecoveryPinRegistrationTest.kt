package dev.kpetriv.sprout.feature.lock

import dev.kpetriv.sprout.feature.lock.SessionFixture.Companion.PIN
import dev.kpetriv.sprout.feature.lock.SessionFixture.Companion.WRONG_PIN
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days

/**
 * ADR-0013 §1: the server's recovery PIN check value follows the parent PIN. Set-up and change register the new PIN; a
 * correct PIN entry registers it while the flag is not set; failures are silent and retried; nothing waits for it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecoveryPinRegistrationTest {
    @get:Rule
    val main = MainDispatcherRule()

    private fun test(block: suspend TestScope.() -> Unit) = runTest(main.dispatcher) { block() }

    private fun TestScope.fixture(pin: String? = PIN) =
        SessionFixture(backgroundScope, pin = pin, bootClock = TestSchedulerBootClock(testScheduler))

    private fun PinEntryViewModel.type(pin: String) = pin.forEach { onEvent(PinEntryEvent.Digit(it)) }

    private fun PinSetupViewModel.type(pin: String) = pin.forEach { onEvent(PinSetupEvent.Digit(it)) }

    @Test
    fun `a new PIN is registered once set up, and the flag is set`() = test {
        val fixture = fixture(pin = null)
        val viewModel = fixture.setupViewModel()
        viewModel.type(PIN)
        viewModel.type(PIN)
        runCurrent()

        assertIs<ParentSessionState.Unlocked>(fixture.session.state.value)
        assertEquals(listOf(PIN), fixture.uploader.uploads)
        assertEquals(listOf(true), fixture.uploader.newPins, "a PIN just chosen restarts the server's attempts")
        assertTrue(fixture.recoveryFlag.isRegistered())
        assertEquals(listOf(false, true), fixture.recoveryFlag.writes, "cleared before the hash, set after the upload")
        assertTrue(fixture.uploader.arrays.single().all { it == '\u0000' }, "the PIN copy is zeroed after the upload")
    }

    @Test
    fun `unlocking never waits for the upload`() = test {
        val fixture = fixture()
        fixture.uploader.gate = CompletableDeferred()
        val viewModel = fixture.entryViewModel()
        viewModel.type(PIN)
        runCurrent()

        assertIs<ParentSessionState.Unlocked>(fixture.session.state.value)
        assertFalse(viewModel.state.value.isVerifying)
        assertFalse(fixture.recoveryFlag.isRegistered(), "the upload is still running")
        fixture.uploader.gate?.complete(Unit)
        runCurrent()
        assertTrue(fixture.recoveryFlag.isRegistered())
    }

    @Test
    fun `a correct PIN registers while the flag is not set, and only then`() = test {
        val fixture = fixture()
        val viewModel = fixture.entryViewModel()
        viewModel.type(PIN)
        runCurrent()
        assertEquals(listOf(PIN), fixture.uploader.uploads)
        assertTrue(fixture.recoveryFlag.isRegistered())

        fixture.session.lock()
        viewModel.onEvent(PinEntryEvent.Shown)
        viewModel.type(PIN)
        runCurrent()
        assertIs<ParentSessionState.Unlocked>(fixture.session.state.value)
        assertEquals(1, fixture.uploader.uploads.size, "registered already: no second upload")
    }

    @Test
    fun `a registration older than 30 days is made again at the next correct PIN`() = test {
        val fixture = fixture()
        val viewModel = fixture.entryViewModel()
        viewModel.type(PIN)
        runCurrent()
        fixture.session.lock()
        fixture.clock.advanceBy(RecoveryPinRegistration.REFRESH_AFTER - 1.days)
        viewModel.onEvent(PinEntryEvent.Shown)
        viewModel.type(PIN)
        runCurrent()
        assertEquals(1, fixture.uploader.uploads.size, "still fresh")

        fixture.session.lock()
        fixture.clock.advanceBy(2.days)
        viewModel.onEvent(PinEntryEvent.Shown)
        viewModel.type(PIN)
        runCurrent()
        assertEquals(listOf(PIN, PIN), fixture.uploader.uploads)
        assertEquals(listOf(false, false), fixture.uploader.newPins, "an unchanged PIN keeps the server's count")
        assertEquals(fixture.clock.now(), fixture.recoveryFlag.registeredAt())
        assertEquals(10, fixture.recoveryFlag.registration()?.attemptsLeft)
    }

    @Test
    fun `a failed upload is silent and retried at the next correct PIN, a wrong PIN uploads nothing`() = test {
        val fixture = fixture()
        fixture.uploader.result = RecoveryUploadResult.Failed
        val viewModel = fixture.entryViewModel()
        viewModel.type(PIN)
        runCurrent()
        assertIs<ParentSessionState.Unlocked>(fixture.session.state.value)
        assertEquals(null, viewModel.state.value.error)
        assertFalse(fixture.recoveryFlag.isRegistered())

        fixture.session.lock()
        viewModel.onEvent(PinEntryEvent.Shown)
        viewModel.type(WRONG_PIN)
        runCurrent()
        assertEquals(1, fixture.uploader.uploads.size)

        fixture.uploader.result = RecoveryUploadResult.Uploaded(stored = true, attemptsLeft = 10)
        viewModel.type(PIN)
        runCurrent()
        assertEquals(listOf(PIN, PIN), fixture.uploader.uploads)
        assertTrue(fixture.recoveryFlag.isRegistered())
    }

    @Test
    fun `nothing to register (the trial household) leaves the flag unset`() = test {
        val fixture = fixture()
        fixture.uploader.result = RecoveryUploadResult.NotApplicable
        fixture.entryViewModel().type(PIN)
        runCurrent()
        assertFalse(fixture.recoveryFlag.isRegistered())
    }

    @Test
    fun `a PIN change clears the flag and registers the new PIN`() = test {
        val fixture = fixture()
        fixture.recoveryFlag.setMarks(RecoveryPinMarks(registered = RecoveryRegistration(fixture.clock.now(), 10)))
        assertEquals(PinUnlockResult.Unlocked, fixture.session.unlockWithPin(PIN.toCharArray()))
        runCurrent()
        fixture.uploader.uploads.clear()
        fixture.uploader.newPins.clear()
        fixture.recoveryFlag.writes.clear()

        val changed = fixture.session.changePin(PIN.toCharArray(), "271828".toCharArray(), "271828".toCharArray())
        assertEquals(ChangePinResult.Changed, changed)
        runCurrent()
        assertEquals(listOf(false, true), fixture.recoveryFlag.writes, "cleared before the hash, set after the upload")
        assertEquals(listOf("271828"), fixture.uploader.uploads)
        assertEquals(listOf(true), fixture.uploader.newPins)
        assertTrue(fixture.recoveryFlag.isRegistered())
    }

    @Test
    fun `an upload of an older PIN never sets the flag for a newer one`() = test {
        val fixture = fixture()
        val gate = CompletableDeferred<Unit>()
        fixture.uploader.gate = gate
        fixture.session.unlockWithPin(PIN.toCharArray())
        runCurrent()
        // The upload of PIN is still running when the PIN changes.
        fixture.session.changePin(PIN.toCharArray(), "271828".toCharArray(), "271828".toCharArray())
        runCurrent()
        gate.complete(Unit)
        runCurrent()

        assertEquals(listOf(PIN, "271828"), fixture.uploader.uploads)
        assertEquals(listOf(false, true), fixture.recoveryFlag.writes, "the older upload wrote nothing")
    }

    @Test
    fun `a PIN restored from the server becomes the local PIN, unlocks and is registered`() = test {
        val fixture = fixture(pin = null)
        val result = fixture.session.adoptRecoveredPin(PIN.toCharArray(), attemptsLeft = 7)
        assertEquals(RecoveredPinResult.Adopted, result)
        assertEquals(RecoveryRegistration(fixture.clock.now(), 7), fixture.recoveryFlag.registration())
        assertEquals(ParentSessionState.Unlocked::class, fixture.session.state.value::class)
        assertTrue(fixture.recoveryFlag.isRegistered())
        runCurrent()
        assertEquals(emptyList(), fixture.uploader.uploads, "the server's value already matches")
        fixture.session.lock()
        assertEquals(PinUnlockResult.Unlocked, fixture.session.unlockWithPin(PIN.toCharArray()))
    }

    @Test
    fun `an existing PIN is never replaced by a restored one`() = test {
        val fixture = fixture(pin = "135790")
        assertEquals(RecoveredPinResult.NotAllowed, fixture.session.adoptRecoveredPin(PIN.toCharArray(), 7))
        assertEquals(ParentSessionState.Locked, fixture.session.state.value)
        assertFalse(fixture.recoveryFlag.isRegistered())
        assertEquals(PinUnlockResult.Unlocked, fixture.session.unlockWithPin("135790".toCharArray()))
    }

    @Test
    fun `settings registers now after checking the PIN under the lockout`() = test {
        val fixture = fixture()
        fixture.uploader.result = RecoveryUploadResult.Failed
        assertEquals(RecoveryPinSetupResult.NotAllowed, fixture.session.registerRecoveryPin(PIN.toCharArray()))
        fixture.session.unlockWithPin(PIN.toCharArray())
        runCurrent()
        fixture.uploader.uploads.clear()
        fixture.uploader.newPins.clear()

        assertIs<RecoveryPinSetupResult.WrongPin>(fixture.session.registerRecoveryPin(WRONG_PIN.toCharArray()))
        assertEquals(emptyList(), fixture.uploader.uploads)
        assertEquals(RecoveryPinSetupResult.Failed, fixture.session.registerRecoveryPin(PIN.toCharArray()))
        fixture.uploader.result = RecoveryUploadResult.Uploaded(stored = true, attemptsLeft = 10)
        assertEquals(RecoveryPinSetupResult.Registered, fixture.session.registerRecoveryPin(PIN.toCharArray()))
        assertTrue(fixture.recoveryFlag.isRegistered())
        assertEquals(listOf(PIN, PIN), fixture.uploader.uploads)
    }

    // ---- fix pass 1: new PIN mark, kept-other answers, same PIN, a new PENDING device, the wipe ----

    @Test
    fun `a new PIN whose upload failed goes up as new at the next correct PIN, and success clears the mark`() = test {
        val fixture = fixture()
        fixture.session.unlockWithPin(PIN.toCharArray())
        runCurrent()
        fixture.uploader.result = RecoveryUploadResult.Failed
        fixture.session.changePin(PIN.toCharArray(), "271828".toCharArray(), "271828".toCharArray())
        runCurrent()
        assertTrue(fixture.recoveryFlag.marks().newPinPending, "the new PIN has not gone up")

        fixture.uploader.result = RecoveryUploadResult.Uploaded(stored = true, attemptsLeft = 10)
        fixture.session.lock()
        assertEquals(PinUnlockResult.Unlocked, fixture.session.unlockWithPin("271828".toCharArray()))
        runCurrent()
        assertEquals(listOf(false, true, true), fixture.uploader.newPins)
        val marks = fixture.recoveryFlag.marks()
        assertFalse(marks.newPinPending)
        assertTrue(fixture.recoveryFlag.isRegistered())
    }

    @Test
    fun `a value kept on another phone is remembered, not counted as registered, and not asked for every unlock`() =
        test {
            val fixture = fixture()
            fixture.uploader.result = RecoveryUploadResult.Uploaded(stored = false, attemptsLeft = 8)
            fixture.session.unlockWithPin(PIN.toCharArray())
            runCurrent()
            val marks = fixture.recoveryFlag.marks()
            assertEquals(null, marks.registered)
            assertEquals(RecoveryRegistration(fixture.clock.now(), 8), marks.keptOther)

            fixture.session.lock()
            fixture.session.unlockWithPin(PIN.toCharArray())
            runCurrent()
            assertEquals(1, fixture.uploader.uploads.size, "no upload at every unlock")

            fixture.session.lock()
            fixture.clock.advanceBy(RecoveryPinRegistration.REFRESH_AFTER)
            fixture.session.unlockWithPin(PIN.toCharArray())
            runCurrent()
            assertEquals(2, fixture.uploader.uploads.size, "asked again after 30 days")
            assertEquals(listOf(false, false), fixture.uploader.newPins)
        }

    @Test
    fun `changing the PIN to the PIN it is sends it as unchanged and sets no mark`() = test {
        val fixture = fixture()
        fixture.session.unlockWithPin(PIN.toCharArray())
        runCurrent()
        fixture.uploader.newPins.clear()
        assertEquals(
            ChangePinResult.Changed,
            fixture.session.changePin(PIN.toCharArray(), PIN.toCharArray(), PIN.toCharArray()),
        )
        runCurrent()
        assertEquals(listOf(false), fixture.uploader.newPins, "no ten new attempts for the same PIN")
        assertFalse(fixture.recoveryFlag.marks().newPinPending)

        // A mark already set stays: a new PIN that has not gone up yet is still new.
        fixture.uploader.result = RecoveryUploadResult.Failed
        fixture.session.changePin(PIN.toCharArray(), "271828".toCharArray(), "271828".toCharArray())
        runCurrent()
        fixture.session.changePin("271828".toCharArray(), "271828".toCharArray(), "271828".toCharArray())
        runCurrent()
        assertEquals(listOf(false, true, true), fixture.uploader.newPins)
        assertTrue(fixture.recoveryFlag.marks().newPinPending)
    }

    @Test
    fun `a new PENDING device forgets the PIN, its lockout and the marks, and a restore then adopts its PIN`() = test {
        val fixture = fixture(pin = "135790")
        fixture.recoveryFlag.setMarks(RecoveryPinMarks(newPinPending = true))
        assertIs<PinUnlockResult.WrongPin>(fixture.session.unlockWithPin(WRONG_PIN.toCharArray()))

        fixture.session.forgetPinForNewDevice()
        assertEquals(null, fixture.pinStore.hash)
        assertEquals(ParentSessionState.SetupRequired, fixture.session.state.value)
        assertEquals(RecoveryPinMarks(), fixture.recoveryFlag.marks())
        assertEquals(dev.kpetriv.sprout.core.crypto.LockoutDecision.Allowed, fixture.session.lockoutDecision())

        assertEquals(RecoveredPinResult.Adopted, fixture.session.adoptRecoveredPin(PIN.toCharArray(), 9))
        fixture.session.lock()
        assertEquals(PinUnlockResult.Unlocked, fixture.session.unlockWithPin(PIN.toCharArray()))
    }

    @Test
    fun `a PIN that cannot be removed stops the new registration`() = test {
        val fixture = fixture()
        fixture.pinStore.failWrites = true
        val failure = runCatching { fixture.session.forgetPinForNewDevice() }.exceptionOrNull()
        assertIs<java.io.IOException>(failure)
    }

    @Test
    fun `an upload still running when the household is wiped writes no mark afterwards`() = test {
        val fixture = fixture()
        val gate = CompletableDeferred<Unit>()
        fixture.uploader.gate = gate
        fixture.session.unlockWithPin(PIN.toCharArray())
        runCurrent()
        fixture.recoveryPin.forget()
        gate.complete(Unit)
        runCurrent()
        assertEquals(1, fixture.uploader.uploads.size)
        assertEquals(RecoveryPinMarks(), fixture.recoveryFlag.marks())
    }

    @Test
    fun `settings says when the server kept the other phone's value, and when it refused`() = test {
        val fixture = fixture()
        fixture.session.unlockWithPin(PIN.toCharArray())
        runCurrent()
        fixture.uploader.result = RecoveryUploadResult.Uploaded(stored = false, attemptsLeft = 10)
        assertEquals(RecoveryPinSetupResult.KeptOther, fixture.session.registerRecoveryPin(PIN.toCharArray()))
        fixture.uploader.result = RecoveryUploadResult.Refused
        assertEquals(RecoveryPinSetupResult.Refused, fixture.session.registerRecoveryPin(PIN.toCharArray()))
    }
}
