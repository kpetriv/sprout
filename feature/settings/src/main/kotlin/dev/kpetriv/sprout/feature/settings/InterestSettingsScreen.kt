package dev.kpetriv.sprout.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kpetriv.sprout.core.designsystem.theme.SproutSpacing
import dev.kpetriv.sprout.core.model.MAX_SCHEDULE_DAY_OF_MONTH
import dev.kpetriv.sprout.core.ui.rememberMoneyFormatter

@Composable
internal fun InterestSettingsRoute(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val viewModel: InterestSettingsViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    InterestSettingsScreen(state = state, onEvent = viewModel::onEvent, onBack = onBack, modifier = modifier)
}

/** The interest policy: yearly rate in percent, accrual day, an explanation and a live preview. */
@Composable
fun InterestSettingsScreen(
    state: InterestSettingsUiState,
    onEvent: (InterestSettingsEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbar = remember { SnackbarHostState() }
    SettingsScaffold(
        title = stringResource(R.string.feature_settings_section_interest),
        onBack = onBack,
        modifier = modifier,
        snackbarHostState = snackbar,
    ) { padding ->
        if (!loadStatus(state.isLoading, state.loadFailed, Modifier.padding(padding))) {
            InterestForm(state, onEvent, padding)
        }
    }
    MessageEffect(state.message, snackbar) { onEvent(InterestSettingsEvent.MessageShown) }
}

@Composable
private fun InterestForm(
    state: InterestSettingsUiState,
    onEvent: (InterestSettingsEvent) -> Unit,
    padding: PaddingValues,
) {
    SettingsColumn(padding = padding) {
        SettingsCard {
            Text(
                stringResource(R.string.feature_settings_interest_explanation),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val rateError = rateErrorText(state.rateError)
            OutlinedTextField(
                value = state.rateText,
                onValueChange = { onEvent(InterestSettingsEvent.RateChanged(it)) },
                label = { Text(stringResource(R.string.feature_settings_interest_rate)) },
                isError = rateError != null,
                supportingText = { Text(rateError ?: stringResource(R.string.feature_settings_interest_rate_hint)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                shape = MaterialTheme.shapes.small,
                colors = settingsFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            ChoiceDropdown(
                label = stringResource(R.string.feature_settings_interest_accrual_day),
                options = (1..MAX_SCHEDULE_DAY_OF_MONTH).toList(),
                selected = state.accrualDay,
                optionLabel = { it.toString() },
                onSelect = { onEvent(InterestSettingsEvent.AccrualDayChanged(it)) },
            )
            InterestPreview(state)
            SaveButton(
                state.isSaving,
                enabled = state.canSave,
                onClick = { onEvent(InterestSettingsEvent.Save) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** What the rate earns on a round amount in a month, on a Linen tile. */
@Composable
private fun InterestPreview(state: InterestSettingsUiState) {
    val preview = state.preview ?: return
    val currency = state.currency ?: return
    val formatter = rememberMoneyFormatter()
    Text(
        text = if (state.rateBasisPoints == 0) {
            stringResource(R.string.feature_settings_interest_off)
        } else {
            stringResource(
                R.string.feature_settings_interest_preview,
                formatter.format(preview.principal, currency),
                formatter.format(preview.earned, currency),
            )
        },
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.medium)
            .padding(SproutSpacing.md),
    )
}

@Composable
private fun rateErrorText(error: RateError?): String? = when (error) {
    null -> null
    RateError.REQUIRED -> stringResource(R.string.feature_settings_error_required)
    RateError.NOT_A_NUMBER -> stringResource(R.string.feature_settings_error_not_a_number)
    RateError.TOO_MANY_DECIMALS -> stringResource(R.string.feature_settings_error_too_many_decimals)
    RateError.TOO_HIGH -> stringResource(R.string.feature_settings_interest_too_high)
}
