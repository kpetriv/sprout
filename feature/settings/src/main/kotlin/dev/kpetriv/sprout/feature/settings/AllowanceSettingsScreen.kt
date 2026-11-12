package dev.kpetriv.sprout.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kpetriv.sprout.core.designsystem.components.InitialAvatar
import dev.kpetriv.sprout.core.designsystem.icon.SproutIcons
import dev.kpetriv.sprout.core.designsystem.theme.STACK_FONT_SCALE
import dev.kpetriv.sprout.core.designsystem.theme.SproutSpacing
import dev.kpetriv.sprout.core.domainledger.Money
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
        if (!loadStatus(state.isLoading, state.loadFailed, Modifier.padding(padding))) {
            SettingsColumn(padding = padding, spacing = SproutSpacing.rowGap) {
                if (state.rows.isEmpty()) {
                    Text(stringResource(R.string.feature_settings_no_kids), style = MaterialTheme.typography.bodyLarge)
                }
                state.rows.forEachIndexed { index, row ->
                    AllowanceRow(row, index, onClick = { onEvent(AllowanceSettingsEvent.Edit(row.kid.id)) })
                }
                Text(
                    stringResource(R.string.feature_settings_allowance_signing_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = SproutSpacing.md),
                )
                SectionHeading(stringResource(R.string.feature_settings_allowance_games_title))
                val cap = state.gameCap
                if (cap != null) {
                    GameCapRow(cap, onClick = { onEvent(AllowanceSettingsEvent.EditGameCap) })
                } else {
                    // No household policy on this phone yet: it never writes one of its own (D-M7-57).
                    Text(
                        stringResource(R.string.feature_settings_policy_waiting),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    MessageEffect(state.message, snackbar) { onEvent(AllowanceSettingsEvent.MessageShown) }
    state.editor?.let { AllowanceEditorSheet(it, onEvent) }
    state.gameCapEditor?.let { GameCapDialog(it, onEvent) }
}

/** "Daily game limit" with the household's value (D-M5-4). */
@Composable
internal fun GameCapRow(cap: GameCapUi, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val amount = formatSmallAmount(cap.capMinor, cap.currency)
    SettingsRow(
        title = stringResource(R.string.feature_settings_game_cap_title),
        summary = stringResource(
            if (cap.isDefault) {
                R.string.feature_settings_game_cap_summary_default
            } else {
                R.string.feature_settings_game_cap_summary
            },
            amount,
        ),
        icon = SproutIcons.SportsEsports,
        onClick = onClick,
        modifier = modifier,
    )
}

/** The game limit dialog. */
@Composable
internal fun GameCapDialog(editor: GameCapEditorUi, onEvent: (AllowanceSettingsEvent) -> Unit) {
    SettingsDialog(onDismissRequest = { onEvent(AllowanceSettingsEvent.DismissGameCap) }) {
        GameCapDialogCard(editor, onEvent)
    }
}

/** The game limit dialog's card: the amount (0 allowed), "Use the default", save and cancel. */
@Composable
internal fun GameCapDialogCard(
    editor: GameCapEditorUi,
    onEvent: (AllowanceSettingsEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val formatter = rememberMoneyFormatter()
    SettingsDialogCard(
        title = stringResource(R.string.feature_settings_game_cap_title),
        modifier = modifier,
        buttons = {
            QuietButton(
                text = stringResource(R.string.feature_settings_cancel),
                onClick = { onEvent(AllowanceSettingsEvent.DismissGameCap) },
                enabled = !editor.isSaving,
            )
            SaveButton(editor.isSaving, enabled = true, onClick = { onEvent(AllowanceSettingsEvent.SaveGameCap) })
        },
    ) {
        Text(stringResource(R.string.feature_settings_game_cap_text), style = MaterialTheme.typography.bodyMedium)
        val error = if (editor.error == AmountError.TOO_LARGE) {
            val max = formatter.format(Money(HouseholdRulesState.MAX_DAILY_GAME_CAP_MINOR), editor.currency)
            stringResource(R.string.feature_settings_game_cap_too_large, max)
        } else {
            amountErrorText(editor.error)
        }
        OutlinedTextField(
            value = editor.text,
            onValueChange = { onEvent(AllowanceSettingsEvent.GameCapChanged(it)) },
            label = { Text(stringResource(R.string.feature_settings_game_cap_amount, editor.currency.isoCode)) },
            isError = error != null,
            supportingText = error?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            enabled = !editor.isSaving,
            shape = MaterialTheme.shapes.small,
            colors = settingsFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        val default = formatSmallAmount(HouseholdRulesState.DEFAULT_DAILY_GAME_CAP_MINOR, editor.currency)
        QuietButton(
            text = stringResource(R.string.feature_settings_game_cap_reset, default),
            onClick = { onEvent(AllowanceSettingsEvent.ResetGameCap) },
            enabled = !editor.isSaving,
        )
        if (editor.saveFailed) ErrorText(stringResource(R.string.feature_settings_save_failed))
    }
}

@Composable
private fun AllowanceRow(row: AllowanceRowUi, index: Int, onClick: () -> Unit) {
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
    SettingsRow(
        title = row.kid.firstName,
        summary = summary,
        onClick = onClick,
        leading = {
            InitialAvatar(
                initial = row.kid.firstName.take(1).uppercase(),
                tintIndex = index,
                size = 40.dp,
                modifier = Modifier.clearAndSetSemantics {},
            )
        },
        extra = detail?.let {
            {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
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
    ModalBottomSheet(
        onDismissRequest = { onEvent(AllowanceSettingsEvent.Dismiss) },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
        AllowanceEditorForm(editor, onEvent, Modifier.sheetPadding())
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
        verticalArrangement = Arrangement.spacedBy(SproutSpacing.md),
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
            shape = MaterialTheme.shapes.small,
            colors = settingsFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        RadioChips(
            options = CadenceKind.entries,
            selected = editor.cadence,
            optionLabel = { kind ->
                stringResource(
                    when (kind) {
                        CadenceKind.WEEKLY -> R.string.feature_settings_allowance_cadence_weekly
                        CadenceKind.MONTHLY -> R.string.feature_settings_allowance_cadence_monthly
                    },
                )
            },
            onSelect = { onEvent(AllowanceSettingsEvent.CadenceChanged(it)) },
        )
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
        StartsOnRow(editor.startsOn, onChange = { pickingDate = true })
        Row(
            modifier = Modifier.fillMaxWidth().toggleable(
                value = editor.active,
                role = Role.Switch,
                onValueChange = { onEvent(AllowanceSettingsEvent.ActiveChanged(it)) },
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.feature_settings_allowance_active),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = editor.active, onCheckedChange = null)
        }
        editor.nextDue?.let {
            Text(
                stringResource(R.string.feature_settings_allowance_next, formatDate(it)),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (editor.saveFailed) ErrorText(stringResource(R.string.feature_settings_save_failed))
        ButtonRow(alignment = Alignment.Start) {
            SaveButton(editor.isSaving, enabled = true, onClick = { onEvent(AllowanceSettingsEvent.Save) })
            QuietButton(
                text = stringResource(R.string.feature_settings_cancel),
                onClick = { onEvent(AllowanceSettingsEvent.Dismiss) },
            )
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

/** "Starts on 7 Nov 2026" with its "Change" pill; stacked at large font sizes. */
@Composable
private fun StartsOnRow(startsOn: CalendarDate, onChange: () -> Unit) {
    val text = stringResource(R.string.feature_settings_allowance_starts_on, formatDate(startsOn))
    val change = @Composable {
        QuietButton(
            text = stringResource(R.string.feature_settings_allowance_change_date),
            onClick = onChange,
            icon = SproutIcons.Schedule,
        )
    }
    if (LocalDensity.current.fontScale >= STACK_FONT_SCALE) {
        Column(verticalArrangement = Arrangement.spacedBy(SproutSpacing.sm)) {
            Text(text, style = MaterialTheme.typography.bodyLarge)
            change()
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            change()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StartDatePicker(initial: CalendarDate, onPicked: (CalendarDate) -> Unit, onDismiss: () -> Unit) {
    val pickerState = rememberDatePickerState(initialSelectedDateMillis = initial.toEpochDay() * MILLIS_PER_DAY)
    val colors = DatePickerDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        colors = colors,
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
        DatePicker(state = pickerState, colors = colors)
    }
}

private const val MILLIS_PER_DAY = 86_400_000L
