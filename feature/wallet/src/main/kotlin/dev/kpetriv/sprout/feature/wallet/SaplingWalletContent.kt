package dev.kpetriv.sprout.feature.wallet

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.ui.ContentWidth
import dev.kpetriv.sprout.core.ui.TierScaledText
import dev.kpetriv.sprout.core.ui.TierTextRole
import dev.kpetriv.sprout.core.ui.withGutters
import java.text.DateFormat
import java.util.Date

/**
 * Sapling (about 12 and up): every account, chores and shop buttons, the savings goal (when the kid has one), interest
 * with an explanation, a budget placeholder, CSV export and the full history with category filter chips.
 */
@Composable
internal fun SaplingWalletContent(
    state: WalletUiState.Ready,
    onFilterChange: (EventCategory?) -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenChores: () -> Unit = {},
    onOpenShop: () -> Unit = {},
) {
    val tier = KidUiTier.SAPLING
    val locale = LocalConfiguration.current.locales[0]
    val dateFormat = remember(locale) { DateFormat.getDateInstance(DateFormat.MEDIUM, locale) }
    ContentWidth(modifier = modifier.fillMaxSize()) { gutters ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp).withGutters(gutters),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { SectionHeading(stringResource(R.string.feature_wallet_accounts), tier) }
            items(state.balances, key = { "balance-${it.accountId.value}" }) { balance ->
                BalanceCard(title = balance.label(), value = balance.kidFormatted, tier = tier)
            }
            item { ChoresAndShopButtons(onOpenChores = onOpenChores, onOpenShop = onOpenShop) }
            state.savingsGoal?.takeIf { it.title != null }?.let { goal -> item { SavingsGoalCard(goal, tier) } }
            item { InterestCard(state.interestEarned) }
            item { BudgetPlaceholderCard() }
            item {
                OutlinedButton(onClick = onExport, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.feature_wallet_export_csv))
                }
            }
            item { SectionHeading(stringResource(R.string.feature_wallet_history), tier) }
            item { FilterChips(state.availableFilters, state.filter, onFilterChange) }
            if (state.events.isEmpty()) {
                item { EmptyHistory(tier) }
            } else {
                items(state.events, key = { it.eventId }) { event ->
                    val date = dateFormat.format(Date(event.createdAt.toEpochMilliseconds()))
                    EventRow(event, tier, subtitle = listOfNotNull(event.note, date).joinToString(" · "))
                }
            }
        }
    }
}

@Composable
private fun InterestCard(earned: String?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TierScaledText(
                text = stringResource(R.string.feature_wallet_interest_title),
                role = TierTextRole.Title,
                tier = KidUiTier.SAPLING,
            )
            TierScaledText(
                text = earned?.let { stringResource(R.string.feature_wallet_interest_earned, it) }
                    ?: stringResource(R.string.feature_wallet_interest_none),
                role = TierTextRole.Body,
                tier = KidUiTier.SAPLING,
            )
            TierScaledText(
                text = stringResource(R.string.feature_wallet_interest_explainer),
                role = TierTextRole.Label,
                tier = KidUiTier.SAPLING,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BudgetPlaceholderCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TierScaledText(
                text = stringResource(R.string.feature_wallet_budget_title),
                role = TierTextRole.Title,
                tier = KidUiTier.SAPLING,
            )
            TierScaledText(
                text = stringResource(R.string.feature_wallet_budget_placeholder),
                role = TierTextRole.Body,
                tier = KidUiTier.SAPLING,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FilterChips(
    available: List<EventCategory>,
    selected: EventCategory?,
    onFilterChange: (EventCategory?) -> Unit,
) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = selected == null,
            onClick = { onFilterChange(null) },
            label = { Text(stringResource(R.string.feature_wallet_filter_all)) },
        )
        // Keep the selected chip visible even when the filtered category has dropped out of the loaded window.
        (available + listOfNotNull(selected)).distinct().forEach { category ->
            FilterChip(
                selected = selected == category,
                onClick = { onFilterChange(if (selected == category) null else category) },
                label = { Text(stringResource(category.filterLabelRes)) },
            )
        }
    }
}
