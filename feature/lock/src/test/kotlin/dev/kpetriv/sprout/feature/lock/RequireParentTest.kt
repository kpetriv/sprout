package dev.kpetriv.sprout.feature.lock

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import dev.kpetriv.sprout.core.crypto.ParentAuthAvailability
import dev.kpetriv.sprout.core.designsystem.theme.SproutTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

@RunWith(RobolectricTestRunner::class)
class RequireParentTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var session by mutableStateOf<ParentSessionState>(ParentSessionState.Initializing)
    private var gateShown by mutableStateOf(true)

    private val isSecure: Boolean
        get() = compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0

    private fun show() {
        compose.setContent {
            SproutTheme {
                if (gateShown) {
                    RequireParentContent(
                        session = session,
                        locked = { Text("PIN entry") },
                        setup = { Text("PIN setup") },
                    ) { Text("Parent dashboard") }
                }
            }
        }
    }

    @Test
    fun `content shows only while unlocked`() {
        show()
        compose.onNodeWithText("Parent dashboard").assertDoesNotExist()

        session = ParentSessionState.SetupRequired
        compose.onNodeWithText("PIN setup").assertIsDisplayed()

        session = ParentSessionState.Locked
        compose.onNodeWithText("PIN entry").assertIsDisplayed()
        compose.onNodeWithText("Parent dashboard").assertDoesNotExist()

        session = ParentSessionState.Unlocked(Instant.fromEpochSeconds(0), UnlockMethod.PIN)
        compose.onNodeWithText("Parent dashboard").assertIsDisplayed()
        compose.onNodeWithText("PIN entry").assertDoesNotExist()

        session = ParentSessionState.Locked
        compose.onNodeWithText("Parent dashboard").assertDoesNotExist()
        compose.onNodeWithText("PIN entry").assertIsDisplayed()
    }

    @Test
    fun `the window is FLAG_SECURE while the gate is shown, content included`() {
        session = ParentSessionState.Unlocked(Instant.fromEpochSeconds(0), UnlockMethod.PIN)
        show()
        compose.waitForIdle()
        assertTrue(isSecure)

        gateShown = false
        compose.waitForIdle()
        assertFalse(isSecure)
    }

    @Test
    fun `nested secure screens keep the flag until the last one leaves`() {
        var inner by mutableStateOf(true)
        compose.setContent {
            SecureWindow()
            if (inner) SecureWindow()
            if (gateShown) SecureWindow()
        }
        compose.waitForIdle()
        assertTrue(isSecure)

        inner = false
        compose.waitForIdle()
        assertTrue(isSecure)

        gateShown = false
        compose.waitForIdle()
        assertTrue(isSecure, "the outer SecureWindow is still in composition")
    }

    @Test
    fun `the signature banner warns only without a strong biometric`() {
        var availability by mutableStateOf(ParentAuthAvailability.NOT_ENROLLED)
        compose.setContent { SproutTheme { ParentSignaturePolicyBanner(availability = availability) } }

        compose.onNodeWithText("Money changes on this device can't be fingerprint-signed").assertIsDisplayed()
        compose.onNodeWithText("Add a fingerprint in system settings", substring = true).assertIsDisplayed()

        availability = ParentAuthAvailability.STRONG_BIOMETRIC_AVAILABLE
        compose.onNodeWithText("Money changes on this device can't be fingerprint-signed").assertDoesNotExist()
    }
}
