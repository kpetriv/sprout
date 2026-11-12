package dev.kpetriv.sprout.feature.chores

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.kpetriv.sprout.core.designsystem.components.SproutButton
import dev.kpetriv.sprout.core.designsystem.components.SproutButtonStyle
import dev.kpetriv.sprout.core.designsystem.components.SproutChip
import dev.kpetriv.sprout.core.designsystem.icon.SproutIcons
import dev.kpetriv.sprout.core.designsystem.theme.SproutSpacing
import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.model.DayOfWeek
import dev.kpetriv.sprout.core.model.MAX_SCHEDULE_DAY_OF_MONTH

private const val MILLIS_PER_DAY = 86_400_000L

/** Who a new chore is for, as one-choice chips. Fixed once the chore exists (its history belongs to that kid). */
@Composable
internal fun KidPicker(state: ChoreEditorUiState, onEvent: (ChoreEditorEvent) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(SproutSpacing.sm)) {
        SectionHeading(stringResource(R.string.feature_chores_field_kid))
        ChipRow {
            state.kids.forEach { kid ->
                SproutChip(
                    selected = kid.id == state.kidId,
                    label = kid.name,
                    onClick = { onEvent(ChoreEditorEvent.KidChanged(kid.id)) },
                    enabled = state.isNew || kid.id == state.kidId,
                    role = Role.RadioButton,
                )
            }
        }
        ErrorLine(state.errors, EditorError.NO_KID)
    }
}

/** Once (optional date) / daily (weekday toggles) / weekly (one day) / monthly (day 1 to 28). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScheduleEditor(state: ChoreEditorUiState, onEvent: (ChoreEditorEvent) -> Unit) {
    val form = state.schedule
    Column(verticalArrangement = Arrangement.spacedBy(SproutSpacing.sm)) {
        SectionHeading(stringResource(R.string.feature_chores_field_schedule))
        ChipRow {
            ScheduleKind.entries.forEach { kind ->
                SproutChip(
                    selected = form.kind == kind,
                    label = kindLabel(kind),
                    onClick = { onEvent(ChoreEditorEvent.ScheduleKindChanged(kind)) },
                    role = Role.RadioButton,
                )
            }
        }
        when (form.kind) {
            ScheduleKind.ONCE -> OnceEditor(form.onceDate, state.today, onEvent)

            ScheduleKind.DAILY -> DayChips(selected = form.dailyDays, role = Role.Checkbox) {
                onEvent(ChoreEditorEvent.DailyDayToggled(it))
            }

            ScheduleKind.WEEKLY -> DayChips(selected = setOf(form.weeklyDay), role = Role.RadioButton) {
                onEvent(ChoreEditorEvent.WeeklyDayChanged(it))
            }

            ScheduleKind.MONTHLY -> MonthlyEditor(form.monthlyDay, onEvent)
        }
        ErrorLine(state.errors, EditorError.NO_DAYS, EditorError.DUE_DATE_IN_PAST)
    }
}

@Composable
private fun kindLabel(kind: ScheduleKind): String = stringResource(
    when (kind) {
        ScheduleKind.ONCE -> R.string.feature_chores_kind_once
        ScheduleKind.DAILY -> R.string.feature_chores_kind_daily
        ScheduleKind.WEEKLY -> R.string.feature_chores_kind_weekly
        ScheduleKind.MONTHLY -> R.string.feature_chores_kind_monthly
    },
)

/**
 * One chip per weekday ("Mon", read as "Monday"): [Role.Checkbox] for the days of a daily chore, [Role.RadioButton]
 * for the one day of a weekly chore.
 */
@Composable
private fun DayChips(selected: Set<DayOfWeek>, role: Role, onToggle: (DayOfWeek) -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    ChipRow {
        DayOfWeek.entries.forEach { day ->
            val full = day.displayName(locale)
            SproutChip(
                selected = day in selected,
                label = day.displayName(locale, short = true),
                onClick = { onToggle(day) },
                role = role,
                modifier = Modifier.semantics { contentDescription = full },
            )
        }
    }
}

/**
 * Chips or pills that wrap onto more lines; with [selectable] (the default, for radio and checkbox chip sets) they
 * form one selectable group.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(selectable: Boolean = true, content: @Composable () -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().then(if (selectable) Modifier.selectableGroup() else Modifier),
        horizontalArrangement = Arrangement.spacedBy(SproutSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(SproutSpacing.sm),
    ) { content() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OnceEditor(date: CalendarDate?, today: CalendarDate, onEvent: (ChoreEditorEvent) -> Unit) {
    var picking by rememberSaveable { mutableStateOf(false) }
    Text(
        text = date?.let { stringResource(R.string.feature_chores_once_due, formatDate(it)) }
            ?: stringResource(R.string.feature_chores_once_any_time),
        style = MaterialTheme.typography.bodyLarge,
    )
    // Plain buttons, not a choice: no selectable group.
    ChipRow(selectable = false) {
        SproutButton(
            text = stringResource(R.string.feature_chores_pick_date),
            onClick = { picking = true },
            style = SproutButtonStyle.Tertiary,
            icon = SproutIcons.Schedule,
        )
        if (date != null) {
            SproutButton(
                text = stringResource(R.string.feature_chores_clear_date),
                onClick = { onEvent(ChoreEditorEvent.OnceDateChanged(null)) },
                style = SproutButtonStyle.Tertiary,
                icon = SproutIcons.Close,
            )
        }
    }
    if (picking) {
        val initial = (date ?: today).toEpochDay() * MILLIS_PER_DAY
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = initial)
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        picking = false
                        pickerState.selectedDateMillis?.let { millis ->
                            // The picker reports UTC midnight of the chosen day.
                            val picked = CalendarDate.fromEpochDay(Math.floorDiv(millis, MILLIS_PER_DAY))
                            onEvent(ChoreEditorEvent.OnceDateChanged(picked))
                        }
                    },
                ) { Text(stringResource(R.string.feature_chores_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { picking = false }) { Text(stringResource(R.string.feature_chores_cancel)) }
            },
        ) { DatePicker(state = pickerState) }
    }
}

@Composable
private fun MonthlyEditor(day: Int, onEvent: (ChoreEditorEvent) -> Unit) {
    val earlier = stringResource(R.string.feature_chores_monthly_earlier)
    val later = stringResource(R.string.feature_chores_monthly_later)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SproutSpacing.sm + SproutSpacing.xs),
    ) {
        SproutButton(
            text = "−",
            onClick = { onEvent(ChoreEditorEvent.MonthlyDayChanged(day - 1)) },
            enabled = day > 1,
            style = SproutButtonStyle.Tertiary,
            modifier = Modifier.semantics { contentDescription = earlier },
        )
        Text(
            text = stringResource(R.string.feature_chores_monthly_day, day),
            style = MaterialTheme.typography.bodyLarge,
        )
        SproutButton(
            text = "+",
            onClick = { onEvent(ChoreEditorEvent.MonthlyDayChanged(day + 1)) },
            enabled = day < MAX_SCHEDULE_DAY_OF_MONTH,
            style = SproutButtonStyle.Tertiary,
            modifier = Modifier.semantics { contentDescription = later },
        )
    }
}
