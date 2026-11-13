package dev.kpetriv.sprout.feature.onboarding

import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.testing.screenshot.ScreenshotTest
import org.junit.Test
import org.robolectric.annotation.Config
import kotlin.time.Instant

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

    // ---- account recovery on the waiting screen (ADR-0013 §3) ----

    private fun waiting(recovery: RecoveryUi) = OnboardingUiState(
        step = OnboardingStep.WaitingForApproval(
            displayFingerprint(DeviceId("3fa9" + "c01d" + "77e2" + "b4a8" + "0".repeat(48))),
            recovery,
        ),
    )

    private fun ready(
        pinAvailable: Boolean = true,
        waitEndsAt: Instant? = null,
        waitOver: Boolean = false,
        waitExpanded: Boolean = false,
    ) = RecoveryUi.Ready(
        pinAvailable = pinAvailable,
        attemptsLeft = if (pinAvailable) 10 else 0,
        waitEndsAt = waitEndsAt,
        waitOver = waitOver,
        waitExpanded = waitExpanded,
    )

    @Test
    @Config(qualifiers = "+h1400dp")
    fun `waiting, recovery loading`() = screenshot.captureLightDark("onboarding_recovery_loading") {
        OnboardingScreen(waiting(RecoveryUi.Loading), onEvent = {})
    }

    @Test
    @Config(qualifiers = "+h1400dp")
    fun `waiting, recovery options could not load`() = screenshot.captureLightDark("onboarding_recovery_offline") {
        OnboardingScreen(waiting(RecoveryUi.Unavailable(OnboardingFailure.NETWORK)), onEvent = {})
    }

    @Test
    @Config(qualifiers = "+h1400dp")
    fun `waiting, restore with the PIN`() = screenshot.captureLightDark("onboarding_recovery_pin") {
        OnboardingScreen(waiting(ready()), onEvent = {})
    }

    @Test
    @Config(qualifiers = "+h1400dp")
    fun `waiting, the 7-day wait not started`() = screenshot.captureLightDark("onboarding_recovery_wait_start") {
        OnboardingScreen(waiting(ready(pinAvailable = true, waitExpanded = true)), onEvent = {})
    }

    @Test
    @Config(qualifiers = "+h1400dp")
    fun `waiting, the wait running`() = screenshot.captureLightDark("onboarding_recovery_wait_date") {
        OnboardingScreen(waiting(ready(pinAvailable = false, waitEndsAt = WAIT_END)), onEvent = {})
    }

    @Test
    @Config(qualifiers = "+h1400dp")
    fun `waiting, the wait over`() = screenshot.captureLightDark("onboarding_recovery_wait_over") {
        OnboardingScreen(waiting(ready(pinAvailable = false, waitEndsAt = WAIT_END, waitOver = true)), onEvent = {})
    }

    @Test
    @Config(qualifiers = "+h1400dp")
    fun `waiting, PIN path closed after ten wrong PINs`() = screenshot.captureLightDark("onboarding_recovery_closed") {
        OnboardingScreen(
            waiting(ready(pinAvailable = false).copy(wrongPinAttemptsLeft = 0)),
            onEvent = {},
        )
    }

    @Test
    @Config(qualifiers = "+h1400dp")
    fun `waiting, another parent can approve`() = screenshot.captureLightDark("onboarding_recovery_other_parent") {
        OnboardingScreen(waiting(RecoveryUi.OtherParent), onEvent = {})
    }

    @Test
    fun `recovery PIN pad, wrong PIN`() = screenshot.captureLightDark("onboarding_recovery_pin_wrong") {
        OnboardingScreen(
            waiting(
                ready().copy(
                    pinEntry = RecoveryPinEntry(enteredDigits = 2, rejections = 1),
                    wrongPinAttemptsLeft = 7,
                ),
            ),
            onEvent = {},
        )
    }

    @Test
    @Config(qualifiers = "+h1400dp")
    fun `waiting, another Google account picked`() = screenshot.captureLightDark("onboarding_recovery_wrong_account") {
        OnboardingScreen(waiting(ready().copy(failure = OnboardingFailure.WRONG_ACCOUNT)), onEvent = {})
    }

    @Test
    fun `recovery confirmation`() = screenshot.captureLightDark("onboarding_recovery_confirm") {
        RecoveryConfirmCard(onEvent = {})
    }

    private companion object {
        /** Noon UTC, so the date is the same in every time zone the tests run in. */
        val WAIT_END: Instant = Instant.parse("2026-11-20T12:00:00Z")
    }
}
