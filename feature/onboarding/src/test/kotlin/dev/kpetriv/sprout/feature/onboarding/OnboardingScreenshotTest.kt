package dev.kpetriv.sprout.feature.onboarding

import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.testing.screenshot.ScreenshotTest
import org.junit.Test

/** Goldens in src/test/screenshots; see docs/SCREENSHOT_TESTS.md. */
class OnboardingScreenshotTest : ScreenshotTest(checkAccessibility = true) {
    @Test
    fun welcome() = screenshot.captureLightDark("onboarding_welcome") {
        OnboardingScreen(OnboardingUiState(), onEvent = {}, onNotNow = {})
    }

    @Test
    fun `parent sign-in, debug build against the emulator`() = screenshot.captureLightDark("onboarding_sign_in_debug") {
        OnboardingScreen(
            OnboardingUiState(
                step = OnboardingStep.ParentSignIn(
                    emailPassword = true,
                    email = "parent@example.test",
                    password = "secret1",
                ),
                failure = OnboardingFailure.INVALID_CREDENTIAL,
            ),
            onEvent = {},
        )
    }

    @Test
    fun `code entry, empty`() = screenshot.captureLightDark("onboarding_code_empty") {
        OnboardingScreen(OnboardingUiState(step = OnboardingStep.KidCode()), onEvent = {})
    }

    @Test
    fun `code entry, refused`() = screenshot.captureLightDark("onboarding_code_error") {
        OnboardingScreen(
            OnboardingUiState(step = OnboardingStep.KidCode("K7QM2X9P"), failure = OnboardingFailure.CODE_NOT_ACCEPTED),
            onEvent = {},
        )
    }

    @Test
    fun `waiting for approval`() = screenshot.captureLightDark("onboarding_waiting") {
        OnboardingScreen(
            OnboardingUiState(
                step = OnboardingStep.WaitingForApproval(
                    displayFingerprint(DeviceId("3fa9" + "c01d" + "77e2" + "b4a8" + "0".repeat(48))),
                ),
            ),
            onEvent = {},
        )
    }

    @Test
    fun `create the household`() = screenshot.captureLightDark("onboarding_household") {
        val currencies = listOf("GBP", "EUR", "USD").map { Currency.Fiat(it) }
        OnboardingScreen(
            OnboardingUiState(step = OnboardingStep.CreateHousehold(currencies.first(), currencies)),
            onEvent = {},
        )
    }

    // Linking a kid's phone: the code half typed.
    @Test
    fun `kid phone, code half typed`() = screenshot.captureLightDark("onboarding_kid_add") {
        OnboardingScreen(OnboardingUiState(step = OnboardingStep.KidCode("K7QM")), onEvent = {})
    }

    @Test
    fun `signing key setup`() = screenshot.captureLightDark("onboarding_signing_setup") {
        SigningSetupScreen(state = SigningSetupUiState(checking = false, enteredDigits = 2), onEvent = {})
    }

    @Test
    fun `signing key setup, wrong pin`() = screenshot.captureLightDark("onboarding_signing_setup_wrong_pin") {
        SigningSetupScreen(
            state = SigningSetupUiState(checking = false, error = SigningSetupResult.WRONG_PIN, rejections = 1),
            onEvent = {},
        )
    }

    @Test
    fun `trial data purge`() = screenshot.captureLightDark("onboarding_purge") {
        OnboardingScreen(OnboardingUiState(step = OnboardingStep.ConfirmPurge(forKidDevice = false)), onEvent = {})
    }
}
