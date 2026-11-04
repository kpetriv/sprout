package dev.kpetriv.sprout.navigation

import android.app.Application
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.window.Dialog
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.sync.GateState
import dev.kpetriv.sprout.sync.Removal
import dev.kpetriv.sprout.sync.RemovalReason
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * `FLAG_SECURE` is structural (M7 §9, D-M7-6, D-M7-7, threat model T15): the onboarding, parent, kid and "Sign in
 * again" areas are secure whatever their screens do (and so are the dialogs they open), and the screens that neither
 * show household data nor take credentials are not.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class) // Not the Hilt app: its startup opens the encrypted database.
class SecureAreaTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var mode by mutableStateOf<AppMode>(AppMode.Loading)

    private val secure: Boolean
        get() = compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0

    @Test
    fun `onboarding, parent, kid and sign-in areas are secure, the others are not, and leaving one clears it`() {
        compose.setContent {
            AppModeContent(
                mode = mode,
                onboarding = { Text("Onboarding") },
                parent = { Text("Parent") },
                kid = { Text("Kid") },
                signInAgain = { Text("Sign in again") },
                removed = { Text("Removed") },
            )
        }
        val expected = listOf(
            AppMode.Loading to false,
            AppMode.Onboarding(canLeave = false) to true,
            AppMode.Parent to true,
            AppMode.Kid(KidId("kid-leo"), linkedDevice = true) to true,
            AppMode.Kid(KidId("kid-leo"), linkedDevice = false) to true,
            // It takes the account e-mail, the password and the parent PIN (audit 2 N4).
            AppMode.SignInAgain(GateState.Checking) to true,
            AppMode.Removed(Removal(RemovalReason.DEVICE_REVOKED)) to false,
            AppMode.Parent to true,
            AppMode.Loading to false,
        )
        expected.forEach { (next, flagged) ->
            mode = next
            compose.waitForIdle()
            assertEquals(flagged, secure, "FLAG_SECURE in $next")
        }
    }

    @Test
    fun `a dialog opened inside an area inherits its flag`() {
        compose.setContent {
            AppModeContent(
                mode = mode,
                onboarding = { Text("Onboarding") },
                parent = { AreaDialog("Parent dialog") },
                kid = { Text("Kid") },
                signInAgain = { AreaDialog("Erase this phone's copy?") },
                removed = { AreaDialog("Removed dialog") },
            )
        }
        val expected = listOf(
            AppMode.Parent to true,
            AppMode.SignInAgain(GateState.Unreachable) to true,
            AppMode.Removed(Removal(RemovalReason.DEVICE_REVOKED)) to false,
        )
        expected.forEach { (next, flagged) ->
            mode = next
            compose.waitForIdle()
            val dialog = assertNotNull(ShadowDialog.getLatestDialog(), "a dialog in $next")
            val dialogSecure = dialog.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
            assertEquals(flagged, dialogSecure, "FLAG_SECURE on the dialog window in $next (D-M7-76)")
        }
    }

    @Composable
    private fun AreaDialog(text: String) {
        Dialog(onDismissRequest = { }) { Text(text) }
    }
}
