package dev.kpetriv.sprout.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
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
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kpetriv.sprout.core.model.MAX_SCHEDULE_DAY_OF_MONTH
import dev.kpetriv.sprout.core.ui.contentWidth
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
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (!loadStatus(state.isLoading, state.loadFailed)) InterestForm(state, onEvent)
        }
    }
    MessageEffect(state.message, snackbar) { onEvent(InterestSettingsEvent.MessageShown) }
}

@Composable
private fun InterestForm(state: InterestSettingsUiState, onEvent: (InterestSettingsEvent) -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).contentWidth().sectionPadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            stringResource(R.string.feature_settings_interest_explanation),
            style = MaterialTheme.typography.bodyMedium,
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
            modifier = Modifier.fillMaxWidth(),
        )
        ChoiceDropdown(
            label = stringResource(R.string.feature_settings_interest_accrual_day),
            options = (1..MAX_SCHEDULE_DAY_OF_MONTH).toList(),
            selected = state.accrualDay,
            optionLabel = { it.toString() },
            onSelect = { onEvent(InterestSettingsEvent.AccrualDayChanged(it)) },
        )
        InterestPreviewCard(state)
        SaveButton(state.isSaving, enabled = state.canSave, onClick = { onEvent(InterestSettingsEvent.Save) })
    }
}

@Composable
private fun InterestPreviewCard(state: InterestSettingsUiState) {
    val preview = state.preview ?: return
    val currency = state.currency ?: return
    val formatter = rememberMoneyFormatter()
    Card(modifier = Modifier.fillMaxWidth()) {
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
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun rateErrorText(error: RateError?): String? = when (error) {
    null -> null
    RateError.REQUIRED -> stringResource(R.string.feature_settings_error_required)
    RateError.NOT_A_NUMBER -> stringResource(R.string.feature_settings_error_not_a_number)
    RateError.TOO_MANY_DECIMALS -> stringResource(R.string.feature_settings_error_too_many_decimals)
    RateError.TOO_HIGH -> stringResource(R.string.feature_settings_interest_too_high)
}
