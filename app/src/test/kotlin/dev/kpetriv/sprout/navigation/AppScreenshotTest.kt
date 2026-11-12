package dev.kpetriv.sprout.navigation

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.kpetriv.sprout.core.model.AgeBand
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.testing.screenshot.ScreenshotTest
import dev.kpetriv.sprout.kids.KidPickPurpose
import dev.kpetriv.sprout.kids.KidPickerDialog
import dev.kpetriv.sprout.kids.KidRow
import dev.kpetriv.sprout.kids.KidsUiState
import dev.kpetriv.sprout.kids.StartupErrorScreen
import dev.kpetriv.sprout.startup.StartupFailure
import dev.kpetriv.sprout.sync.GateFailure
import dev.kpetriv.sprout.sync.GateState
import dev.kpetriv.sprout.sync.RemovalReason
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * Goldens of the screens the app shell composes itself (M7 §7): the kid picker, "Sign in again" and its erase
 * dialog, the removed screen, the startup error and the kid's home shortcut bar. Parent-facing screens light and
 * dark; the kid shortcut bar per tier, on a phone and a tablet. The shell itself: `ShellScreenshotTest`. Every
 * variant also passes the accessibility rule.
 */
@Config(application = Application::class) // Not the Hilt app: its startup opens the encrypted database.
class AppScreenshotTest : ScreenshotTest(checkAccessibility = true) {
    private val kids = KidsUiState.Ready(
        listOf(
            KidRow(KidId("k1"), "Mia", AgeBand.AGES_5_TO_7, KidUiTier.SEEDLING),
            KidRow(KidId("k2"), "Leo", AgeBand.AGES_8_TO_11, KidUiTier.SPROUT),
            KidRow(KidId("k3"), "Zoe", AgeBand.AGES_12_PLUS, KidUiTier.SAPLING),
        ),
    )

    @Test
    fun `kid picker`() = screenshot.captureLightDark("app_kid_picker", screen = true) {
        KidPickerDialog(state = kids, purpose = KidPickPurpose.KID_MODE, onPick = {}, onDismiss = {})
    }

    @Test
    fun `kid picker empty`() = screenshot.capture("app_kid_picker_empty", screen = true) {
        KidPickerDialog(
            state = KidsUiState.Ready(emptyList()),
            purpose = KidPickPurpose.SHOP,
            onPick = {},
            onDismiss = {},
        )
    }

    @Test
    fun `sign in again, checking`() = screenshot.captureLightDark("app_sign_in_again_checking") {
        SignInAgainScreen(GateState.Checking, onEvent = {})
    }

    @Test
    fun `sign in again, unreachable`() = screenshot.capture("app_sign_in_again_unreachable") {
        SignInAgainScreen(GateState.Unreachable, onEvent = {})
    }

    @Test
    fun `sign in again, google`() = screenshot.captureLightDark("app_sign_in_again_google") {
        SignInAgainScreen(GateState.SignInRequired(emailPassword = false), onEvent = {})
    }

    @Test
    fun `sign in again, wrong account`() = screenshot.capture("app_sign_in_again_wrong_account") {
        SignInAgainScreen(
            GateState.SignInRequired(emailPassword = false, failure = GateFailure.WRONG_ACCOUNT),
            onEvent = {},
        )
    }

    // The PIN field of the erase dialog sits in a dialog window, which never goes idle under Robolectric with a text
    // field (D-M7-27): the golden shows the dialog of a phone without a parent PIN.
    @Test
    fun `erase this phone's copy`() = screenshot.captureLightDark("app_erase_dialog", screen = true) {
        SignInAgainScreen(GateState.Unreachable, onEvent = {})
        EraseConfirmDialog(EraseConfirmState(unsyncedChanges = 3, needsPin = false), onEvent = {})
    }

    @Test
    fun `removed, wiping`() = screenshot.captureLightDark("app_removed_wiping") {
        RemovedScreen(RemovalReason.DEVICE_REVOKED, RemovedProgress.WIPING, onEvent = {})
    }

    @Test
    fun `removed, wiped`() = screenshot.capture("app_removed_wiped") {
        RemovedScreen(RemovalReason.HOUSEHOLD_GONE, RemovedProgress.WIPED, onEvent = {})
    }

    @Test
    fun `removed, failed`() = screenshot.capture("app_removed_failed") {
        RemovedScreen(RemovalReason.SIGNED_OUT, RemovedProgress.FAILED, onEvent = {})
    }

    @Test
    fun `startup error`() = screenshot.captureLightDark("app_startup_error") {
        StartupErrorScreen(StartupFailure.DATABASE, onRetry = {})
    }

    // Seedling's giant Chat, Screen time and Games buttons; Sprout and Sapling have only Screen time left (M9 §5.1).
    @Test
    fun `kid shortcut bar`() = screenshot.captureWidthClassTiers("app_kid_shortcuts") { _, tier ->
        WithShortcuts(kidShortcuts(tier, chat = true, onChat = {}, onScreenTime = {}, onGames = {})) {
            HomePlaceholder()
        }
    }
}

@androidx.compose.runtime.Composable
private fun HomePlaceholder() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Home", style = MaterialTheme.typography.titleLarge)
    }
}
