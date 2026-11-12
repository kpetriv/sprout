package dev.kpetriv.sprout.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kpetriv.sprout.core.designsystem.theme.SproutSpacing

@Composable
internal fun AiSettingsRoute(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val viewModel: AiSettingsViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    AiSettingsScreen(state = state, onEvent = viewModel::onEvent, onBack = onBack, modifier = modifier)
}

/**
 * On-device AI: the status line (with the kid-phone note, D-M6-34), "Use on-device AI" (this phone only), the one-time
 * "Download the model" consent (only with AI on and while the model can be downloaded, D-M6-6, audit L3) and what the
 * AI is used for.
 */
@Composable
fun AiSettingsScreen(
    state: AiSettingsUiState,
    onEvent: (AiSettingsEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbar = remember { SnackbarHostState() }
    SettingsScaffold(
        title = stringResource(R.string.feature_settings_section_ai),
        onBack = onBack,
        modifier = modifier,
        snackbarHostState = snackbar,
    ) { padding ->
        val settings = state.settings
        if (!loadStatus(state.isLoading, state.loadFailed, Modifier.padding(padding)) && settings != null) {
            AiSettingsContent(state, settings, onEvent, padding)
        }
    }
    MessageEffect(state.message, snackbar) { onEvent(AiSettingsEvent.MessageShown) }
}

@Composable
private fun AiSettingsContent(
    state: AiSettingsUiState,
    settings: AiSettings,
    onEvent: (AiSettingsEvent) -> Unit,
    padding: PaddingValues,
) {
    SettingsColumn(padding = padding) {
        SettingsCard {
            Text(
                text = stringResource(settings.status.textRes()),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            Text(
                text = stringResource(R.string.feature_settings_ai_kid_phones),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SettingsCard {
            SwitchRow(
                title = stringResource(R.string.feature_settings_ai_enabled_title),
                summary = stringResource(R.string.feature_settings_ai_enabled_summary),
                checked = settings.enabled,
                enabled = !state.isSaving,
                onChange = { onEvent(AiSettingsEvent.EnabledChanged(it)) },
            )
            if (state.showsDownloadConsent) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SwitchRow(
                    title = stringResource(R.string.feature_settings_ai_download_title),
                    summary = stringResource(R.string.feature_settings_ai_download_summary),
                    checked = settings.downloadConsent,
                    enabled = state.canChangeConsent,
                    onChange = { onEvent(AiSettingsEvent.DownloadConsentChanged(it)) },
                )
            }
        }
        SettingsCard {
            SectionHeading(stringResource(R.string.feature_settings_ai_uses_title))
            Text(stringResource(R.string.feature_settings_ai_uses), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.feature_settings_ai_privacy),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A title, an explanation and a Material switch (it takes the scheme's colours); the whole row toggles. */
@Composable
internal fun SwitchRow(
    title: String,
    summary: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SproutSpacing.md),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(SproutSpacing.xs)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

private fun AiModelStatus.textRes(): Int = when (this) {
    AiModelStatus.AVAILABLE -> R.string.feature_settings_ai_status_available
    AiModelStatus.DOWNLOADABLE -> R.string.feature_settings_ai_status_downloadable
    AiModelStatus.DOWNLOADING -> R.string.feature_settings_ai_status_downloading
    AiModelStatus.UNAVAILABLE -> R.string.feature_settings_ai_status_unavailable
}
