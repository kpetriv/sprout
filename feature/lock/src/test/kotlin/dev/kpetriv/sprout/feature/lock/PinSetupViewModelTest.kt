package dev.kpetriv.sprout.feature.lock

import dev.kpetriv.sprout.core.crypto.ParentAuthAvailability
import dev.kpetriv.sprout.feature.lock.SessionFixture.Companion.PIN
import dev.kpetriv.sprout.feature.lock.SessionFixture.Companion.WRONG_PIN
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PinSetupViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private fun test(block: suspend TestScope.() -> Unit) = runTest(main.dispatcher) { block() }

    private fun PinSetupViewModel.type(pin: String) = pin.forEach { onEvent(PinSetupEvent.Digit(it)) }

    @Test
    fun `a rule-breaking PIN is refused at once`() = test {
        val viewModel = SessionFixture(backgroundScope).setupViewModel()

        viewModel.type("111111")
        assertEquals(PinSetupUiState(error = PinSetupError.ALL_SAME, rejections = 1), viewModel.state.value)

        viewModel.type("654321")
        assertEquals(PinSetupError.SEQUENCE, viewModel.state.value.error)
        assertEquals(PinSetupStep.ENTER, viewModel.state.value.step)
    }

    @Test
    fun `a mismatched confirmation starts over`() = test {
        val fixture = SessionFixture(backgroundScope)
        val viewModel = fixture.setupViewModel()

        viewModel.type(PIN)
        assertEquals(PinSetupStep.CONFIRM, viewModel.state.value.step)
        viewModel.type(WRONG_PIN)
        runCurrent()

        assertEquals(PinSetupUiState(error = PinSetupError.MISMATCH, rejections = 1), viewModel.state.value)
        assertEquals(ParentSessionState.SetupRequired, fixture.session.state.value)
    }

    @Test
    fun `a confirmed PIN is saved and unlocks`() = test {
        val fixture = SessionFixture(backgroundScope)
        val viewModel = fixture.setupViewModel()

        viewModel.type(PIN)
        viewModel.type(PIN)
        runCurrent()

        assertIs<ParentSessionState.Unlocked>(fixture.session.state.value)
        fixture.session.lock()
        assertEquals(PinUnlockResult.Unlocked, fixture.session.unlockWithPin(PIN.toCharArray()))
    }

    @Test
    fun `with a strong biometric the new PIN turns fingerprint signing on at once`() = test {
        val fixture = SessionFixture(backgroundScope)
        val viewModel = fixture.setupViewModel()

        viewModel.type(PIN)
        viewModel.type(PIN)
        runCurrent()

        val registered = assertNotNull(fixture.registry.key)
        assertEquals(registered, fixture.authorizer.existingParentPublicKey())
        assertEquals(1, fixture.prompter.prompts)
        assertEquals(ParentKeyStatus.READY, fixture.session.parentKeyStatus())
    }

    @Test
    fun `a dismissed signing prompt at setup still saves the PIN, with signing left off`() = test {
        val fixture = SessionFixture(backgroundScope)
        fixture.prompter.answer = { throw BiometricCancelledException(errorCode = 10) }
        val viewModel = fixture.setupViewModel()

        viewModel.type(PIN)
        viewModel.type(PIN)
        runCurrent()

        assertIs<ParentSessionState.Unlocked>(fixture.session.state.value)
        assertNull(fixture.registry.key)
        assertNull(fixture.authorizer.existingParentPublicKey())
        assertEquals(ParentKeyStatus.NOT_REGISTERED, fixture.session.parentKeyStatus())
    }

    @Test
    fun `the banner is re-checked when the screen resumes`() = test {
        val fixture = SessionFixture(backgroundScope)
        fixture.authorizer.availability = ParentAuthAvailability.NOT_ENROLLED
        val viewModel = fixture.setupViewModel()
        assertEquals(ParentAuthAvailability.NOT_ENROLLED, viewModel.state.value.signingAvailability)

        // The parent enrolled a fingerprint in system settings and came back.
        fixture.authorizer.availability = ParentAuthAvailability.STRONG_BIOMETRIC_AVAILABLE
        viewModel.onEvent(PinSetupEvent.RefreshAvailability)

        assertEquals(ParentAuthAvailability.STRONG_BIOMETRIC_AVAILABLE, viewModel.state.value.signingAvailability)
        viewModel.type(PIN)
        viewModel.type(PIN)
        runCurrent()
        assertNotNull(fixture.registry.key, "the new PIN turns signing on with the fingerprint enrolled meanwhile")
    }

    @Test
    fun `without a strong biometric setup shows the banner and records unsigned parent events`() = test {
        val fixture = SessionFixture(backgroundScope)
        fixture.authorizer.availability = ParentAuthAvailability.NO_HARDWARE
        val viewModel = fixture.setupViewModel()
        assertEquals(ParentAuthAvailability.NO_HARDWARE, viewModel.state.value.signingAvailability)

        viewModel.type(PIN)
        viewModel.type(PIN)
        runCurrent()

        assertIs<ParentSessionState.Unlocked>(fixture.session.state.value)
        assertTrue(fixture.registry.signingUnavailableRecorded)
        assertEquals(0, fixture.authorizer.keysCreated)
        assertEquals(0, fixture.prompter.prompts)
    }
}
