package dev.kpetriv.sprout.feature.timebank

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
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
            Column {
                state.today.forEach { SessionRow(it, withDate = false) }
            }
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
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TimerCard(state, onEvent)
        Text(
            text = stringResource(R.string.feature_timebank_balance, formatter.formatMinutes(state.balanceMinutes)),
            style = MaterialTheme.typography.titleMedium,
        )
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle(stringResource(R.string.feature_timebank_rules_title))
                Text(
                    text = stringResource(R.string.feature_timebank_rules),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
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
            Column {
                state.history.forEach { SessionRow(it, withDate = true) }
            }
        }
    }
}
