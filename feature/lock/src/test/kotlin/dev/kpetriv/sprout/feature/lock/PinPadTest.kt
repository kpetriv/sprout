package dev.kpetriv.sprout.feature.lock

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.kpetriv.sprout.core.designsystem.theme.SproutTheme
import dev.kpetriv.sprout.core.ui.PinDots
import dev.kpetriv.sprout.core.ui.PinPad
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** Behaviour of `core/ui`'s [PinPad] and [PinDots]; it lives here because this module has the Robolectric setup. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h900dp")
class PinPadTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `every key reports its digit and backspace reports itself`() {
        val typed = StringBuilder()
        var backspaces = 0
        compose.setContent {
            SproutTheme { PinPad(onDigit = { typed.append(it) }, onBackspace = { backspaces++ }, enabled = true) }
        }

        "1234567890".forEach { compose.onNodeWithText(it.toString()).performClick() }
        compose.onNodeWithContentDescription("Delete digit").performClick()

        assertEquals("1234567890", typed.toString())
        assertEquals(1, backspaces)
    }

    @Test
    fun `a disabled pad sends nothing`() {
        val typed = StringBuilder()
        compose.setContent {
            SproutTheme { PinPad(onDigit = { typed.append(it) }, onBackspace = {}, enabled = false) }
        }

        compose.onNodeWithText("5").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Delete digit").assertIsNotEnabled()
        assertEquals("", typed.toString())
    }

    @Test
    fun `the dots announce how many digits were typed, never the digits`() {
        compose.setContent { SproutTheme { PinDots(entered = 3, length = 6, shakeKey = 0) } }

        compose.onNodeWithContentDescription("3 of 6 digits entered").assertExists()
    }
}
