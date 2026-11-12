package dev.kpetriv.sprout.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kpetriv.sprout.core.ui.PinDots
import dev.kpetriv.sprout.core.ui.PinPad
import dev.kpetriv.sprout.core.ui.contentWidth

/**
 * Turns on fingerprint signing for a parent who already has a PIN, backed by a [SigningSetupViewModel]. Calls
 * [onDone] once nothing is left to do. Show it only inside the unlocked parent area (the app wraps it in
 * `RequireParent`, which also keeps the window `FLAG_SECURE`).
 */
@Composable
fun SigningSetupRoute(onDone: () -> Unit, modifier: Modifier = Modifier) {
    val viewModel: SigningSetupViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.done) { if (state.done) onDone() }
    SigningSetupScreen(state, viewModel::onEvent, modifier)
}

@Composable
fun SigningSetupScreen(
    state: SigningSetupUiState,
    onEvent: (SigningSetupEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (state.checking || state.done) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Surface
        }
        Column(
            modifier = Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .contentWidth()
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                stringResource(R.string.feature_onboarding_signing_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                stringResource(R.string.feature_onboarding_signing_detail),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            PinDots(entered = state.enteredDigits, length = state.pinLength, shakeKey = state.rejections)
            Text(
                text = state.error?.let { stringResource(it.messageRes()) }.orEmpty(),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.heightIn(min = 24.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
            if (state.busy) CircularProgressIndicator()
            PinPad(
                onDigit = { onEvent(SigningSetupEvent.Digit(it)) },
                onBackspace = { onEvent(SigningSetupEvent.Backspace) },
                enabled = state.inputEnabled,
            )
            TextButton(onClick = { onEvent(SigningSetupEvent.Skip) }, enabled = !state.busy) {
                Text(stringResource(R.string.feature_onboarding_signing_later))
            }
        }
    }
}

private fun SigningSetupResult.messageRes(): Int = when (this) {
    SigningSetupResult.WRONG_PIN -> R.string.feature_onboarding_signing_wrong_pin

    SigningSetupResult.LOCKED_OUT -> R.string.feature_onboarding_signing_locked_out

    SigningSetupResult.CANCELLED -> R.string.feature_onboarding_signing_cancelled

    SigningSetupResult.POLICY_NOT_SYNCED -> R.string.feature_onboarding_signing_policy_not_synced

    SigningSetupResult.REGISTERED, SigningSetupResult.UNAVAILABLE, SigningSetupResult.FAILED ->
        R.string.feature_onboarding_signing_failed
}
