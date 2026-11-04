package dev.kpetriv.sprout.feature.lock

import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
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
class PinSetupScreenTest {
    @get:Rule(order = 0)
    val compose = createComposeRule()

    @get:Rule(order = 1)
    val a11y = AccessibilityRule(compose)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val fixture = SessionFixture(scope)

    @After
    fun tearDown() = scope.cancel()

    private fun show() {
        val viewModel = fixture.setupViewModel()
        compose.setContent {
            SproutTheme {
                PinSetupScreen(state = viewModel.state.collectAsState().value, onEvent = viewModel::onEvent)
            }
        }
    }

    private fun type(pin: String) = pin.forEach { compose.onNodeWithText(it.toString()).performClick() }

    @Test
    fun `the rules are listed while choosing`() {
        show()
        compose.onNodeWithText("Choose a parent PIN").assertIsDisplayed()
        compose.onNodeWithText("• Not a run like 123456 or 654321").assertIsDisplayed()
    }

    @Test
    fun `a simple sequence is refused before the confirm step`() {
        show()
        type("123456")

        compose.onNodeWithText("Too easy to guess. Avoid runs like 123456.").assertIsDisplayed()
        compose.onNodeWithText("Choose a parent PIN").assertIsDisplayed()
    }

    @Test
    fun `a mismatched confirmation shows the error and starts over`() {
        show()
        type(PIN)
        compose.onNodeWithText("Enter the PIN again").assertIsDisplayed()

        type(WRONG_PIN)
        compose.waitForIdle()

        compose.onNodeWithText("The PINs didn't match. Start again.").assertIsDisplayed()
        compose.onNodeWithText("Choose a parent PIN").assertIsDisplayed()
        assertEquals(ParentSessionState.SetupRequired, fixture.session.state.value)
    }

    @Test
    fun `a matching confirmation saves the PIN and unlocks`() {
        show()
        type(PIN)
        type(PIN)
        compose.waitForIdle()

        assertIs<ParentSessionState.Unlocked>(fixture.session.state.value)
    }
}
