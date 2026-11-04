package dev.kpetriv.sprout.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.ui.contentWidth

@Composable
internal fun LinkDeviceRoute(kidId: KidId, kidName: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val viewModel: LinkDeviceViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(kidId) { viewModel.onEvent(LinkDeviceEvent.Start(kidId, kidName)) }
    LinkDeviceScreen(state, viewModel::onEvent, onBack, modifier)
}

/** Settings → Kids → kid → "Link a device": the code in two groups of four with its countdown. */
@Composable
fun LinkDeviceScreen(
    state: LinkDeviceUiState,
    onEvent: (LinkDeviceEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsScaffold(
        title = stringResource(R.string.feature_settings_link_title, state.kidName),
        onBack = onBack,
        modifier = modifier,
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .contentWidth()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.feature_settings_link_steps, state.kidName),
                style = MaterialTheme.typography.bodyLarge,
            )
            val code = state.groupedCode
            when {
                state.loading -> CircularProgressIndicator()

                code != null && !state.expired -> {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = code,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 40.sp,
                            letterSpacing = 4.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(vertical = 32.dp),
                        )
                    }
                    Text(
                        stringResource(R.string.feature_settings_link_expires, countdown(state.remainingSeconds)),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }

                else -> {
                    val colors = MaterialTheme.colorScheme
                    Text(
                        text = stringResource(linkProblemRes(state)),
                        color = if (state.expired) colors.onSurface else colors.error,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Button(onClick = { onEvent(LinkDeviceEvent.NewCode) }) {
                        Text(stringResource(R.string.feature_settings_link_new))
                    }
                }
            }
            Text(stringResource(R.string.feature_settings_link_single_use), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun linkProblemRes(state: LinkDeviceUiState): Int = when {
    state.expired -> R.string.feature_settings_link_expired
    state.failure == AdminFailure.RATE_LIMITED -> R.string.feature_settings_link_rate_limited
    state.failure == AdminFailure.NETWORK -> R.string.feature_settings_link_network
    else -> R.string.feature_settings_link_failed
}

/** "9:41". */
internal fun countdown(totalSeconds: Long): String {
    val seconds = totalSeconds.coerceAtLeast(0)
    return "${seconds / SECONDS_PER_MINUTE}:${(seconds % SECONDS_PER_MINUTE).toString().padStart(2, '0')}"
}

private const val SECONDS_PER_MINUTE = 60
