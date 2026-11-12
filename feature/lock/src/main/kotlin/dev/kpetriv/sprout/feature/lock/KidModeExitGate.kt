package dev.kpetriv.sprout.feature.lock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kpetriv.sprout.core.designsystem.components.SproutButton
import dev.kpetriv.sprout.core.designsystem.components.SproutButtonStyle
import dev.kpetriv.sprout.core.designsystem.components.SproutCard
import dev.kpetriv.sprout.core.designsystem.theme.SproutSpacing

/**
 * A small dialog asking for the parent PIN (or fingerprint) before leaving kid mode. Show it when the kid-mode
 * "exit" control is used; [onExitConfirmed] runs once the parent has unlocked, [onDismiss] when cancelled.
 *
 * Opening the gate locks the session first, so an unlocked session left over from before kid mode never lets a
 * kid straight out. The dialog window is `FLAG_SECURE`. On first run (no PIN yet) it asks for a new PIN instead.
 */
@Composable
fun KidModeExitGate(onDismiss: () -> Unit, onExitConfirmed: () -> Unit) {
    val gateViewModel: ParentGateViewModel = hiltViewModel()
    val gate by gateViewModel.state.collectAsStateWithLifecycle()
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        gateViewModel.onEvent(ParentGateEvent.Lock)
        armed = true
    }
    if (armed && gate.session is ParentSessionState.Unlocked) {
        LaunchedEffect(Unit) { onExitConfirmed() }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn, usePlatformDefaultWidth = false),
    ) {
        SecureWindow()
        KidModeExitGateCard(onDismiss = onDismiss) {
            if (gate.session == ParentSessionState.SetupRequired) {
                PinSetupRoute()
            } else {
                KidModeExitPinEntry()
            }
        }
    }
}

/**
 * The gate's card, a white `SproutCard` (M9): [content] (PIN setup, or the title and PIN entry) above Cancel.
 * Stateless, for goldens.
 */
@Composable
internal fun KidModeExitGateCard(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    SproutCard(contentPadding = PaddingValues(SproutSpacing.lg)) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(SproutSpacing.md),
        ) {
            content()
            SproutButton(
                text = stringResource(R.string.feature_lock_cancel),
                onClick = onDismiss,
                style = SproutButtonStyle.Tertiary,
            )
        }
    }
}

/** "Exit kid mode?" over the PIN pad, as [KidModeExitPinEntry] shows it for [state]. */
@Composable
internal fun KidModeExitPinContent(
    state: PinEntryUiState,
    onEvent: (PinEntryEvent) -> Unit,
    onUseBiometric: (() -> Unit)?,
) {
    Text(
        text = stringResource(R.string.feature_lock_exit_kid_mode_title),
        style = MaterialTheme.typography.headlineSmall,
        textAlign = TextAlign.Center,
        modifier = Modifier.semantics { heading() },
    )
    PinEntryBody(
        state = state,
        onEvent = onEvent,
        onUseBiometric = onUseBiometric,
        keySize = 56.dp,
        allowRenewal = false,
    )
}

@Composable
private fun KidModeExitPinEntry() {
    val viewModel: PinEntryViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val prompter = rememberBiometricUnlockPrompter()
    LaunchedEffect(viewModel) { viewModel.onEvent(PinEntryEvent.Shown) }
    KidModeExitPinContent(
        state = state,
        onEvent = viewModel::onEvent,
        onUseBiometric = prompter?.let { { viewModel.onEvent(PinEntryEvent.UseBiometric(it)) } },
    )
}
