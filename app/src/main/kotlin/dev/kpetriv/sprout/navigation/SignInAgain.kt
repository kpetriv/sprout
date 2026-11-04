package dev.kpetriv.sprout.navigation

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kpetriv.sprout.R
import dev.kpetriv.sprout.core.ui.contentWidth
import dev.kpetriv.sprout.sync.CredentialGate
import dev.kpetriv.sprout.sync.EraseConfirmation
import dev.kpetriv.sprout.sync.ErasePinCheck
import dev.kpetriv.sprout.sync.GateFailure
import dev.kpetriv.sprout.sync.GateState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the "Sign in again" gate can ask for. */
sealed interface SignInAgainEvent {
    /** [activityContext] hosts Credential Manager's account picker; it is not kept. */
    class Google(val activityContext: Context) : SignInAgainEvent

    /** Debug builds against the Auth emulator (D-M3-2). Never logged. */
    class EmailPassword(val email: String, val password: String) : SignInAgainEvent {
        override fun toString(): String = "EmailPassword(<redacted>)"
    }

    data object Retry : SignInAgainEvent

    /** "Erase this phone's copy instead": opens the confirmation ([EraseConfirmState]). */
    data object EraseInstead : SignInAgainEvent

    /** Confirms the erase; [pin] is the parent PIN when one is asked for (zeroed once checked). Never logged. */
    class ConfirmErase(val pin: CharArray? = null) : SignInAgainEvent {
        override fun toString(): String = "ConfirmErase(<redacted>)"
    }

    data object CancelErase : SignInAgainEvent

    data object DismissFailure : SignInAgainEvent
}

/**
 * The erase confirmation (audit L5): what erasing loses ([unsyncedChanges], null when it could not be counted) and,
 * when this phone has a parent PIN, the PIN ([needsPin]), checked by `feature/lock`.
 */
data class EraseConfirmState(
    val unsyncedChanges: Int?,
    val needsPin: Boolean,
    val busy: Boolean = false,
    val pinFailure: ErasePinCheck? = null,
)

/** The gate screen's own state; the gate itself is the [AppMode.SignInAgain] the shell already has. */
data class SignInAgainUiState(val erase: EraseConfirmState? = null)

/**
 * The gate's events over [CredentialGate], and the erase confirmation over [EraseConfirmation]. The confirmation is
 * not offered while the registry is being asked or a sign-in runs ([CredentialGate.canEraseInstead]), and the erase
 * happens only after it (and the PIN, when there is one) was confirmed.
 */
@HiltViewModel
class SignInAgainViewModel @Inject constructor(
    private val gate: CredentialGate,
    private val confirmation: EraseConfirmation,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SignInAgainUiState())
    val state: StateFlow<SignInAgainUiState> = mutableState.asStateFlow()

    fun onEvent(event: SignInAgainEvent) {
        when (event) {
            is SignInAgainEvent.Google -> gate.signInWithGoogle(event.activityContext)
            is SignInAgainEvent.EmailPassword -> gate.signInWithEmailPassword(event.email.trim(), event.password)
            SignInAgainEvent.Retry -> gate.retry()
            SignInAgainEvent.EraseInstead -> openErase()
            is SignInAgainEvent.ConfirmErase -> confirmErase(event.pin)
            SignInAgainEvent.CancelErase -> cancelErase()
            SignInAgainEvent.DismissFailure -> gate.dismissFailure()
        }
    }

    private fun openErase() {
        if (!gate.canEraseInstead() || mutableState.value.erase != null) return
        viewModelScope.launch {
            val unsynced = attempt { confirmation.unsyncedChanges() }
            val needsPin = attempt { confirmation.hasParentPin() } ?: true
            if (gate.canEraseInstead()) {
                mutableState.update { it.copy(erase = it.erase ?: EraseConfirmState(unsynced, needsPin)) }
            }
        }
    }

    private fun cancelErase() = mutableState.update { if (it.erase?.busy == true) it else it.copy(erase = null) }

    private fun confirmErase(pin: CharArray?) {
        val erase = mutableState.value.erase
        when {
            erase == null || erase.busy -> pin?.fill(ZERO)

            !gate.canEraseInstead() -> {
                pin?.fill(ZERO)
                mutableState.update { it.copy(erase = null) }
            }

            !erase.needsPin -> {
                pin?.fill(ZERO)
                mutableState.update { it.copy(erase = null) }
                gate.eraseInstead()
            }

            pin == null || pin.isEmpty() -> Unit

            else -> checkPinThenErase(erase, pin)
        }
    }

    private fun checkPinThenErase(erase: EraseConfirmState, pin: CharArray) {
        mutableState.update { it.copy(erase = erase.copy(busy = true, pinFailure = null)) }
        viewModelScope.launch {
            val check = try {
                attempt { confirmation.checkPin(pin) } ?: ErasePinCheck.UNAVAILABLE
            } finally {
                pin.fill(ZERO)
            }
            if (check == ErasePinCheck.CONFIRMED) {
                mutableState.update { it.copy(erase = null) }
                gate.eraseInstead()
            } else {
                mutableState.update { state ->
                    state.copy(erase = state.erase?.copy(busy = false, pinFailure = check))
                }
            }
        }
    }

    // A failure reads as "unknown"; dropped unlogged (it may carry personal data).
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private suspend fun <T> attempt(block: suspend () -> T): T? = try {
        block()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Exception) {
        null
    }

    private companion object {
        const val ZERO = '\u0000'
    }
}

@Composable
internal fun SignInAgainRoute(gate: GateState, modifier: Modifier = Modifier) {
    val viewModel: SignInAgainViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    SignInAgainScreen(gate, viewModel::onEvent, modifier)
    state.erase?.let { EraseConfirmDialog(it, viewModel::onEvent) }
}

/**
 * The "Sign in again" gate (audit H2), in the parent lock's style: a title, one line, the action. Shows no
 * household data. "Erase this phone's copy instead" is offered once the registry answered and while no sign-in runs;
 * it opens a confirmation first ([EraseConfirmDialog]).
 */
@Composable
internal fun SignInAgainScreen(gate: GateState, onEvent: (SignInAgainEvent) -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .contentWidth()
                .padding(24.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.gate_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            when (gate) {
                GateState.Checking -> {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.gate_checking), textAlign = TextAlign.Center)
                }

                GateState.Unreachable -> {
                    Text(stringResource(R.string.gate_unreachable), textAlign = TextAlign.Center)
                    Button(onClick = { onEvent(SignInAgainEvent.Retry) }) { Text(stringResource(R.string.gate_retry)) }
                }

                is GateState.SignInRequired -> SignInForm(gate, onEvent)
            }
            if (gate !is GateState.Checking) {
                TextButton(
                    onClick = { onEvent(SignInAgainEvent.EraseInstead) },
                    enabled = (gate as? GateState.SignInRequired)?.busy != true,
                ) { Text(stringResource(R.string.gate_erase), color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

/** "Erase this phone's copy?": what is lost, and the parent PIN when this phone has one. */
@Composable
internal fun EraseConfirmDialog(state: EraseConfirmState, onEvent: (SignInAgainEvent) -> Unit) {
    // Not saveable: a PIN never goes into saved instance state.
    var pin by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { onEvent(SignInAgainEvent.CancelErase) },
        title = { Text(stringResource(R.string.gate_erase_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.gate_erase_text))
                Text(
                    when (val count = state.unsyncedChanges) {
                        null -> stringResource(R.string.gate_erase_unsynced_unknown)
                        0 -> stringResource(R.string.gate_erase_unsynced_none)
                        else -> pluralStringResource(R.plurals.gate_erase_unsynced, count, count)
                    },
                    color = if (state.unsyncedChanges == 0) Color.Unspecified else MaterialTheme.colorScheme.error,
                )
                if (state.needsPin) {
                    OutlinedTextField(
                        value = pin,
                        onValueChange = { typed -> pin = typed.filter(Char::isDigit).take(PIN_MAX_LENGTH) },
                        label = { Text(stringResource(R.string.gate_erase_pin)) },
                        singleLine = true,
                        enabled = !state.busy,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                state.pinFailure?.let { failure ->
                    Text(stringResource(failure.text()), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onEvent(SignInAgainEvent.ConfirmErase(pin.toCharArray().takeIf { state.needsPin }))
                    pin = ""
                },
                enabled = !state.busy && (!state.needsPin || pin.isNotEmpty()),
            ) { Text(stringResource(R.string.gate_erase_confirm), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = {
            TextButton(onClick = { onEvent(SignInAgainEvent.CancelErase) }, enabled = !state.busy) {
                Text(stringResource(R.string.gate_erase_cancel))
            }
        },
    )
}

private const val PIN_MAX_LENGTH = 12

private fun ErasePinCheck.text(): Int = when (this) {
    ErasePinCheck.WRONG -> R.string.gate_erase_wrong_pin
    ErasePinCheck.LOCKED_OUT -> R.string.gate_erase_locked_out
    ErasePinCheck.UNAVAILABLE, ErasePinCheck.CONFIRMED -> R.string.gate_erase_unavailable
}

@Composable
private fun SignInForm(gate: GateState.SignInRequired, onEvent: (SignInAgainEvent) -> Unit) {
    Text(stringResource(R.string.gate_text), textAlign = TextAlign.Center)
    gate.failure?.let { failure ->
        Text(stringResource(failure.text()), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
    }
    if (gate.emailPassword) {
        var email by rememberSaveable { mutableStateOf("") }
        // Not saveable: a password never goes into saved instance state.
        var password by remember { mutableStateOf("") }
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text(stringResource(R.string.gate_email)) },
            singleLine = true,
            enabled = !gate.busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(stringResource(R.string.gate_password)) },
            singleLine = true,
            enabled = !gate.busy,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { onEvent(SignInAgainEvent.EmailPassword(email, password)) },
            enabled = !gate.busy && email.contains('@') && password.isNotEmpty(),
        ) { Text(stringResource(R.string.gate_sign_in)) }
    } else {
        val context = LocalContext.current
        Button(onClick = { onEvent(SignInAgainEvent.Google(context)) }, enabled = !gate.busy) {
            Text(stringResource(R.string.gate_sign_in_google))
        }
    }
    if (gate.busy) CircularProgressIndicator()
}

private fun GateFailure.text(): Int = when (this) {
    GateFailure.WRONG_ACCOUNT -> R.string.gate_failure_wrong_account
    GateFailure.INVALID_CREDENTIAL -> R.string.gate_failure_invalid
    GateFailure.CANCELLED -> R.string.gate_failure_cancelled
    GateFailure.NETWORK -> R.string.gate_failure_network
    GateFailure.FAILED -> R.string.gate_failure_failed
}
