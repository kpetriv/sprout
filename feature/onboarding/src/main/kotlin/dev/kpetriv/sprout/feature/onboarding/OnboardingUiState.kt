package dev.kpetriv.sprout.feature.onboarding

import android.content.Context
import androidx.compose.runtime.Immutable
import dev.kpetriv.sprout.core.domainledger.Currency
import kotlin.time.Instant

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

    /**
     * A second parent device waits for an existing parent device to approve [fingerprint]; [recovery] is the "Don't
     * have your other phone?" section (ADR-0013 §3), null where it is not shown.
     */
    data class WaitingForApproval(val fingerprint: String, val recovery: RecoveryUi? = null) : OnboardingStep

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

/** Restoring this account from the waiting screen (ADR-0013 §3). */
sealed interface RecoveryUi {
    /** `recoveryStatus` is running. */
    data object Loading : RecoveryUi

    /** `recoveryStatus` failed ([failure]): "Try again". */
    data class Unavailable(val failure: OnboardingFailure) : RecoveryUi

    /** Another parent of the household has a working phone: approve there, no restore here. */
    data object OtherParent : RecoveryUi

    /**
     * The ways to restore. [pinAvailable]: "Restore with your Sprout PIN" ([attemptsLeft] tries); [pinUnsupported]: a
     * check value exists that this phone cannot compute; [showWait]: the wait part is shown (always without the PIN
     * path or once the wait has started, otherwise after "Forgot your PIN?", [waitExpanded]): "Start the 7-day wait"
     * while [waitEndsAt] is null, then the date and time, and once [waitOver] "Restore this account". The wait is
     * always offered: collapsed under the PIN way while that is available. [confirming] is the
     * confirmation dialog for a method, [pinEntry] the PIN pad, [restoring] a call running, [wrongPinAttemptsLeft]
     * the tries left after the last wrong PIN, [failure] the last other failure, and [signInNeeded] asks the screen
     * to start the Google sign-in again ([OnboardingEvent.RecoverySignIn]).
     */
    data class Ready(
        val pinAvailable: Boolean,
        val attemptsLeft: Int,
        val waitEndsAt: Instant?,
        val waitOver: Boolean,
        val pinUnsupported: Boolean = false,
        val waitExpanded: Boolean = false,
        val confirming: RecoveryChoice? = null,
        val pinEntry: RecoveryPinEntry? = null,
        val restoring: Boolean = false,
        val wrongPinAttemptsLeft: Int? = null,
        val failure: OnboardingFailure? = null,
        val signInNeeded: Boolean = false,
    ) : RecoveryUi {
        val showWait: Boolean get() = !pinAvailable || waitEndsAt != null || waitExpanded
    }
}

/** How to restore: with the PIN at once, or without it once the wait is over. */
enum class RecoveryChoice { PIN, WAIT }

/** The recovery PIN pad: how many digits are typed (never which), and a shake counter. */
data class RecoveryPinEntry(val enteredDigits: Int = 0, val rejections: Int = 0) {
    val length: Int get() = RECOVERY_PIN_LENGTH
}

/** The parent PIN length (mirrors `feature/lock`'s `PinRules.LENGTH`, which onboarding cannot depend on). */
const val RECOVERY_PIN_LENGTH: Int = 6

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

    /** Load the recovery options again after a failure. */
    data object RetryRecoveryStatus : OnboardingEvent

    /** "Restore with your Sprout PIN" or "Restore this account": the confirmation for [choice]. */
    data class ChooseRecovery(val choice: RecoveryChoice) : OnboardingEvent

    /** "Forgot your PIN?": show the wait text. */
    data object ShowRecoveryWait : OnboardingEvent

    /** "Start the 7-day wait": asks the server to start it (no restore yet). */
    data object StartRecoveryWait : OnboardingEvent

    /** The waiting screen is shown again (resumed): whether the wait is over is read from the clock again. */
    data object RecoveryResumed : OnboardingEvent

    /** "Restore" in the confirmation: the PIN pad (PIN), or the restore itself (wait). */
    data object ConfirmRecovery : OnboardingEvent

    /** "Cancel" in the confirmation or on the PIN pad. */
    data object CancelRecovery : OnboardingEvent

    data class RecoveryPinDigit(val digit: Char) : OnboardingEvent {
        override fun toString(): String = "RecoveryPinDigit(<redacted>)"
    }

    data object RecoveryPinBackspace : OnboardingEvent

    /** The screen's answer to [RecoveryUi.Ready.signInNeeded]: [activityContext] hosts the account picker. */
    class RecoverySignIn(val activityContext: Context) : OnboardingEvent
}
