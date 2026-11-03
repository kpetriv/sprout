package dev.kpetriv.sprout.feature.lock

import dev.kpetriv.sprout.core.crypto.PinLockoutPolicy
import dev.kpetriv.sprout.feature.lock.SessionFixture.Companion.PIN
import dev.kpetriv.sprout.feature.lock.SessionFixture.Companion.WRONG_PIN
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class PinEntryViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private fun test(block: suspend TestScope.() -> Unit) = runTest(main.dispatcher) { block() }

    /** The boot clock follows virtual time, so the ViewModel's countdown and the lockout policy agree. */
    private fun TestScope.fixture() =
        SessionFixture(backgroundScope, pin = PIN, bootClock = TestSchedulerBootClock(testScheduler))

    private fun PinEntryViewModel.type(pin: String) = pin.forEach { onEvent(PinEntryEvent.Digit(it)) }

    @Test
    fun `digits and backspace move the dots`() = test {
        val viewModel = fixture().entryViewModel()

        viewModel.type("482")
        assertEquals(3, viewModel.state.value.enteredDigits)
        viewModel.onEvent(PinEntryEvent.Backspace)
        assertEquals(2, viewModel.state.value.enteredDigits)
    }

    @Test
    fun `the correct PIN unlocks the session`() = test {
        val fixture = fixture()
        val viewModel = fixture.entryViewModel()

        viewModel.type(PIN)
        runCurrent()

        assertIs<ParentSessionState.Unlocked>(fixture.session.state.value)
        // No parent key is registered, so the fingerprint is not offered.
        assertEquals(PinEntryUiState(), viewModel.state.value)
    }

    @Test
    fun `a wrong PIN shows the error, clears the dots and shakes`() = test {
        val viewModel = fixture().entryViewModel()

        viewModel.type(WRONG_PIN)
        runCurrent()

        val state = viewModel.state.value
        assertEquals(PinEntryError.WRONG_PIN, state.error)
        assertEquals(0, state.enteredDigits)
        assertEquals(1, state.wrongAttempts)
        assertEquals(LockoutUi.None, state.lockout)
    }

    @Test
    fun `a lockout counts down, blocks input, then re-opens`() = test {
        val fixture = fixture()
        val viewModel = fixture.entryViewModel()
        repeat(PinLockoutPolicy.FREE_ATTEMPTS + 1) {
            viewModel.type(WRONG_PIN)
            runCurrent()
        }
        assertEquals(LockoutUi.Wait(30), viewModel.state.value.lockout)
        assertFalse(viewModel.state.value.inputEnabled)

        viewModel.type("4")
        assertEquals(0, viewModel.state.value.enteredDigits)

        advanceTimeBy(10.seconds)
        runCurrent()
        assertEquals(LockoutUi.Wait(20), viewModel.state.value.lockout)

        advanceTimeBy(20.seconds)
        runCurrent()
        assertEquals(LockoutUi.None, viewModel.state.value.lockout)
        viewModel.type(PIN)
        runCurrent()
        assertIs<ParentSessionState.Unlocked>(fixture.session.state.value)
    }

    @Test
    fun `an existing lockout is shown on open`() = test {
        val fixture = fixture()
        repeat(PinLockoutPolicy.FREE_ATTEMPTS + 1) { fixture.session.unlockWithPin(WRONG_PIN.toCharArray()) }

        val viewModel = fixture.entryViewModel()
        runCurrent()

        assertEquals(LockoutUi.Wait(30), viewModel.state.value.lockout)
    }

    @Test
    fun `biometric unlock goes through the prompter`() = test {
        val fixture = fixture()
        fixture.enrolKeyDirectly()
        val viewModel = fixture.entryViewModel()
        runCurrent()
        assertTrue(viewModel.state.value.biometricAvailable)

        viewModel.onEvent(PinEntryEvent.UseBiometric { null })
        runCurrent()
        assertEquals(ParentSessionState.Locked, fixture.session.state.value)
        assertEquals(null, viewModel.state.value.error)

        viewModel.onEvent(PinEntryEvent.UseBiometric { request -> request.signature })
        runCurrent()
        assertIs<ParentSessionState.Unlocked>(fixture.session.state.value)
    }

    @Test
    fun `a failing prompt shows the biometric error`() = test {
        val fixture = fixture()
        fixture.enrolKeyDirectly()
        val viewModel = fixture.entryViewModel()

        viewModel.onEvent(PinEntryEvent.UseBiometric { throw BiometricErrorException(errorCode = 7) })
        runCurrent()

        assertEquals(PinEntryError.BIOMETRIC_FAILED, viewModel.state.value.error)
        assertEquals(ParentSessionState.Locked, fixture.session.state.value)
    }

    @Test
    fun `without a registered key the fingerprint is not offered`() = test {
        val fixture = fixture()
        val viewModel = fixture.entryViewModel()
        runCurrent()
        assertFalse(viewModel.state.value.biometricAvailable)

        viewModel.onEvent(PinEntryEvent.UseBiometric { error("No prompt without a registered key") })
        runCurrent()
        assertFalse(viewModel.state.value.biometricAvailable)
        assertEquals(null, viewModel.state.value.error)
        assertEquals(0, fixture.authorizer.keysCreated)
    }

    @Test
    fun `a changed fingerprint offers renewal, and the next PIN unlocks and renews the key`() = test {
        val fixture = fixture()
        val old = fixture.enrolKeyDirectly()
        val viewModel = fixture.entryViewModel()
        runCurrent()
        fixture.authorizer.invalidateKey()

        viewModel.onEvent(PinEntryEvent.UseBiometric { error("No prompt for an invalidated key") })
        runCurrent()
        assertEquals(PinEntryError.BIOMETRIC_KEY_CHANGED, viewModel.state.value.error)
        assertTrue(viewModel.state.value.renewalOffered)
        assertFalse(viewModel.state.value.biometricAvailable)

        viewModel.onEvent(PinEntryEvent.RenewSigningKey)
        assertTrue(viewModel.state.value.renewOnUnlock)
        viewModel.type(PIN)
        runCurrent()

        assertIs<ParentSessionState.Unlocked>(fixture.session.state.value)
        assertNotEquals(old, fixture.registry.key)
        assertEquals(fixture.registry.key, fixture.authorizer.existingParentPublicKey())
        assertEquals(1, fixture.registry.replacements)
    }

    @Test
    fun `after a renewal the next unlock offers the fingerprint and does not renew again`() = test {
        val fixture = fixture()
        fixture.enrolKeyDirectly()
        fixture.authorizer.invalidateKey()
        val viewModel = fixture.entryViewModel()
        runCurrent()

        viewModel.onEvent(PinEntryEvent.RenewSigningKey)
        viewModel.type(PIN)
        runCurrent()
        assertEquals(1, fixture.registry.replacements)
        assertFalse(viewModel.state.value.renewOnUnlock)
        assertFalse(viewModel.state.value.renewalOffered)
        assertTrue(viewModel.state.value.biometricAvailable)

        // The same ViewModel shows again at the next lock (it is shared with the kid-mode exit gate).
        fixture.session.lock()
        viewModel.onEvent(PinEntryEvent.Shown)
        viewModel.type(PIN)
        runCurrent()

        assertIs<ParentSessionState.Unlocked>(fixture.session.state.value)
        assertEquals(1, fixture.registry.replacements, "one renewal per choice")
        assertEquals(1, fixture.prompter.prompts)
    }

    @Test
    fun `a key that needs renewal is offered on open`() = test {
        val fixture = fixture()
        fixture.enrolKeyDirectly()
        fixture.authorizer.invalidateKey()

        val viewModel = fixture.entryViewModel()
        runCurrent()

        assertTrue(viewModel.state.value.renewalOffered)
        assertFalse(viewModel.state.value.biometricAvailable)
    }

    @Test
    fun `a wrong PIN in renewal mode renews nothing`() = test {
        val fixture = fixture()
        fixture.enrolKeyDirectly()
        fixture.authorizer.invalidateKey()
        val viewModel = fixture.entryViewModel()
        runCurrent()

        viewModel.onEvent(PinEntryEvent.RenewSigningKey)
        viewModel.type(WRONG_PIN)
        runCurrent()

        assertEquals(0, fixture.registry.replacements)
        assertEquals(0, fixture.prompter.prompts)
    }

    @Test
    fun `digits typed before a fingerprint unlock do not pre-fill the next gate`() = test {
        val fixture = fixture()
        fixture.enrolKeyDirectly()
        val viewModel = fixture.entryViewModel()
        runCurrent()

        viewModel.type(PIN.take(PinRules.LENGTH - 1))
        viewModel.onEvent(PinEntryEvent.UseBiometric { request -> request.signature })
        runCurrent()
        assertIs<ParentSessionState.Unlocked>(fixture.session.state.value)
        assertEquals(0, viewModel.state.value.enteredDigits)

        // The kid-mode exit gate reuses the same ViewModel.
        fixture.session.lock()
        viewModel.onEvent(PinEntryEvent.Shown)
        runCurrent()
        assertEquals(0, viewModel.state.value.enteredDigits)

        // The PIN's last digit alone must not complete the earlier five and unlock.
        viewModel.type(PIN.takeLast(1))
        runCurrent()
        assertEquals(1, viewModel.state.value.enteredDigits)
        assertFalse(viewModel.state.value.isVerifying)
        assertEquals(ParentSessionState.Locked, fixture.session.state.value)
    }

    @Test
    fun `showing the gate again clears typed digits`() = test {
        val fixture = fixture()
        val viewModel = fixture.entryViewModel()
        runCurrent()

        viewModel.type(PIN.take(3))
        assertEquals(3, viewModel.state.value.enteredDigits)
        viewModel.onEvent(PinEntryEvent.Shown)
        runCurrent()
        assertEquals(0, viewModel.state.value.enteredDigits)

        // Only the newly typed digits count: the full PIN typed now unlocks.
        viewModel.type(PIN)
        runCurrent()
        assertIs<ParentSessionState.Unlocked>(fixture.session.state.value)
    }

    @Test
    fun `a renewal choice does not carry over to the next gate`() = test {
        val fixture = fixture()
        fixture.enrolKeyDirectly()
        fixture.authorizer.invalidateKey()
        val viewModel = fixture.entryViewModel()
        runCurrent()

        viewModel.onEvent(PinEntryEvent.RenewSigningKey)
        assertTrue(viewModel.state.value.renewOnUnlock)
        viewModel.onEvent(PinEntryEvent.Shown)
        runCurrent()

        assertFalse(viewModel.state.value.renewOnUnlock)
        assertTrue(viewModel.state.value.renewalOffered)
        viewModel.type(PIN)
        runCurrent()
        assertIs<ParentSessionState.Unlocked>(fixture.session.state.value)
        assertEquals(0, fixture.registry.replacements)
    }
}
