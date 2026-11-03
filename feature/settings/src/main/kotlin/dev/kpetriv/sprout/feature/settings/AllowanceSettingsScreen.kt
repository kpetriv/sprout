package dev.kpetriv.sprout.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kpetriv.sprout.core.model.AllowanceCadence
import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.model.DayOfWeek
import dev.kpetriv.sprout.core.model.MAX_SCHEDULE_DAY_OF_MONTH
import dev.kpetriv.sprout.core.ui.rememberMoneyFormatter
import java.time.format.TextStyle

@Composable
internal fun AllowanceSettingsRoute(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val viewModel: AllowanceSettingsViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    AllowanceSettingsScreen(state = state, onEvent = viewModel::onEvent, onBack = onBack, modifier = modifier)
}

/** Allowance per kid, with the edit sheet when [AllowanceSettingsUiState.editor] is set. */
@Composable
fun AllowanceSettingsScreen(
    state: AllowanceSettingsUiState,
    onEvent: (AllowanceSettingsEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbar = remember { SnackbarHostState() }
    SettingsScaffold(
        title = stringResource(R.string.feature_settings_section_allowance),
        onBack = onBack,
        modifier = modifier,
        snackbarHostState = snackbar,
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (!loadStatus(state.isLoading, state.loadFailed)) {
                Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    if (state.rows.isEmpty()) {
                        Text(stringResource(R.string.feature_settings_no_kids), modifier = Modifier.sectionPadding())
                    }
                    state.rows.forEachIndexed { index, row ->
                        if (index > 0) HorizontalDivider()
                        AllowanceRow(row, onClick = { onEvent(AllowanceSettingsEvent.Edit(row.kid.id)) })
                    }
                    Text(
                        stringResource(R.string.feature_settings_allowance_signing_note),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.sectionPadding(),
                    )
                }
            }
        }
    }
    MessageEffect(state.message, snackbar) { onEvent(AllowanceSettingsEvent.MessageShown) }
    state.editor?.let { AllowanceEditorSheet(it, onEvent) }
}

@Composable
private fun AllowanceRow(row: AllowanceRowUi, onClick: () -> Unit) {
    val schedule = row.schedule
    val formatter = rememberMoneyFormatter()
    val summary = when {
        schedule == null -> stringResource(R.string.feature_settings_allowance_none)
        else -> cadenceSummary(formatter.format(schedule.amount, schedule.currency), schedule.cadence)
    }
    val detail = when {
        schedule == null -> null
        !schedule.active -> stringResource(R.string.feature_settings_allowance_paused)
        row.nextDue != null -> stringResource(R.string.feature_settings_allowance_next, formatDate(row.nextDue))
        else -> null
    }
    ListItem(
        headlineContent = { Text(row.kid.firstName) },
        supportingContent = {
            Column {
                Text(summary)
                detail?.let { Text(it) }
            }
        },
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick),
    )
}

@Composable
private fun cadenceSummary(amount: String, cadence: AllowanceCadence): String = when (cadence) {
    is AllowanceCadence.Weekly ->
        stringResource(R.string.feature_settings_allowance_weekly, amount, dayName(cadence.dayOfWeek))

    is AllowanceCadence.Monthly ->
        stringResource(R.string.feature_settings_allowance_monthly, amount, cadence.dayOfMonth)
}

@Composable
private fun dayName(day: DayOfWeek): String {
    val locale = LocalConfiguration.current.locales[0]
    return java.time.DayOfWeek.of(day.isoNumber).getDisplayName(TextStyle.FULL, locale)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AllowanceEditorSheet(editor: AllowanceEditorUi, onEvent: (AllowanceSettingsEvent) -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = { onEvent(AllowanceSettingsEvent.Dismiss) }, sheetState = sheetState) {
        AllowanceEditorForm(editor, onEvent, Modifier.padding(start = 24.dp, end = 24.dp, bottom = 24.dp))
    }
}

/** Amount, cadence and its day, start date, active toggle, and the next due date for the form as it stands. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AllowanceEditorForm(
    editor: AllowanceEditorUi,
    onEvent: (AllowanceSettingsEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pickingDate by remember { mutableStateOf(false) }
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionHeading(stringResource(R.string.feature_settings_allowance_edit_title, editor.kidName))
        val amountError = amountErrorText(editor.amountError)
        OutlinedTextField(
            value = editor.amountText,
            onValueChange = { onEvent(AllowanceSettingsEvent.AmountChanged(it)) },
            label = { Text(stringResource(R.string.feature_settings_allowance_amount, editor.currency.isoCode)) },
            isError = amountError != null,
            supportingText = amountError?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            CadenceKind.entries.forEachIndexed { index, kind ->
                SegmentedButton(
                    selected = editor.cadence == kind,
                    onClick = { onEvent(AllowanceSettingsEvent.CadenceChanged(kind)) },
                    shape = SegmentedButtonDefaults.itemShape(index, CadenceKind.entries.size),
                ) {
                    Text(
                        stringResource(
                            when (kind) {
                                CadenceKind.WEEKLY -> R.string.feature_settings_allowance_cadence_weekly
                                CadenceKind.MONTHLY -> R.string.feature_settings_allowance_cadence_monthly
                            },
                        ),
                    )
                }
            }
        }
        when (editor.cadence) {
            CadenceKind.WEEKLY -> ChoiceDropdown(
                label = stringResource(R.string.feature_settings_allowance_day_of_week),
                options = DayOfWeek.entries,
                selected = editor.dayOfWeek,
                optionLabel = { dayName(it) },
                onSelect = { onEvent(AllowanceSettingsEvent.DayOfWeekChanged(it)) },
            )

            CadenceKind.MONTHLY -> ChoiceDropdown(
                label = stringResource(R.string.feature_settings_allowance_day_of_month),
                options = (1..MAX_SCHEDULE_DAY_OF_MONTH).toList(),
                selected = editor.dayOfMonth,
                optionLabel = { it.toString() },
                onSelect = { onEvent(AllowanceSettingsEvent.DayOfMonthChanged(it)) },
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.feature_settings_allowance_starts_on, formatDate(editor.startsOn)),
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { pickingDate = true }) {
                Text(stringResource(R.string.feature_settings_allowance_change_date))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.feature_settings_allowance_active), modifier = Modifier.weight(1f))
            Switch(checked = editor.active, onCheckedChange = { onEvent(AllowanceSettingsEvent.ActiveChanged(it)) })
        }
        editor.nextDue?.let {
            Text(
                stringResource(R.string.feature_settings_allowance_next, formatDate(it)),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (editor.saveFailed) {
            Text(stringResource(R.string.feature_settings_save_failed), color = MaterialTheme.colorScheme.error)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SaveButton(editor.isSaving, enabled = true, onClick = { onEvent(AllowanceSettingsEvent.Save) })
            TextButton(onClick = { onEvent(AllowanceSettingsEvent.Dismiss) }) {
                Text(stringResource(R.string.feature_settings_cancel))
            }
        }
    }
    if (pickingDate) {
        StartDatePicker(
            initial = editor.startsOn,
            onPicked = {
                pickingDate = false
                onEvent(AllowanceSettingsEvent.StartsOnChanged(it))
            },
            onDismiss = { pickingDate = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StartDatePicker(initial: CalendarDate, onPicked: (CalendarDate) -> Unit, onDismiss: () -> Unit) {
    val pickerState = rememberDatePickerState(initialSelectedDateMillis = initial.toEpochDay() * MILLIS_PER_DAY)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    // The picker reports UTC midnight of the chosen day, so the epoch day is exact.
                    pickerState.selectedDateMillis?.let { onPicked(CalendarDate.fromEpochDay(it / MILLIS_PER_DAY)) }
                        ?: onDismiss()
                },
            ) { Text(stringResource(R.string.feature_settings_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.feature_settings_cancel)) }
        },
    ) {
        DatePicker(state = pickerState)
    }
}

private const val MILLIS_PER_DAY = 86_400_000L
