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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kpetriv.sprout.core.crypto.ParentAuthAvailability
import dev.kpetriv.sprout.core.designsystem.theme.SproutTheme
import dev.kpetriv.sprout.core.ui.PinDots
import dev.kpetriv.sprout.core.ui.PinPad

/** First-run PIN setup backed by a [PinSetupViewModel]. */
@Composable
fun PinSetupRoute(modifier: Modifier = Modifier) {
    val viewModel: PinSetupViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    PinSetupScreen(state = state, onEvent = viewModel::onEvent, modifier = modifier)
}

/**
 * Choose a parent PIN, then confirm it, with the PIN rules listed and, without a usable strong biometric, the
 * D-M2-1 signature banner. `FLAG_SECURE` while shown.
 */
@Composable
fun PinSetupScreen(state: PinSetupUiState, onEvent: (PinSetupEvent) -> Unit, modifier: Modifier = Modifier) {
    SecureWindow()
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            val confirming = state.step == PinSetupStep.CONFIRM
            Text(
                text = stringResource(
                    if (confirming) R.string.feature_lock_setup_confirm_title else R.string.feature_lock_setup_title,
                ),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            PinDots(entered = state.enteredDigits, length = state.pinLength, shakeKey = state.rejections)
            Text(
                text = state.error?.let { stringResource(it.messageRes()) } ?: "",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .heightIn(min = 24.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
            if (!confirming) {
                PinRuleHints(state.error)
                // D-M2-1: say up front that money changes from this device will not carry a parent signature.
                ParentSignaturePolicyBanner(availability = state.signingAvailability)
            }
            PinPad(
                onDigit = { onEvent(PinSetupEvent.Digit(it)) },
                onBackspace = { onEvent(PinSetupEvent.Backspace) },
                enabled = state.inputEnabled,
            )
            if (confirming) {
                TextButton(onClick = { onEvent(PinSetupEvent.StartOver) }, enabled = state.inputEnabled) {
                    Text(stringResource(R.string.feature_lock_setup_start_over))
                }
            }
        }
    }
}

@Composable
private fun PinRuleHints(error: PinSetupError?) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        RuleHint(stringResource(R.string.feature_lock_rule_length), broken = false)
        RuleHint(stringResource(R.string.feature_lock_rule_not_same), broken = error == PinSetupError.ALL_SAME)
        RuleHint(stringResource(R.string.feature_lock_rule_not_sequence), broken = error == PinSetupError.SEQUENCE)
    }
}

@Composable
private fun RuleHint(text: String, broken: Boolean) {
    Text(
        text = "• $text",
        style = MaterialTheme.typography.bodyMedium,
        color = if (broken) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun PinSetupError.messageRes(): Int = when (this) {
    PinSetupError.ALL_SAME -> R.string.feature_lock_setup_error_all_same
    PinSetupError.SEQUENCE -> R.string.feature_lock_setup_error_sequence
    PinSetupError.MISMATCH -> R.string.feature_lock_setup_error_mismatch
    PinSetupError.FAILED -> R.string.feature_lock_setup_error_failed
}

@Preview
@Composable
private fun PinSetupScreenPreview() {
    SproutTheme {
        PinSetupScreen(state = PinSetupUiState(enteredDigits = 3), onEvent = {})
    }
}

@Preview
@Composable
private fun PinSetupScreenUnsignedPreview() {
    SproutTheme {
        PinSetupScreen(
            state = PinSetupUiState(signingAvailability = ParentAuthAvailability.NO_HARDWARE),
            onEvent = {},
        )
    }
}

@Preview
@Composable
private fun PinSetupScreenMismatchPreview() {
    SproutTheme {
        PinSetupScreen(state = PinSetupUiState(error = PinSetupError.MISMATCH), onEvent = {})
    }
}
