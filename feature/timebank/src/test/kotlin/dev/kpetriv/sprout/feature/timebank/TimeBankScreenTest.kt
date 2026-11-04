package dev.kpetriv.sprout.feature.timebank

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.kpetriv.sprout.core.designsystem.theme.SproutTheme
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.testing.accessibility.AccessibilityRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Renders [TimeBankScreen] per tier under Robolectric. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rGB-w411dp-h2000dp")
class TimeBankScreenTest {
    @get:Rule(order = 0)
    val compose = createComposeRule()

    @get:Rule(order = 1)
    val a11y = AccessibilityRule(compose)

    private val events = mutableListOf<TimeBankEvent>()

    private fun show(state: TimeBankUiState) {
        compose.setContent { SproutTheme { TimeBankScreen(state = state, onEvent = { events += it }) } }
    }

    @Test
    fun `seedling sun is one big button that starts and says the minutes`() {
        show(TimeBankPreviewData.idle(KidUiTier.SEEDLING))

        val sun = compose.onNodeWithContentDescription("Start screen time. 45 minutes left.")
        sun.assertIsDisplayed()
        val size = sun.fetchSemanticsNode().size
        assertTrue(with(compose.density) { minOf(size.width, size.height).toDp().value } >= 72f)
        sun.performClick()

        assertEquals(listOf<TimeBankEvent>(TimeBankEvent.Start), events)
        compose.onNodeWithText("45 minutes left").assertIsDisplayed()
    }

    @Test
    fun `seedling running sun stops`() {
        show(TimeBankPreviewData.running(KidUiTier.SEEDLING))

        compose.onNodeWithContentDescription("Stop screen time. 33 minutes left.").performClick()

        assertEquals(listOf<TimeBankEvent>(TimeBankEvent.Stop), events)
    }

    @Test
    fun `seedling empty sun is disabled`() {
        show(TimeBankPreviewData.empty(KidUiTier.SEEDLING))

        compose.onNodeWithContentDescription("No screen time left.").assertIsNotEnabled()
        compose.onNodeWithText("Do chores or save up for a screen time reward to get more minutes.").assertIsDisplayed()
    }

    @Test
    fun `sprout shows the timer, minutes left and today's sessions`() {
        show(TimeBankPreviewData.running(KidUiTier.SPROUT))

        compose.onNodeWithContentDescription("Time used 12:34").assertIsDisplayed()
        compose.onNodeWithText("33 minutes left").assertIsDisplayed()
        compose.onNodeWithText("16:05 · 20 min").assertIsDisplayed()
        compose.onNodeWithText("Used today: 35 min").assertIsDisplayed()
        val stop = compose.onNodeWithText("Stop")
        val height = stop.fetchSemanticsNode().let { node ->
            generateSequence(node) { it.parent }.first { SemanticsProperties.Role in it.config }.size.height
        }
        assertTrue(with(compose.density) { height.toDp().value } >= 56f, "height was ${height}px")
        stop.performClick()

        assertEquals(listOf<TimeBankEvent>(TimeBankEvent.Stop), events)
    }

    @Test
    fun `sapling shows the rule text and dated history`() {
        show(TimeBankPreviewData.idle(KidUiTier.SAPLING))

        compose.onNodeWithText("How it works").assertIsDisplayed()
        compose.onNodeWithText("Balance: 45 min").assertIsDisplayed()
        compose.onNodeWithText("3 Nov 2026, 18:30 · 30 min").assertIsDisplayed()
        compose.onNodeWithText("Start").performClick()

        assertEquals(listOf<TimeBankEvent>(TimeBankEvent.Start), events)
    }

    @Test
    fun `a stop message is shown once`() {
        show(TimeBankPreviewData.idle(KidUiTier.SPROUT).copy(message = TimeBankMessage.Stopped(12)))

        compose.onNodeWithText("Stopped. 12 minutes used.").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(10_000)
        compose.waitForIdle()

        assertEquals(listOf<TimeBankEvent>(TimeBankEvent.MessageShown), events)
    }
}
