package dev.kpetriv.sprout.feature.lock

import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.kpetriv.sprout.core.designsystem.theme.SproutTheme
import dev.kpetriv.sprout.core.testing.accessibility.AccessibilityRule
import dev.kpetriv.sprout.feature.lock.SessionFixture.Companion.PIN
import dev.kpetriv.sprout.feature.lock.SessionFixture.Companion.WRONG_PIN
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertIs

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h1200dp")
class PinEntryScreenTest {
    @get:Rule(order = 0)
    val compose = createComposeRule()

    @get:Rule(order = 1)
    val a11y = AccessibilityRule(compose)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val events = mutableListOf<PinEntryEvent>()

    @After
    fun tearDown() = scope.cancel()

    private fun showViewModel(fixture: SessionFixture): PinEntryViewModel {
        val viewModel = fixture.entryViewModel()
        compose.setContent {
            SproutTheme {
                val state = viewModel.state.collectAsState().value
                PinEntryScreen(state = state, onEvent = viewModel::onEvent)
            }
        }
        return viewModel
    }

    private fun show(state: PinEntryUiState, onUseBiometric: (() -> Unit)? = null) {
        compose.setContent {
            SproutTheme { PinEntryScreen(state = state, onEvent = { events += it }, onUseBiometric = onUseBiometric) }
        }
    }

    private fun type(pin: String) = pin.forEach { compose.onNodeWithText(it.toString()).performClick() }

    @Test
    fun `the dots follow the digits typed`() {
        showViewModel(SessionFixture(scope, pin = PIN))
        compose.onNodeWithContentDescription("0 of 6 digits entered").assertIsDisplayed()

        type("482")
        compose.onNodeWithContentDescription("3 of 6 digits entered").assertIsDisplayed()

        compose.onNodeWithContentDescription("Delete digit").performClick()
        compose.onNodeWithContentDescription("2 of 6 digits entered").assertIsDisplayed()
    }

    @Test
    fun `a wrong PIN shows the error and clears the dots`() {
        val fixture = SessionFixture(scope, pin = PIN)
        showViewModel(fixture)

        type(WRONG_PIN)
        compose.waitForIdle()

        compose.onNodeWithText("Wrong PIN").assertIsDisplayed()
        compose.onNodeWithContentDescription("0 of 6 digits entered").assertIsDisplayed()
        assertEquals(ParentSessionState.Locked, fixture.session.state.value)
    }

    @Test
    fun `the correct PIN unlocks`() {
        val fixture = SessionFixture(scope, pin = PIN)
        showViewModel(fixture)

        type(PIN)
        compose.waitForIdle()

        assertIs<ParentSessionState.Unlocked>(fixture.session.state.value)
    }

    @Test
    fun `a lockout shows the countdown and disables the keypad`() {
        show(PinEntryUiState(lockout = LockoutUi.Wait(remainingSeconds = 95), error = PinEntryError.WRONG_PIN))

        compose.onNodeWithText("Too many tries. Try again in 1:35").assertIsDisplayed()
        compose.onNodeWithText("5").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Delete digit").assertIsNotEnabled()
    }

    @Test
    fun `after the cap the screen asks for the fingerprint`() {
        var prompts = 0
        show(PinEntryUiState(lockout = LockoutUi.RequireBiometric, biometricAvailable = true)) { prompts++ }

        compose.onNodeWithText("PIN locked after too many tries. Unlock with your fingerprint.").assertIsDisplayed()
        compose.onNodeWithText("1").assertIsNotEnabled()
        compose.onNodeWithText("Use fingerprint").assertIsEnabled().performClick()
        assertEquals(1, prompts)
    }

    @Test
    fun `no fingerprint button without a prompter or a strong biometric`() {
        show(PinEntryUiState(biometricAvailable = false)) {}
        compose.onNodeWithText("Use fingerprint").assertDoesNotExist()
    }

    @Test
    fun `a key that needs renewal offers it`() {
        show(PinEntryUiState(renewalOffered = true, error = PinEntryError.BIOMETRIC_KEY_CHANGED))
        compose.onNodeWithText("Fingerprint changed. Renew the fingerprint key with your PIN.").assertIsDisplayed()
        compose.onNodeWithText("Renew fingerprint key").performClick()
        assertEquals(listOf<PinEntryEvent>(PinEntryEvent.RenewSigningKey), events)
    }

    @Test
    fun `in renewal mode the screen explains what the next PIN does`() {
        show(PinEntryUiState(renewalOffered = true, renewOnUnlock = true))
        compose.onNodeWithText("Enter your PIN to unlock and renew the fingerprint key").assertIsDisplayed()
        compose.onNodeWithText("Renew fingerprint key").assertDoesNotExist()
    }

    @Test
    fun `keys send digit and backspace events`() {
        show(PinEntryUiState(enteredDigits = 1))

        compose.onNodeWithText("7").performClick()
        compose.onNodeWithContentDescription("Delete digit").performClick()

        assertEquals(listOf(PinEntryEvent.Digit('7'), PinEntryEvent.Backspace), events)
    }

    @Test
    fun `formatCountdown pads seconds`() {
        assertEquals("0:05", formatCountdown(5))
        assertEquals("60:00", formatCountdown(3_600))
    }
}
