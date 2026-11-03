package dev.kpetriv.sprout.feature.onboarding

import android.content.Context
import androidx.compose.runtime.Immutable
import dev.kpetriv.sprout.core.domainledger.Currency

/**
 * The onboarding flow (M3 §6) as one state: the [step] shown, whether a call is running ([busy]), the last
 * [failure] (until dismissed or the next attempt), and [finished] once the device is set up (the app then leaves
 * onboarding). [toString] of the steps that hold typed text never shows it.
 */
@Immutable
data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.Welcome,
    val busy: Boolean = false,
    val failure: OnboardingFailure? = null,
    val finished: Boolean = false,
)

sealed interface OnboardingStep {
    /** "I'm a parent" or "This is a kid's device". */
    data object Welcome : OnboardingStep

    /**
     * Parent sign-in: "Continue with Google" in release builds, e-mail and password against the Auth emulator in
     * debug builds ([emailPassword], D-M3-2).
     */
    data class ParentSignIn(val emailPassword: Boolean, val email: String = "", val password: String = "") :
        OnboardingStep {
        val canSubmit: Boolean get() = !emailPassword || (email.contains('@') && password.length >= MIN_PASSWORD)

        override fun toString(): String = "ParentSignIn(emailPassword=$emailPassword, <redacted>)"
    }

    /** A new household: its money currency. */
    data class CreateHousehold(val currency: Currency.Fiat, val options: List<Currency.Fiat>) : OnboardingStep

    /** Trial data exists: "it stays on this phone until you continue; it will be deleted". */
    data class ConfirmPurge(val forKidDevice: Boolean) : OnboardingStep

    /** A second parent device waits for an existing parent device to approve [fingerprint]. */
    data class WaitingForApproval(val fingerprint: String) : OnboardingStep

    /** The kid device's link code, as typed so far (normalised). */
    data class KidCode(val code: String = "") : OnboardingStep {
        val complete: Boolean get() = LinkCodeInput.parse(code) != null

        override fun toString(): String = "KidCode(length=${code.length})"
    }

    /** Parent PIN and fingerprint signing (the app's `feature/lock` step). */
    data object ParentLock : OnboardingStep

    /** The kid device's first sync before it opens kid mode. */
    data object FirstSync : OnboardingStep

    companion object {
        /** Firebase's minimum password length. */
        const val MIN_PASSWORD: Int = 6
    }
}

sealed interface OnboardingEvent {
    data object ChooseParent : OnboardingEvent

    data object ChooseKidDevice : OnboardingEvent

    /** Back from any step: to the welcome screen (signing out of a half-finished sign-in). */
    data object Back : OnboardingEvent

    data class EmailChanged(val email: String) : OnboardingEvent {
        override fun toString(): String = "EmailChanged(<redacted>)"
    }

    data class PasswordChanged(val password: String) : OnboardingEvent {
        override fun toString(): String = "PasswordChanged(<redacted>)"
    }

    data object SignInWithEmail : OnboardingEvent

    /** [activityContext] hosts Credential Manager's account picker; it is not kept. */
    class SignInWithGoogle(val activityContext: Context) : OnboardingEvent

    data class CurrencyChosen(val currency: Currency.Fiat) : OnboardingEvent

    data object CreateHousehold : OnboardingEvent

    /** "Continue" on the trial-data screen. */
    data object ConfirmPurge : OnboardingEvent

    data class CodeChanged(val typed: String) : OnboardingEvent {
        override fun toString(): String = "CodeChanged(<redacted>)"
    }

    data object SubmitCode : OnboardingEvent

    /** The app's PIN and signing step finished. */
    data object ParentLockDone : OnboardingEvent

    data object DismissFailure : OnboardingEvent
}
