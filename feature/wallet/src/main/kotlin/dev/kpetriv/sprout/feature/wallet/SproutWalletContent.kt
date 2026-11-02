package dev.kpetriv.sprout.feature.wallet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.ui.LedgerEventRow
import dev.kpetriv.sprout.core.ui.SavingsGoalBar
import dev.kpetriv.sprout.core.ui.TierScaledText
import dev.kpetriv.sprout.core.ui.TierTextRole

/** Sprout (about 8 to 11): real currency with decimals, a savings goal bar and the last 10 events. */
@Composable
internal fun SproutWalletContent(state: WalletUiState.Ready, modifier: Modifier = Modifier) {
    val tier = KidUiTier.SPROUT
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        state.mainBalance?.let { main ->
            BalanceCard(title = stringResource(R.string.feature_wallet_balance), value = main.kidFormatted, tier = tier)
        }
        state.minutesBalance?.let { minutes ->
            BalanceCard(
                title = stringResource(R.string.feature_wallet_screen_time),
                value = minutes.kidFormatted,
                tier = tier,
            )
        }
        state.savingsGoal?.let { goal ->
            Card(modifier = Modifier.fillMaxWidth()) {
                SavingsGoalBar(
                    title = stringResource(R.string.feature_wallet_savings),
                    savedText = goal.savedText,
                    targetText = goal.targetText,
                    progress = goal.progress,
                    tier = tier,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        SectionHeading(stringResource(R.string.feature_wallet_recent), tier)
        if (state.events.isEmpty()) {
            EmptyHistory(tier)
        } else {
            state.events.forEach { EventRow(it, tier) }
        }
    }
}

/** A titled card with one big number. Merged for accessibility so it reads "Your money, £12.50". */
@Composable
internal fun BalanceCard(title: String, value: String, tier: KidUiTier, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .semantics(mergeDescendants = true) {},
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TierScaledText(text = title, role = TierTextRole.Label, tier = tier)
            TierScaledText(text = value, role = TierTextRole.Balance, tier = tier)
        }
    }
}

@Composable
internal fun SectionHeading(text: String, tier: KidUiTier, modifier: Modifier = Modifier) {
    TierScaledText(
        text = text,
        role = TierTextRole.Title,
        tier = tier,
        modifier = modifier.semantics { heading() },
    )
}

@Composable
internal fun EmptyHistory(tier: KidUiTier, modifier: Modifier = Modifier) {
    TierScaledText(
        text = stringResource(R.string.feature_wallet_history_empty),
        role = TierTextRole.Body,
        tier = tier,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

@Composable
internal fun EventRow(event: EventUi, tier: KidUiTier, modifier: Modifier = Modifier, subtitle: String? = event.note) {
    LedgerEventRow(
        icon = event.category.icon,
        title = stringResource(event.category.titleRes),
        amount = event.amount,
        currency = event.currency,
        direction = event.direction,
        tier = tier,
        subtitle = subtitle,
        modifier = modifier,
    )
}
