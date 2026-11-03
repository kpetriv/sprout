package dev.kpetriv.sprout.feature.onboarding

import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.testing.screenshot.ScreenshotTest
import org.junit.Test

/** Goldens in src/test/screenshots; see docs/SCREENSHOT_TESTS.md. */
class OnboardingScreenshotTest : ScreenshotTest() {
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
    fun `trial data purge`() = screenshot.captureLightDark("onboarding_purge") {
        OnboardingScreen(OnboardingUiState(step = OnboardingStep.ConfirmPurge(forKidDevice = false)), onEvent = {})
    }
}
