package dev.kpetriv.sprout.feature.parenthome

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.kpetriv.sprout.core.designsystem.components.SproutButton
import dev.kpetriv.sprout.core.designsystem.components.SproutButtonStyle
import dev.kpetriv.sprout.core.designsystem.components.SproutCard
import dev.kpetriv.sprout.core.designsystem.components.SproutChip
import dev.kpetriv.sprout.core.designsystem.icon.SproutIcons
import dev.kpetriv.sprout.core.designsystem.theme.SproutSpacing

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
    SproutCard(modifier = modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(SproutSpacing.sm + SproutSpacing.xs)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(SproutSpacing.sm),
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

/** "On-device" with a lock on a Linen pill: the summary was written on this phone, nothing left it. */
@Composable
private fun OnDeviceChip() {
    Row(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceContainerLow, CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
            .padding(horizontal = SproutSpacing.sm + SproutSpacing.xs, vertical = SproutSpacing.xs),
        horizontalArrangement = Arrangement.spacedBy(SproutSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            SproutIcons.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
        Text(
            stringResource(R.string.feature_parent_home_summary_on_device),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** "This week so far" and "Last week" as one-choice chips. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WeekToggle(week: SummaryWeek, onWeekChange: (SummaryWeek) -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(SproutSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(SproutSpacing.sm),
    ) {
        SummaryWeek.entries.forEach { option ->
            SproutChip(
                selected = option == week,
                label = stringResource(
                    when (option) {
                        SummaryWeek.THIS_WEEK -> R.string.feature_parent_home_summary_this_week
                        SummaryWeek.LAST_WEEK -> R.string.feature_parent_home_summary_last_week
                    },
                ),
                onClick = { onWeekChange(option) },
                role = Role.RadioButton,
            )
        }
    }
}

@Composable
private fun SummaryBody(summary: WeeklySummaryUi, announce: Boolean, onDownloadModel: () -> Unit) {
    when (summary) {
        WeeklySummaryUi.Loading -> Row(
            horizontalArrangement = Arrangement.spacedBy(SproutSpacing.sm),
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
                style = MaterialTheme.typography.bodyLarge,
                modifier = if (announce) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier,
            )
            if (summary.offerModelDownload) {
                SproutButton(
                    text = stringResource(R.string.feature_parent_home_summary_download),
                    onClick = onDownloadModel,
                    style = SproutButtonStyle.Tertiary,
                    icon = SproutIcons.AutoAwesome,
                )
            }
        }

        WeeklySummaryUi.Unavailable -> Text(
            stringResource(R.string.feature_parent_home_summary_unavailable),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
