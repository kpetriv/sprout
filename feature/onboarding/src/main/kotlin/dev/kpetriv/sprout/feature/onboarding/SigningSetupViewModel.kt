package dev.kpetriv.sprout.feature.onboarding

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The fingerprint-signing step for a parent who already has a PIN (from the trial): the purge removed the trial's
 * parent key with the trial household (D-M3-26), so the parent proves the PIN once more and registers a parent key
 * for the real household. [done] is set when there is nothing to do, when it is registered, when this phone has no
 * usable strong biometric (D-M2-1), or when the parent chose to do it later in Settings.
 */
@Immutable
data class SigningSetupUiState(
    val checking: Boolean = true,
    val enteredDigits: Int = 0,
    val pinLength: Int = PIN_LENGTH,
    val busy: Boolean = false,
    val error: SigningSetupResult? = null,
    /** Increments on every rejected PIN; the dots shake when it changes. */
    val rejections: Int = 0,
    val done: Boolean = false,
) {
    val inputEnabled: Boolean get() = !checking && !busy && !done

    companion object {
        const val PIN_LENGTH: Int = 6
    }
}

sealed interface SigningSetupEvent {
    data class Digit(val digit: Char) : SigningSetupEvent

    data object Backspace : SigningSetupEvent

    /** "Later": finish without signing; Settings → Parent security offers it again. */
    data object Skip : SigningSetupEvent
}

@HiltViewModel
class SigningSetupViewModel @Inject constructor(private val setup: ParentSigningSetup) : ViewModel() {
    private val digits = CharArray(SigningSetupUiState.PIN_LENGTH)
    private var count = 0

    private val mutableState = MutableStateFlow(SigningSetupUiState())
    val state: StateFlow<SigningSetupUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            val needed = try {
                setup.needsEnrolment()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") failure: Exception) {
                // Unknown: offer it; enrolment itself re-checks everything.
                true
            }
            mutableState.update { it.copy(checking = false, done = !needed) }
        }
    }

    fun onEvent(event: SigningSetupEvent) {
        when (event) {
            is SigningSetupEvent.Digit -> addDigit(event.digit)

            SigningSetupEvent.Backspace -> removeDigit()

            SigningSetupEvent.Skip -> {
                clearDigits()
                mutableState.update { it.copy(done = true, enteredDigits = 0) }
            }
        }
    }

    private fun addDigit(digit: Char) {
        if (!mutableState.value.inputEnabled || count >= digits.size || digit !in '0'..'9') return
        digits[count++] = digit
        mutableState.update { it.copy(enteredDigits = count, error = null) }
        if (count == digits.size) submit()
    }

    private fun removeDigit() {
        if (!mutableState.value.inputEnabled || count == 0) return
        digits[--count] = ZERO_CHAR
        mutableState.update { it.copy(enteredDigits = count) }
    }

    private fun submit() {
        val pin = digits.copyOf()
        clearDigits()
        mutableState.update { it.copy(busy = true, enteredDigits = 0) }
        viewModelScope.launch {
            // enrol zeroes the PIN.
            val result = setup.enrol(pin)
            mutableState.update {
                when (result) {
                    SigningSetupResult.REGISTERED, SigningSetupResult.UNAVAILABLE ->
                        it.copy(busy = false, done = true, error = null)

                    else -> it.copy(busy = false, error = result, rejections = it.rejections + 1)
                }
            }
        }
    }

    private fun clearDigits() {
        digits.fill(ZERO_CHAR)
        count = 0
    }

    override fun onCleared() = clearDigits()

    private companion object {
        const val ZERO_CHAR = '\u0000'
    }
}
