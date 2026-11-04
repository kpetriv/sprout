package dev.kpetriv.sprout.feature.lock

import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.kpetriv.sprout.core.crypto.ParentAuthAvailability
import dev.kpetriv.sprout.core.testing.screenshot.ScreenshotTest
import org.junit.Test

/**
 * Golden screenshots of the lock screens (parent sizing), in src/test/screenshots. Re-record with
 * `./gradlew :feature:lock:recordRoborazziDebug`.
 */
class LockScreenshotTest : ScreenshotTest(checkAccessibility = true) {
    @Test
    fun `pin entry`() = screenshot.captureLightDark("lock_pin_entry") {
        PinEntryScreen(
            state = PinEntryUiState(enteredDigits = 2, biometricAvailable = true),
            onEvent = {},
            onUseBiometric = {},
        )
    }

    @Test
    fun `pin entry, locked out`() = screenshot.captureLightDark("lock_pin_entry_wait") {
        PinEntryScreen(
            state = PinEntryUiState(lockout = LockoutUi.Wait(remainingSeconds = 30), error = PinEntryError.WRONG_PIN),
            onEvent = {},
        )
    }

    @Test
    fun `pin entry, fingerprint required`() = screenshot.captureLightDark("lock_pin_entry_require_biometric") {
        PinEntryScreen(
            state = PinEntryUiState(lockout = LockoutUi.RequireBiometric, biometricAvailable = true),
            onEvent = {},
            onUseBiometric = {},
        )
    }

    @Test
    fun `pin entry, fingerprint changed`() = screenshot.captureLightDark("lock_pin_entry_key_changed") {
        PinEntryScreen(
            state = PinEntryUiState(renewalOffered = true, error = PinEntryError.BIOMETRIC_KEY_CHANGED),
            onEvent = {},
            onUseBiometric = {},
        )
    }

    @Test
    fun `kid mode exit gate`() = screenshot.captureLightDark("lock_exit_gate") {
        KidModeExitGateCard(onDismiss = {}) {
            KidModeExitPinContent(
                state = PinEntryUiState(enteredDigits = 3, biometricAvailable = true),
                onEvent = {},
                onUseBiometric = {},
            )
        }
    }

    @Test
    fun `kid mode exit gate, wrong pin`() = screenshot.captureLightDark("lock_exit_gate_wrong_pin") {
        KidModeExitGateCard(onDismiss = {}) {
            KidModeExitPinContent(
                state = PinEntryUiState(error = PinEntryError.WRONG_PIN),
                onEvent = {},
                onUseBiometric = null,
            )
        }
    }

    @Test
    fun `pin setup`() = screenshot.captureLightDark("lock_pin_setup") {
        PinSetupScreen(state = PinSetupUiState(enteredDigits = 4), onEvent = {})
    }

    @Test
    fun `pin setup, mismatch`() = screenshot.captureLightDark("lock_pin_setup_mismatch") {
        PinSetupScreen(state = PinSetupUiState(error = PinSetupError.MISMATCH, rejections = 1), onEvent = {})
    }

    @Test
    fun `pin setup, no strong biometric`() = screenshot.captureLightDark("lock_pin_setup_unsigned") {
        PinSetupScreen(
            state = PinSetupUiState(signingAvailability = ParentAuthAvailability.NOT_ENROLLED),
            onEvent = {},
        )
    }

    @Test
    fun `signature policy banner`() = screenshot.captureLightDark("lock_signature_banner") {
        ParentSignaturePolicyBanner(
            availability = ParentAuthAvailability.NOT_ENROLLED,
            modifier = Modifier.padding(16.dp),
        )
    }
}
