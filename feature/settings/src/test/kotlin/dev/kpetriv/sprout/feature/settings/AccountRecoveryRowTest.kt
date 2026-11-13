package dev.kpetriv.sprout.feature.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.kpetriv.sprout.core.designsystem.theme.SproutTheme
import dev.kpetriv.sprout.core.testing.accessibility.AccessibilityRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** Settings → Security's "Account recovery" row (ADR-0013 §1, §3), rendered. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h2400dp")
class AccountRecoveryRowTest {
    @get:Rule(order = 0)
    val compose = createComposeRule()

    @get:Rule(order = 1)
    val a11y = AccessibilityRule(compose)

    private var row by mutableStateOf<RecoveryRowUi?>(null)
    private val events = mutableListOf<ParentSecurityEvent>()

    private fun show(initial: RecoveryRowUi?) {
        row = initial
        compose.setContent {
            SproutTheme {
                ParentSecurityScreen(
                    SettingsPreviewData.security().copy(recovery = row),
                    onEvent = { events += it },
                    onBack = {},
                )
            }
        }
    }

    private fun shows(text: String) =
        compose.onNodeWithText(text, substring = true).performScrollTo().assertIsDisplayed()

    private fun hidden(text: String) =
        assertEquals(0, compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().size)

    @Test
    fun `not set up asks for the PIN`() {
        show(RecoveryRowUi(ready = false))
        shows("Enter your PIN to finish setting up account recovery")
        compose.onNodeWithText("Finish setting up").performScrollTo().performClick()
        assertEquals(listOf<ParentSecurityEvent>(ParentSecurityEvent.StartRecoverySetup), events)
    }

    @Test
    fun `ready with no wrong PINs says nothing more`() {
        show(RecoveryRowUi(ready = true, attemptsLeft = 10))
        shows("Ready.")
        hidden("wrong PIN")
    }

    @Test
    fun `nine attempts left says one wrong PIN was tried`() {
        show(RecoveryRowUi(ready = true, attemptsLeft = 9))
        shows("1 wrong PIN was tried when someone tried to restore this account on another phone. Change your PIN.")
    }

    @Test
    fun `one attempt left says nine wrong PINs were tried`() {
        show(RecoveryRowUi(ready = true, attemptsLeft = 1))
        shows("9 wrong PINs were tried when someone tried to restore this account on another phone. Change your PIN.")
    }

    @Test
    fun `none left says the PIN way is closed until the PIN is changed`() {
        show(RecoveryRowUi(ready = true, attemptsLeft = 0))
        shows("Restoring with your PIN is closed. Change your PIN to open it again.")
        hidden("wrong PIN was tried")
        hidden("Ready.")
    }

    @Test
    fun `a trial household has no row`() {
        show(null)
        hidden("Account recovery")
    }

    @Test
    fun `a value kept on the other phone says so instead of ready, with no finish button`() {
        show(RecoveryRowUi(ready = false, attemptsLeft = 10, keptOther = true))
        shows("This account restores with the parent PIN you set or changed last, on your other phone.")
        hidden("Ready.")
        hidden("Finish setting up")
    }

    @Test
    fun `a value kept on the other phone still warns about wrong PINs`() {
        show(RecoveryRowUi(ready = false, attemptsLeft = 8, keptOther = true))
        shows("2 wrong PINs were tried")
    }
}
