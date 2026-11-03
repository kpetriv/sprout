package dev.kpetriv.sprout.feature.lock

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kpetriv.sprout.core.crypto.LockoutDecision
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** What the PIN entry screen shows. Never holds the PIN, only how many digits were typed. */
@Immutable
data class PinEntryUiState(
    val enteredDigits: Int = 0,
    val pinLength: Int = PinRules.LENGTH,
    val isVerifying: Boolean = false,
    val lockout: LockoutUi = LockoutUi.None,
    val error: PinEntryError? = null,
    /**
     * Show "Use fingerprint": a strong biometric is usable and this device's parent key is the household's
     * registered one ([ParentKeyStatus.READY]).
     */
    val biometricAvailable: Boolean = false,
    /** Increments on every wrong PIN; the dots shake when it changes. */
    val wrongAttempts: Int = 0,
    /** The parent key needs renewal (fingerprint changed): show "Renew fingerprint key". */
    val renewalOffered: Boolean = false,
    /** The parent chose renewal: the next correct PIN unlocks and then renews the key ([ParentKeyRenewal]). */
    val renewOnUnlock: Boolean = false,
) {
    val inputEnabled: Boolean get() = !isVerifying && lockout == LockoutUi.None
}

/** The lockout as the screen shows it. */
sealed interface LockoutUi {
    data object None : LockoutUi

    /** Counting down; [remainingSeconds] is rounded up. */
    data class Wait(val remainingSeconds: Long) : LockoutUi

    /** The PIN path is closed until a fingerprint unlock. */
    data object RequireBiometric : LockoutUi
}

enum class PinEntryError {
    WRONG_PIN,

    /** The stored PIN or lockout state cannot be read. */
    UNAVAILABLE,

    /** The fingerprint prompt failed or its result did not verify. */
    BIOMETRIC_FAILED,

    /** Fingerprints changed on this device; the parent key must be renewed after a PIN unlock. */
    BIOMETRIC_KEY_CHANGED,
}

sealed interface PinEntryEvent {
    data class Digit(val digit: Char) : PinEntryEvent

    data object Backspace : PinEntryEvent

    /** "Use fingerprint": [prompter] shows the prompt over the current activity. */
    data class UseBiometric(val prompter: BiometricUnlockPrompter) : PinEntryEvent

    /** "Renew fingerprint key": renew the parent key with the PIN typed next. */
    data object RenewSigningKey : PinEntryEvent

    /**
     * The screen is shown again: re-read the parent key status. The ViewModel outlives a single lock (it is shared
     * by the parent gate and the kid-mode exit gate), and the key may have been renewed or invalidated meanwhile.
     */
    data object Shown : PinEntryEvent
}

/**
 * PIN entry for [ParentSession]: collects digits, submits at [PinRules.LENGTH], and shows the lockout from
 * [LockoutDecision] (counting a [LockoutDecision.Wait] down, then asking the policy again). The typed digits stay
 * in a private array that is zeroed after every submit, a fingerprint unlock, [PinEntryEvent.Shown], and when the
 * ViewModel is cleared.
 *
 * The fingerprint is offered only when the parent key can sign for the household ([ParentSession.parentKeyStatus]).
 * When it needs renewal (fingerprint changed, or the fingerprint attempt reported
 * [BiometricUnlockRequest.KeyInvalidated]) the screen offers "Renew fingerprint key": the next correct PIN unlocks
 * and starts [ParentKeyRenewal.renewInBackground] with a copy of the same PIN.
 */
@HiltViewModel
class PinEntryViewModel @Inject constructor(
    private val session: ParentSession,
    private val renewal: ParentKeyRenewal,
) : ViewModel() {
    private val digits = CharArray(PinRules.LENGTH)
    private var count = 0
    private var countdown: Job? = null

    private val mutableState = MutableStateFlow(PinEntryUiState())
    val state: StateFlow<PinEntryUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            refreshKeyStatus()
            applyDecision(session.lockoutDecision())
        }
    }

    /** Whether to offer the fingerprint or a renewal, from the parent key's current status. */
    private suspend fun refreshKeyStatus() {
        val status = session.parentKeyStatus()
        mutableState.update {
            it.copy(
                biometricAvailable = status == ParentKeyStatus.READY,
                renewalOffered = status == ParentKeyStatus.NEEDS_RENEWAL,
                // A renewal chosen earlier only stands while one is still needed.
                renewOnUnlock = it.renewOnUnlock && status == ParentKeyStatus.NEEDS_RENEWAL,
            )
        }
    }

    fun onEvent(event: PinEntryEvent) {
        when (event) {
            is PinEntryEvent.Digit -> addDigit(event.digit)

            PinEntryEvent.Backspace -> removeDigit()

            is PinEntryEvent.UseBiometric -> unlockWithBiometric(event.prompter)

            PinEntryEvent.RenewSigningKey -> mutableState.update {
                if (it.renewalOffered) it.copy(renewOnUnlock = true, error = null) else it
            }

            PinEntryEvent.Shown -> onShown()
        }
    }

    /**
     * A new gate starts empty: digits typed at an earlier gate (the ViewModel is shared by the parent gate and the
     * kid-mode exit gate) and an earlier renewal choice never carry over. A running verify already holds its own copy
     * of the PIN, so clearing here never touches it.
     */
    private fun onShown() {
        clearDigits()
        mutableState.update { it.copy(enteredDigits = 0, renewOnUnlock = false, error = null) }
        if (!mutableState.value.isVerifying) {
            viewModelScope.launch { refreshKeyStatus() }
        }
    }

    private fun addDigit(digit: Char) {
        if (!mutableState.value.inputEnabled || count >= PinRules.LENGTH || digit !in '0'..'9') return
        digits[count++] = digit
        mutableState.update { it.copy(enteredDigits = count, error = null) }
        if (count == PinRules.LENGTH) submit()
    }

    private fun removeDigit() {
        if (!mutableState.value.inputEnabled || count == 0) return
        digits[--count] = ZERO_CHAR
        mutableState.update { it.copy(enteredDigits = count) }
    }

    private fun submit() {
        val pin = digits.copyOf()
        // unlockWithPin zeroes its array; renewal needs its own copy of the same PIN (zeroed by the renewal).
        val renewalPin = if (mutableState.value.renewOnUnlock) pin.copyOf() else null
        clearDigits()
        mutableState.update { it.copy(isVerifying = true) }
        viewModelScope.launch {
            val result = try {
                session.unlockWithPin(pin)
            } catch (e: CancellationException) {
                renewalPin?.fill(ZERO_CHAR)
                throw e
            }
            mutableState.update { it.copy(isVerifying = false, enteredDigits = 0) }
            when {
                renewalPin == null -> Unit

                result == PinUnlockResult.Unlocked -> {
                    // One renewal per choice: the next unlock must not renew (and prompt) again. The status is read
                    // again once the renewal has finished, so the next lock offers the fingerprint.
                    mutableState.update { it.copy(renewOnUnlock = false, renewalOffered = false) }
                    val job = renewal.renewInBackground(renewalPin)
                    viewModelScope.launch {
                        job.join()
                        refreshKeyStatus()
                    }
                }

                else -> renewalPin.fill(ZERO_CHAR)
            }
            when (result) {
                PinUnlockResult.Unlocked, PinUnlockResult.SetupRequired -> Unit

                is PinUnlockResult.WrongPin -> {
                    mutableState.update {
                        it.copy(error = PinEntryError.WRONG_PIN, wrongAttempts = it.wrongAttempts + 1)
                    }
                    applyDecision(result.next)
                }

                is PinUnlockResult.LockedOut -> applyDecision(result.decision)

                PinUnlockResult.Unavailable -> mutableState.update { it.copy(error = PinEntryError.UNAVAILABLE) }
            }
        }
    }

    private fun unlockWithBiometric(prompter: BiometricUnlockPrompter) {
        if (mutableState.value.isVerifying) return
        viewModelScope.launch {
            val error = when (val request = session.unlockWithBiometric()) {
                is BiometricUnlockRequest.Ready -> promptAndComplete(prompter, request)

                BiometricUnlockRequest.KeyInvalidated -> {
                    mutableState.update { it.copy(biometricAvailable = false, renewalOffered = true) }
                    PinEntryError.BIOMETRIC_KEY_CHANGED
                }

                is BiometricUnlockRequest.Unavailable, BiometricUnlockRequest.NotEnrolled -> {
                    mutableState.update { it.copy(biometricAvailable = false) }
                    null
                }

                BiometricUnlockRequest.Failed -> PinEntryError.BIOMETRIC_FAILED

                BiometricUnlockRequest.SetupRequired -> null
            }
            mutableState.update { it.copy(error = error) }
        }
    }

    /** Returns the error to show, or null on success or a dismissed prompt. */
    private suspend fun promptAndComplete(
        prompter: BiometricUnlockPrompter,
        request: BiometricUnlockRequest.Ready,
    ): PinEntryError? {
        val signature = try {
            prompter.prompt(request)
        } catch (e: CancellationException) {
            throw e
        } catch (expected: BiometricErrorException) {
            return PinEntryError.BIOMETRIC_FAILED
        }
        return when {
            signature == null -> null

            session.completeBiometricUnlock(request, signature) == BiometricUnlockResult.UNLOCKED -> {
                countdown?.cancel()
                // Digits typed before the fingerprint must not pre-fill the next gate.
                clearDigits()
                mutableState.update { it.copy(lockout = LockoutUi.None, enteredDigits = 0) }
                null
            }

            else -> PinEntryError.BIOMETRIC_FAILED
        }
    }

    private fun applyDecision(decision: LockoutDecision?) {
        countdown?.cancel()
        when (decision) {
            null -> mutableState.update { it.copy(error = PinEntryError.UNAVAILABLE) }
            LockoutDecision.Allowed -> mutableState.update { it.copy(lockout = LockoutUi.None) }
            LockoutDecision.RequireBiometric -> mutableState.update { it.copy(lockout = LockoutUi.RequireBiometric) }
            is LockoutDecision.Wait -> countdown = viewModelScope.launch { countDown(decision.remaining) }
        }
    }

    private suspend fun countDown(total: Duration) {
        var remaining = total
        while (remaining.isPositive()) {
            mutableState.update { it.copy(lockout = LockoutUi.Wait(remaining.roundUpToSeconds())) }
            val step = minOf(remaining, TICK)
            delay(step)
            remaining -= step
        }
        mutableState.update { it.copy(lockout = LockoutUi.None) }
        // Ask the policy again: it is the authority (a reboot, for example, restarts the wait).
        applyDecision(session.lockoutDecision())
    }

    private fun clearDigits() {
        digits.fill(ZERO_CHAR)
        count = 0
    }

    override fun onCleared() {
        clearDigits()
    }

    private companion object {
        const val ZERO_CHAR = '\u0000'
        val TICK = 1.seconds
        const val MILLIS_PER_SECOND = 1_000L

        fun Duration.roundUpToSeconds(): Long = (inWholeMilliseconds + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND
    }
}
