package dev.kpetriv.sprout.feature.timebank

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.kpetriv.sprout.core.designsystem.components.SproutCard
import dev.kpetriv.sprout.core.designsystem.theme.SproutSpacing
import dev.kpetriv.sprout.core.ui.contentWidth
import dev.kpetriv.sprout.core.ui.rememberMoneyFormatter

/** Sprout (about 8 to 11): the timer with minutes left, then today's sessions and today's total. */
@Composable
internal fun SproutTimeBankContent(
    state: TimeBankUiState.Ready,
    onEvent: (TimeBankEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val formatter = rememberMoneyFormatter()
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(
            rememberScrollState(),
        ).contentWidth().padding(SproutSpacing.gutter),
        verticalArrangement = Arrangement.spacedBy(SproutSpacing.md),
    ) {
        TimerCard(state, onEvent)
        SectionTitle(stringResource(R.string.feature_timebank_today))
        if (state.today.isEmpty()) {
            Text(
                text = stringResource(R.string.feature_timebank_today_none),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            SessionList(state.today, withDate = false)
            val total = formatter.formatMinutes(state.todayMinutes)
            Text(
                text = stringResource(R.string.feature_timebank_today_total, total),
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

/** Sapling (about 12 and older): the timer, the balance, the rule text and the most recent sessions with dates. */
@Composable
internal fun SaplingTimeBankContent(
    state: TimeBankUiState.Ready,
    onEvent: (TimeBankEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val formatter = rememberMoneyFormatter()
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(
            rememberScrollState(),
        ).contentWidth().padding(SproutSpacing.gutter),
        verticalArrangement = Arrangement.spacedBy(SproutSpacing.md),
    ) {
        TimerCard(state, onEvent)
        Text(
            text = stringResource(R.string.feature_timebank_balance, formatter.formatMinutes(state.balanceMinutes)),
            style = MaterialTheme.typography.titleMedium,
        )
        SproutCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(SproutSpacing.sm)) {
                SectionTitle(stringResource(R.string.feature_timebank_rules_title))
                Text(
                    text = stringResource(R.string.feature_timebank_rules),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        SectionTitle(stringResource(R.string.feature_timebank_history))
        if (state.history.isEmpty()) {
            Text(
                text = stringResource(R.string.feature_timebank_history_none),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            SessionList(state.history, withDate = true)
        }
    }
}
