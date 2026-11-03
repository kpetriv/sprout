package dev.kpetriv.sprout.feature.lock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kpetriv.sprout.core.designsystem.theme.SproutTheme
import dev.kpetriv.sprout.core.ui.PinDots
import dev.kpetriv.sprout.core.ui.PinPad

/** PIN entry backed by a [PinEntryViewModel]; shows the fingerprint option when this activity can prompt. */
@Composable
fun PinEntryRoute(modifier: Modifier = Modifier) {
    val viewModel: PinEntryViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val prompter = rememberBiometricUnlockPrompter()
    LaunchedEffect(viewModel) { viewModel.onEvent(PinEntryEvent.Shown) }
    PinEntryScreen(
        state = state,
        onEvent = viewModel::onEvent,
        onUseBiometric = prompter?.let { { viewModel.onEvent(PinEntryEvent.UseBiometric(it)) } },
        modifier = modifier,
    )
}

/**
 * The parent PIN screen: six dots, a large keypad, the lockout countdown or the fingerprint-only message,
 * "Use fingerprint" when [onUseBiometric] is set and the parent key can sign, and "Renew fingerprint key" when it
 * needs renewal. `FLAG_SECURE` while shown.
 */
@Composable
fun PinEntryScreen(
    state: PinEntryUiState,
    onEvent: (PinEntryEvent) -> Unit,
    modifier: Modifier = Modifier,
    onUseBiometric: (() -> Unit)? = null,
) {
    SecureWindow()
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Text(
                text = stringResource(R.string.feature_lock_entry_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            PinEntryBody(state = state, onEvent = onEvent, onUseBiometric = onUseBiometric, keySize = 72.dp)
        }
    }
}

/**
 * Dots, status line and keypad; shared by [PinEntryScreen] and [KidModeExitGate]. [allowRenewal] offers "Renew
 * fingerprint key"; only the parent gate does, never the kid-mode exit gate.
 */
@Composable
internal fun PinEntryBody(
    state: PinEntryUiState,
    onEvent: (PinEntryEvent) -> Unit,
    onUseBiometric: (() -> Unit)?,
    keySize: Dp,
    modifier: Modifier = Modifier,
    allowRenewal: Boolean = true,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        PinDots(entered = state.enteredDigits, length = state.pinLength, shakeKey = state.wrongAttempts)
        PinEntryStatus(state)
        val showBiometric = onUseBiometric != null && state.biometricAvailable
        PinPad(
            onDigit = { onEvent(PinEntryEvent.Digit(it)) },
            onBackspace = { onEvent(PinEntryEvent.Backspace) },
            enabled = state.inputEnabled,
            keySize = keySize,
        )
        if (showBiometric) {
            TextButton(onClick = { onUseBiometric?.invoke() }, enabled = !state.isVerifying) {
                Text(stringResource(R.string.feature_lock_use_fingerprint))
            }
        }
        if (allowRenewal && state.renewalOffered && !state.renewOnUnlock) {
            TextButton(onClick = { onEvent(PinEntryEvent.RenewSigningKey) }, enabled = !state.isVerifying) {
                Text(stringResource(R.string.feature_lock_renew_key))
            }
        }
    }
}

@Composable
private fun PinEntryStatus(state: PinEntryUiState) {
    val lockout = state.lockout
    val text = when {
        lockout is LockoutUi.Wait -> stringResource(
            R.string.feature_lock_wait,
            formatCountdown(lockout.remainingSeconds),
        )

        lockout == LockoutUi.RequireBiometric -> if (state.biometricAvailable) {
            stringResource(R.string.feature_lock_require_biometric)
        } else {
            stringResource(R.string.feature_lock_require_biometric_unavailable)
        }

        state.error != null -> stringResource(state.error.messageRes())

        state.renewOnUnlock -> stringResource(R.string.feature_lock_renew_hint)

        else -> null
    }
    val isError = state.error != null || lockout != LockoutUi.None
    Text(
        text = text ?: stringResource(R.string.feature_lock_entry_hint),
        style = MaterialTheme.typography.bodyLarge,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .heightIn(min = 48.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

private fun PinEntryError.messageRes(): Int = when (this) {
    PinEntryError.WRONG_PIN -> R.string.feature_lock_error_wrong_pin
    PinEntryError.UNAVAILABLE -> R.string.feature_lock_error_unavailable
    PinEntryError.BIOMETRIC_FAILED -> R.string.feature_lock_error_biometric_failed
    PinEntryError.BIOMETRIC_KEY_CHANGED -> R.string.feature_lock_error_biometric_key_changed
}

private const val SECONDS_PER_MINUTE = 60

/** `m:ss`, e.g. `0:30` or `12:05`. */
internal fun formatCountdown(totalSeconds: Long): String {
    val minutes = totalSeconds / SECONDS_PER_MINUTE
    val seconds = totalSeconds % SECONDS_PER_MINUTE
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}

@Preview
@Composable
private fun PinEntryScreenPreview() {
    SproutTheme {
        PinEntryScreen(state = PinEntryUiState(enteredDigits = 2, biometricAvailable = true), onEvent = {
        }, onUseBiometric = {})
    }
}

@Preview
@Composable
private fun PinEntryScreenLockedOutPreview() {
    SproutTheme {
        PinEntryScreen(
            state = PinEntryUiState(lockout = LockoutUi.Wait(remainingSeconds = 30), error = PinEntryError.WRONG_PIN),
            onEvent = {},
        )
    }
}
