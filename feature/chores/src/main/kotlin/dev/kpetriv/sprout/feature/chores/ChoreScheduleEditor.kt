package dev.kpetriv.sprout.feature.chores

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.model.DayOfWeek
import dev.kpetriv.sprout.core.model.MAX_SCHEDULE_DAY_OF_MONTH

private const val MILLIS_PER_DAY = 86_400_000L

/** Who a new chore is for. Fixed once the chore exists (its history belongs to that kid). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun KidPicker(state: ChoreEditorUiState, onEvent: (ChoreEditorEvent) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeading(stringResource(R.string.feature_chores_field_kid))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.kids.forEach { kid ->
                FilterChip(
                    selected = kid.id == state.kidId,
                    onClick = { onEvent(ChoreEditorEvent.KidChanged(kid.id)) },
                    enabled = state.isNew || kid.id == state.kidId,
                    label = { Text(kid.name) },
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
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeading(stringResource(R.string.feature_chores_field_schedule))
        val kinds = ScheduleKind.entries
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            kinds.forEachIndexed { index, kind ->
                SegmentedButton(
                    selected = form.kind == kind,
                    onClick = { onEvent(ChoreEditorEvent.ScheduleKindChanged(kind)) },
                    shape = SegmentedButtonDefaults.itemShape(index, kinds.size),
                    label = { Text(kindLabel(kind)) },
                )
            }
        }
        when (form.kind) {
            ScheduleKind.ONCE -> OnceEditor(form.onceDate, state.today, onEvent)

            ScheduleKind.DAILY -> DayChips(selected = form.dailyDays) {
                onEvent(ChoreEditorEvent.DailyDayToggled(it))
            }

            ScheduleKind.WEEKLY -> DayChips(selected = setOf(form.weeklyDay)) {
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DayChips(selected: Set<DayOfWeek>, onToggle: (DayOfWeek) -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DayOfWeek.entries.forEach { day ->
            val full = day.displayName(locale)
            FilterChip(
                selected = day in selected,
                onClick = { onToggle(day) },
                label = { Text(day.displayName(locale, short = true)) },
                modifier = Modifier.semantics { contentDescription = full },
            )
        }
    }
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
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onClick = { picking = true }) { Text(stringResource(R.string.feature_chores_pick_date)) }
        if (date != null) {
            OutlinedButton(onClick = { onEvent(ChoreEditorEvent.OnceDateChanged(null)) }) {
                Text(stringResource(R.string.feature_chores_clear_date))
            }
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
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(
            onClick = { onEvent(ChoreEditorEvent.MonthlyDayChanged(day - 1)) },
            enabled = day > 1,
            modifier = Modifier.semantics { contentDescription = earlier },
        ) { Text("−") }
        Text(
            text = stringResource(R.string.feature_chores_monthly_day, day),
            style = MaterialTheme.typography.bodyLarge,
        )
        OutlinedButton(
            onClick = { onEvent(ChoreEditorEvent.MonthlyDayChanged(day + 1)) },
            enabled = day < MAX_SCHEDULE_DAY_OF_MONTH,
            modifier = Modifier.semantics { contentDescription = later },
        ) { Text("+") }
    }
}
