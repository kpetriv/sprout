package dev.kpetriv.sprout.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kpetriv.sprout.core.designsystem.components.SproutButton
import dev.kpetriv.sprout.core.designsystem.theme.SproutSpacing
import dev.kpetriv.sprout.core.model.KidId

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
        SettingsColumn(padding = padding) {
            SettingsCard {
                SectionHeading(
                    stringResource(R.string.feature_settings_kids_link),
                    icon = ImageVector.vectorResource(R.drawable.feature_settings_ic_link),
                )
                Text(
                    stringResource(R.string.feature_settings_link_steps, state.kidName),
                    style = MaterialTheme.typography.bodyLarge,
                )
                val code = state.groupedCode
                when {
                    state.loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))

                    code != null && !state.expired -> {
                        Text(
                            text = code,
                            style = MaterialTheme.typography.displaySmall,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 4.sp,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.medium)
                                .padding(vertical = SproutSpacing.xl),
                        )
                        Text(
                            stringResource(R.string.feature_settings_link_expires, countdown(state.remainingSeconds)),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }

                    else -> {
                        val text = stringResource(linkProblemRes(state))
                        if (state.expired) {
                            Text(text, style = MaterialTheme.typography.bodyLarge)
                        } else {
                            ErrorText(text)
                        }
                        SproutButton(
                            text = stringResource(R.string.feature_settings_link_new),
                            onClick = { onEvent(LinkDeviceEvent.NewCode) },
                        )
                    }
                }
                Text(
                    stringResource(R.string.feature_settings_link_single_use),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
