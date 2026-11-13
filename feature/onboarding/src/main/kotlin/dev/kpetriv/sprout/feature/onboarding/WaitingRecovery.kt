package dev.kpetriv.sprout.feature.onboarding

import dev.kpetriv.sprout.core.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Instant

/**
 * The "Don't have your other phone?" part of the waiting screen (ADR-0013 §3), owned by [OnboardingViewModel], which
 * keeps the approval polling running alongside:
 *
 * - [load]: `recoveryStatus` → [RecoveryUi.Ready] (or [RecoveryUi.Unavailable] with "Try again", or
 *   [RecoveryUi.OtherParent]).
 * - **With the PIN**: "Restore with your Sprout PIN" → the confirmation → the PIN pad; six digits derive the check
 *   value with the recorded parameters and call `recoverParentDevice`. A wrong PIN shakes the pad and says how many
 *   tries are left; none left, or no check value, replaces the PIN path with the wait.
 * - **By waiting**: "Start the 7-day wait" asks the server to start it (the first `WAIT` call, answered with
 *   `wait` and the end date, which is not an error); from that date "Restore this account" → the confirmation → the
 *   call.
 * - `recent-sign-in`: the screen starts the Google sign-in again ([RecoveryUi.Ready.signInNeeded], answered with
 *   [OnboardingEvent.RecoverySignIn]) and the call is retried once with the same check value.
 * - Success → [onRecovered] with the PIN (to become this phone's PIN) or null; `not-pending` because this phone was
 *   approved meanwhile → [onRecovered] with null (the PIN was not checked); removed → [onRemoved].
 *
 * The PIN lives only in [digits] (a [CharArray], zeroed after every use) and, during one call, a copy that is zeroed
 * when the call ends; the check value is zeroed likewise. Nothing is logged.
 */
@Suppress("TooManyFunctions", "LongParameterList") // One function per event and answer; the view model's hooks.
internal class WaitingRecovery(
    private val scope: CoroutineScope,
    private val recovery: AccountRecovery,
    private val auth: OnboardingAuth,
    private val clock: Clock,
    private val current: () -> RecoveryUi?,
    private val update: ((RecoveryUi) -> RecoveryUi) -> Unit,
    private val onRecovered: suspend (pin: CharArray?, restored: RecoveredDevice?) -> Unit,
    private val onRemoved: suspend () -> Unit,
) {
    private val digits = CharArray(RECOVERY_PIN_LENGTH)
    private var count = 0
    private var job: Job? = null

    /** Shows "Restore this account" when the wait ends, without a reload. */
    private var waitTimer: Job? = null
    private var parameters: RecoveryPinParameters? = null

    /** Held between a `recent-sign-in` refusal and the retry after the new sign-in. */
    private var held: Held? = null

    private class Held(val verifier: ByteArray?, val pin: CharArray?) {
        fun wipe() {
            verifier?.fill(0)
            pin?.fill(ZERO_CHAR)
        }
    }

    /** Loads the options (again). */
    fun load() {
        cancel()
        update { RecoveryUi.Loading }
        job = scope.launch {
            val options = recovery.status()
            options.onSuccess { found ->
                parameters = found.pin
                val pinUsable = found.pin?.supported == true && found.attemptsLeft > 0
                update {
                    RecoveryUi.Ready(
                        pinAvailable = pinUsable,
                        attemptsLeft = found.attemptsLeft,
                        waitEndsAt = found.waitEndsAt,
                        waitOver = isOver(found.waitEndsAt),
                        pinUnsupported = found.pin != null && !found.pin.supported,
                    )
                }
                scheduleWaitEnd()
            }.onFailure { failure -> refused(failure, statusCall = true) }
        }
    }

    /** Handles one recovery event; false when it is not one. */
    @Suppress("CyclomaticComplexMethod") // One branch per event.
    fun onEvent(event: OnboardingEvent): Boolean {
        when (event) {
            OnboardingEvent.RetryRecoveryStatus -> if (current() is RecoveryUi.Unavailable) load()

            is OnboardingEvent.ChooseRecovery -> ready { state ->
                val allowed = when (event.choice) {
                    RecoveryChoice.PIN -> state.pinAvailable
                    RecoveryChoice.WAIT -> state.waitOver
                }
                if (allowed && idle(state)) state.copy(confirming = event.choice, failure = null) else state
            }

            OnboardingEvent.ShowRecoveryWait -> ready { it.copy(waitExpanded = true) }

            OnboardingEvent.RecoveryResumed -> ready { state -> state.copy(waitOver = isOver(state.waitEndsAt)) }
                .also { scheduleWaitEnd() }

            OnboardingEvent.StartRecoveryWait -> {
                val state = current() as? RecoveryUi.Ready
                if (state != null && idle(state) && state.waitEndsAt == null) start(pin = null)
            }

            OnboardingEvent.ConfirmRecovery -> confirm()

            OnboardingEvent.CancelRecovery -> close()

            is OnboardingEvent.RecoveryPinDigit -> addDigit(event.digit)

            OnboardingEvent.RecoveryPinBackspace -> removeDigit()

            is OnboardingEvent.RecoverySignIn -> signInAgain(event)

            else -> return false
        }
        return true
    }

    /**
     * Back on the waiting screen: closes the confirmation or the PIN pad and returns true; true as well while a call
     * runs (never left half-way). False when nothing is open: the waiting screen itself is left.
     */
    fun back(): Boolean {
        val state = current() as? RecoveryUi.Ready ?: return false
        return when {
            state.restoring || state.signInNeeded -> true

            state.confirming != null || state.pinEntry != null -> {
                close()
                true
            }

            else -> false
        }
    }

    /** Stops any call and forgets every PIN and check value (the screen is left, or the phone was approved). */
    fun cancel() {
        job?.cancel()
        job = null
        waitTimer?.cancel()
        waitTimer = null
        clearDigits()
        held?.wipe()
        held = null
    }

    private fun idle(state: RecoveryUi.Ready) = !state.restoring && !state.signInNeeded

    private fun isOver(endsAt: Instant?): Boolean = endsAt != null && clock.now() >= endsAt

    /**
     * Once the wait has started and is not over, "Restore this account" appears at its end without a reload (the
     * server decides: a phone whose clock is off only shows the button early or late).
     */
    private fun scheduleWaitEnd() {
        val state = current() as? RecoveryUi.Ready ?: return
        val endsAt = state.waitEndsAt
        waitTimer?.cancel()
        if (endsAt == null || state.waitOver) return
        waitTimer = scope.launch {
            delay(endsAt - clock.now())
            ready { it.copy(waitOver = true) }
        }
    }

    private fun ready(change: (RecoveryUi.Ready) -> RecoveryUi.Ready) = update { state ->
        if (state is RecoveryUi.Ready) change(state) else state
    }

    private fun confirm() {
        val state = current() as? RecoveryUi.Ready ?: return
        when (state.confirming) {
            RecoveryChoice.PIN -> {
                clearDigits()
                ready { it.copy(confirming = null, pinEntry = RecoveryPinEntry(), wrongPinAttemptsLeft = null) }
            }

            RecoveryChoice.WAIT -> {
                ready { it.copy(confirming = null) }
                start(pin = null)
            }

            null -> Unit
        }
    }

    private fun close() {
        val state = current() as? RecoveryUi.Ready ?: return
        if (!idle(state)) return
        clearDigits()
        ready { it.copy(confirming = null, pinEntry = null) }
    }

    /** The PIN pad while it takes digits (open, no call running), or null. */
    private fun openEntry(): RecoveryPinEntry? = (current() as? RecoveryUi.Ready)?.takeIf { idle(it) }?.pinEntry

    private fun addDigit(digit: Char) {
        val entry = openEntry() ?: return
        if (count >= RECOVERY_PIN_LENGTH || digit !in '0'..'9') return
        digits[count++] = digit
        ready { it.copy(pinEntry = entry.copy(enteredDigits = count), wrongPinAttemptsLeft = null, failure = null) }
        if (count == RECOVERY_PIN_LENGTH) {
            val pin = digits.copyOf()
            clearDigits()
            ready { it.copy(pinEntry = it.pinEntry?.copy(enteredDigits = 0)) }
            start(pin)
        }
    }

    private fun removeDigit() {
        val entry = openEntry() ?: return
        if (count == 0) return
        digits[--count] = ZERO_CHAR
        ready { it.copy(pinEntry = entry.copy(enteredDigits = count)) }
    }

    /** Derives the check value of [pin] (when given) and calls `recoverParentDevice`. Takes [pin]. */
    private fun start(pin: CharArray?) {
        ready { it.copy(restoring = true, failure = null) }
        job = scope.launch {
            var verifier: ByteArray? = null
            try {
                verifier = if (pin == null) null else derive(pin) ?: return@launch
                attempt(verifier, pin, retried = false)
            } finally {
                // A held attempt keeps its own copies until the retry; everything else is zeroed here.
                if (held?.pin !== pin) pin?.fill(ZERO_CHAR)
                if (held?.verifier !== verifier) verifier?.fill(0)
            }
        }
    }

    /** The check value of [pin], or null after showing why there is none. */
    private suspend fun derive(pin: CharArray): ByteArray? {
        val recorded = parameters
        if (recorded == null || !recorded.supported) {
            closePinPath(wrongAttemptsLeft = null)
            return null
        }
        return recovery.verifierFor(pin, recorded).getOrElse { failure ->
            if (failure is CancellationException) throw failure
            ready { it.copy(restoring = false, failure = OnboardingFailure.UNKNOWN) }
            null
        }
    }

    private suspend fun attempt(verifier: ByteArray?, pin: CharArray?, retried: Boolean) {
        val result = recovery.recover(verifier)
        val failure = result.exceptionOrNull()
        when {
            failure == null -> {
                // `restoring` stays set until the view model has taken over: the approval poll leaves this phone to
                // the restore while it is set (it must not cancel the hand-over).
                detach()
                onRecovered(pin?.copyOf(), result.getOrNull())
            }

            (failure as? RecoveryException)?.problem == RecoveryProblem.RecentSignIn && !retried -> {
                if (auth.usesEmailPassword) {
                    ready { it.copy(restoring = false, failure = OnboardingFailure.SIGN_IN_AGAIN) }
                } else {
                    held = Held(verifier, pin)
                    ready { it.copy(signInNeeded = true) }
                }
            }

            else -> refused(failure, statusCall = false)
        }
    }

    private fun signInAgain(event: OnboardingEvent.RecoverySignIn) {
        val state = current() as? RecoveryUi.Ready ?: return
        val retry = held
        if (!state.signInNeeded || retry == null) return
        held = null
        ready { it.copy(signInNeeded = false) }
        job = scope.launch {
            try {
                // The same account again, never another one: re-authentication, not a new sign-in.
                val signedIn = auth.reauthenticateWithGoogle(event.activityContext)
                val failure = signedIn.exceptionOrNull()
                if (failure != null) {
                    retry.wipe()
                    if (failure is CancellationException) throw failure
                    val reason = (failure as? OnboardingException)?.failure ?: OnboardingFailure.UNKNOWN
                    ready { it.copy(restoring = false, failure = reason) }
                } else {
                    attempt(retry.verifier, retry.pin, retried = true)
                }
            } finally {
                retry.wipe()
            }
        }
    }

    /** A refused or failed call ([statusCall]: `recoveryStatus`), shown or acted on. */
    @Suppress("CyclomaticComplexMethod") // One branch per refusal of the server.
    private suspend fun refused(failure: Throwable, statusCall: Boolean) {
        if (failure is CancellationException) throw failure
        when (val problem = (failure as? RecoveryException)?.problem) {
            is RecoveryProblem.WrongPin -> if (problem.attemptsLeft > 0) {
                ready {
                    it.copy(
                        restoring = false,
                        attemptsLeft = problem.attemptsLeft,
                        wrongPinAttemptsLeft = problem.attemptsLeft,
                        pinEntry = (it.pinEntry ?: RecoveryPinEntry()).let { entry ->
                            entry.copy(enteredDigits = 0, rejections = entry.rejections + 1)
                        },
                    )
                }
            } else {
                closePinPath(wrongAttemptsLeft = 0)
            }

            RecoveryProblem.PinUnavailable -> closePinPath(wrongAttemptsLeft = null)

            // The first WAIT call starts the wait; a later one says it is not over yet. Neither is an error.
            is RecoveryProblem.Wait -> ready {
                it.copy(
                    restoring = false,
                    waitEndsAt = problem.waitEndsAt,
                    waitOver = isOver(problem.waitEndsAt),
                )
            }.also { scheduleWaitEnd() }

            RecoveryProblem.OtherParent -> update { RecoveryUi.OtherParent }

            is RecoveryProblem.NotPending -> if (problem.active) {
                detach()
                onRecovered(null, null)
            } else {
                detach()
                onRemoved()
            }

            // A second recent-sign-in after signing in again, or anything without a reason.
            RecoveryProblem.RecentSignIn, null -> {
                val reason = when {
                    problem == RecoveryProblem.RecentSignIn -> OnboardingFailure.SIGN_IN_AGAIN
                    else -> (failure as? OnboardingException)?.failure ?: OnboardingFailure.UNKNOWN
                }
                if (statusCall) {
                    update { RecoveryUi.Unavailable(reason) }
                } else {
                    ready { it.copy(restoring = false, failure = reason) }
                }
            }
        }
    }

    /** The PIN path is closed: the wait text replaces it. */
    private fun closePinPath(wrongAttemptsLeft: Int?) {
        clearDigits()
        ready {
            it.copy(
                restoring = false,
                pinAvailable = false,
                attemptsLeft = 0,
                pinEntry = null,
                confirming = null,
                wrongPinAttemptsLeft = wrongAttemptsLeft,
            )
        }
    }

    /**
     * The running call hands over to the view model ([onRecovered], [onRemoved]), which may [cancel] this flow: that
     * must not cancel the call that is handing over.
     */
    private fun detach() {
        job = null
    }

    private fun clearDigits() {
        digits.fill(ZERO_CHAR)
        count = 0
    }

    private companion object {
        const val ZERO_CHAR = '\u0000'
    }
}
