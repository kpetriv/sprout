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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kpetriv.sprout.R
import dev.kpetriv.sprout.sync.CredentialGate
import dev.kpetriv.sprout.sync.GateFailure
import dev.kpetriv.sprout.sync.GateState
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

    data object EraseInstead : SignInAgainEvent

    data object DismissFailure : SignInAgainEvent
}

/** The gate's events over [CredentialGate]; its state is the [AppMode.SignInAgain] the shell already has. */
@HiltViewModel
class SignInAgainViewModel @Inject constructor(private val gate: CredentialGate) : ViewModel() {
    fun onEvent(event: SignInAgainEvent) {
        when (event) {
            is SignInAgainEvent.Google -> gate.signInWithGoogle(event.activityContext)
            is SignInAgainEvent.EmailPassword -> gate.signInWithEmailPassword(event.email.trim(), event.password)
            SignInAgainEvent.Retry -> gate.retry()
            SignInAgainEvent.EraseInstead -> gate.eraseInstead()
            SignInAgainEvent.DismissFailure -> gate.dismissFailure()
        }
    }
}

@Composable
internal fun SignInAgainRoute(gate: GateState, modifier: Modifier = Modifier) {
    val viewModel: SignInAgainViewModel = hiltViewModel()
    SignInAgainScreen(gate, viewModel::onEvent, modifier)
}

/**
 * The "Sign in again" gate (audit H2), in the parent lock's style: a title, one line, the action. Shows no
 * household data. "Erase this phone's copy instead" is always offered once the registry answered.
 */
@Composable
internal fun SignInAgainScreen(gate: GateState, onEvent: (SignInAgainEvent) -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.gate_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
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
