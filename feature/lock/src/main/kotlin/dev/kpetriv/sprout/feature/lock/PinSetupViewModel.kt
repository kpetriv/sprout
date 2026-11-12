package dev.kpetriv.sprout.feature.lock

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kpetriv.sprout.core.crypto.ParentAuthAvailability
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class PinSetupStep {
    ENTER,
    CONFIRM,
}

enum class PinSetupError {
    ALL_SAME,
    SEQUENCE,

    /** The confirmation did not match; setup starts over. */
    MISMATCH,

    /** Saving failed; setup starts over. */
    FAILED,
}

@Immutable
data class PinSetupUiState(
    val step: PinSetupStep = PinSetupStep.ENTER,
    val enteredDigits: Int = 0,
    val pinLength: Int = PinRules.LENGTH,
    val isSaving: Boolean = false,
    val error: PinSetupError? = null,
    /** Increments on every rejected PIN; the dots shake when it changes. */
    val rejections: Int = 0,
    /** Anything but [ParentAuthAvailability.STRONG_BIOMETRIC_AVAILABLE] shows the D-M2-1 signature banner. */
    val signingAvailability: ParentAuthAvailability = ParentAuthAvailability.STRONG_BIOMETRIC_AVAILABLE,
) {
    val inputEnabled: Boolean get() = !isSaving
}

sealed interface PinSetupEvent {
    data class Digit(val digit: Char) : PinSetupEvent

    data object Backspace : PinSetupEvent

    /** Back to the first step, forgetting the first PIN. */
    data object StartOver : PinSetupEvent

    /**
     * Reads the strong-biometric availability again (the screen sends it whenever it resumes, as
     * [ParentGateEvent.RefreshAvailability]): a fingerprint enrolled in system settings meanwhile hides the banner.
     */
    data object RefreshAvailability : PinSetupEvent
}

/**
 * First-run PIN setup: enter (checked against [PinRules] as soon as six digits are in), then confirm, then
 * [ParentSession.setupPin], which unlocks the session. Both PINs live in private arrays zeroed after use.
 *
 * Fingerprint signing is on from the start when possible (audit M1): once the PIN is saved, a copy of it goes to
 * [ParentKeyEnrolment.enrolInBackground], which re-verifies it, creates and proves the parent key with one
 * fingerprint prompt and registers it, so the household requires parent signatures from the first money event.
 * Without a usable strong biometric it records the D-M2-1 state instead, and this screen shows the banner, re-checked
 * on every [PinSetupEvent.RefreshAvailability].
 */
@HiltViewModel
class PinSetupViewModel @Inject constructor(
    private val session: ParentSession,
    private val enrolment: ParentKeyEnrolment,
) : ViewModel() {
    private val digits = CharArray(PinRules.LENGTH)
    private var count = 0
    private var firstPin: CharArray? = null

    private val mutableState = MutableStateFlow(PinSetupUiState(signingAvailability = session.biometricAvailability()))
    val state: StateFlow<PinSetupUiState> = mutableState.asStateFlow()

    fun onEvent(event: PinSetupEvent) {
        when (event) {
            is PinSetupEvent.Digit -> addDigit(event.digit)

            PinSetupEvent.Backspace -> removeDigit()

            PinSetupEvent.StartOver -> startOver(error = null)

            PinSetupEvent.RefreshAvailability -> mutableState.update {
                it.copy(signingAvailability = session.biometricAvailability())
            }
        }
    }

    private fun addDigit(digit: Char) {
        if (!mutableState.value.inputEnabled || count >= PinRules.LENGTH || digit !in '0'..'9') return
        digits[count++] = digit
        mutableState.update { it.copy(enteredDigits = count, error = null) }
        if (count == PinRules.LENGTH) complete()
    }

    private fun removeDigit() {
        if (!mutableState.value.inputEnabled || count == 0) return
        digits[--count] = ZERO_CHAR
        mutableState.update { it.copy(enteredDigits = count) }
    }

    private fun complete() {
        val entered = digits.copyOf()
        clearDigits()
        val first = firstPin
        if (first == null) acceptFirst(entered) else confirm(first, entered)
    }

    private fun acceptFirst(pin: CharArray) {
        val error = when (PinRules.validate(pin)) {
            null -> null

            PinRuleViolation.SEQUENCE -> PinSetupError.SEQUENCE

            // Only digits can be typed and the length is fixed, so anything else is "all the same".
            else -> PinSetupError.ALL_SAME
        }
        if (error == null) {
            firstPin = pin
            mutableState.update { it.copy(step = PinSetupStep.CONFIRM, enteredDigits = 0) }
        } else {
            pin.fill(ZERO_CHAR)
            mutableState.update { it.copy(enteredDigits = 0, error = error, rejections = it.rejections + 1) }
        }
    }

    private fun confirm(first: CharArray, confirmation: CharArray) {
        firstPin = null
        mutableState.update { it.copy(isSaving = true) }
        // setupPin zeroes both arrays; enrolment gets its own copy (and zeroes it).
        val enrolmentPin = first.copyOf()
        viewModelScope.launch {
            val result = try {
                session.setupPin(first, confirmation)
            } catch (e: CancellationException) {
                enrolmentPin.fill(ZERO_CHAR)
                throw e
            }
            if (result ==
                PinSetupResult.Saved
            ) {
                enrolment.enrolInBackground(enrolmentPin)
            } else {
                enrolmentPin.fill(ZERO_CHAR)
            }
            when (result) {
                PinSetupResult.Saved, PinSetupResult.NotAllowed -> mutableState.update {
                    it.copy(isSaving = false, enteredDigits = 0)
                }

                PinSetupResult.Mismatch -> startOver(PinSetupError.MISMATCH)

                PinSetupResult.Failed -> startOver(PinSetupError.FAILED)

                is PinSetupResult.Invalid -> startOver(
                    if (result.violation ==
                        PinRuleViolation.SEQUENCE
                    ) {
                        PinSetupError.SEQUENCE
                    } else {
                        PinSetupError.ALL_SAME
                    },
                )
            }
        }
    }

    private fun startOver(error: PinSetupError?) {
        firstPin?.fill(ZERO_CHAR)
        firstPin = null
        clearDigits()
        mutableState.update {
            PinSetupUiState(
                error = error,
                rejections = if (error == null) it.rejections else it.rejections + 1,
                signingAvailability = it.signingAvailability,
            )
        }
    }

    private fun clearDigits() {
        digits.fill(ZERO_CHAR)
        count = 0
    }

    override fun onCleared() {
        firstPin?.fill(ZERO_CHAR)
        clearDigits()
    }

    private companion object {
        const val ZERO_CHAR = '\u0000'
    }
}
