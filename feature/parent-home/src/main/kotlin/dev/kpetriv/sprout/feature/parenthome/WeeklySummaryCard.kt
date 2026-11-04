package dev.kpetriv.sprout.feature.parenthome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * The on-device weekly summary (M6 §3.3): "This week so far" / "Last week", the text with an "On-device" chip, and
 * "Download the on-device model" when the model can be fetched and the parent has not agreed yet. Fails soft: an
 * unavailable summary is one line, never an error for the dashboard.
 *
 * The text is announced (a polite live region) only once the parent has switched the week here, never just because the
 * dashboard opened or the facts changed (review L13).
 */
@Composable
fun WeeklySummaryCard(
    summary: WeeklySummaryUi,
    week: SummaryWeek,
    onWeekChange: (SummaryWeek) -> Unit,
    onDownloadModel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var weekChanged by remember { mutableStateOf(false) }
    OutlinedCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.feature_parent_home_summary_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                OnDeviceChip()
            }
            WeekToggle(week) { picked ->
                if (picked != week) weekChanged = true
                onWeekChange(picked)
            }
            SummaryBody(summary, announce = weekChanged, onDownloadModel = onDownloadModel)
        }
    }
}

@Composable
private fun OnDeviceChip() {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Text(
            stringResource(R.string.feature_parent_home_summary_on_device),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun WeekToggle(week: SummaryWeek, onWeekChange: (SummaryWeek) -> Unit) {
    val weeks = SummaryWeek.entries
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        weeks.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == week,
                onClick = { onWeekChange(option) },
                shape = SegmentedButtonDefaults.itemShape(index, weeks.size),
            ) {
                Text(
                    stringResource(
                        when (option) {
                            SummaryWeek.THIS_WEEK -> R.string.feature_parent_home_summary_this_week
                            SummaryWeek.LAST_WEEK -> R.string.feature_parent_home_summary_last_week
                        },
                    ),
                )
            }
        }
    }
}

@Composable
private fun SummaryBody(summary: WeeklySummaryUi, announce: Boolean, onDownloadModel: () -> Unit) {
    when (summary) {
        WeeklySummaryUi.Loading -> Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(
                stringResource(R.string.feature_parent_home_summary_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        is WeeklySummaryUi.Ready -> {
            Text(
                summary.text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = if (announce) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier,
            )
            if (summary.offerModelDownload) {
                TextButton(onClick = onDownloadModel) {
                    Text(stringResource(R.string.feature_parent_home_summary_download))
                }
            }
        }

        WeeklySummaryUi.Unavailable -> Text(
            stringResource(R.string.feature_parent_home_summary_unavailable),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
