package dev.kpetriv.sprout.feature.settings

import dev.kpetriv.sprout.core.crypto.ParentAuthAvailability
import dev.kpetriv.sprout.core.testing.screenshot.ScreenshotTest
import org.junit.Test
import org.robolectric.annotation.Config

/** Goldens in src/test/screenshots; see docs/SCREENSHOT_TESTS.md. */
class SettingsScreenshotTest : ScreenshotTest() {
    @Test
    fun hub() = screenshot.captureLightDark("settings_hub") {
        SettingsScreen(onOpen = {}, onBack = {})
    }

    @Test
    fun interest() = screenshot.captureLightDark("settings_interest") {
        InterestSettingsScreen(SettingsPreviewData.interest(), onEvent = {}, onBack = {})
    }

    @Test
    fun `interest, rate too high`() = screenshot.captureLightDark("settings_interest_too_high") {
        InterestSettingsScreen(SettingsPreviewData.interest("21"), onEvent = {}, onBack = {})
    }

    @Test
    @Config(qualifiers = "+h1100dp") // The PIN form sits below the signing card.
    fun `parent security, signing on`() = screenshot.captureLightDark("settings_security_signing_on") {
        ParentSecurityScreen(SettingsPreviewData.security(), onEvent = {}, onBack = {})
    }

    @Test
    @Config(qualifiers = "+h1100dp")
    fun `parent security, no strong biometric (D-M2-1)`() =
        screenshot.captureLightDark("settings_security_unavailable") {
            ParentSecurityScreen(
                SettingsPreviewData.security(
                    availability = ParentAuthAvailability.NOT_ENROLLED,
                    hasParentKey = false,
                    pinError = PinChangeError.Mismatch,
                ),
                onEvent = {},
                onBack = {},
            )
        }

    @Test
    @Config(qualifiers = "+h1100dp")
    fun `parent security, signing key not registered with the household (D-M3-39)`() =
        screenshot.captureLightDark("settings_security_key_not_shared") {
            ParentSecurityScreen(
                SettingsPreviewData.security(keyNotRegisteredWithHousehold = true),
                onEvent = {},
                onBack = {},
            )
        }

    @Test
    @Config(qualifiers = "+h1100dp")
    fun `parent security, fingerprint changed`() = screenshot.captureLightDark("settings_security_key_changed") {
        ParentSecurityScreen(
            SettingsPreviewData.security(
                keyNeedsRenewal = true,
                signingPin = SigningPinUi(SigningPinPurpose.RENEW, enteredDigits = 2),
            ),
            onEvent = {},
            onBack = {},
        )
    }
}
