package dev.kpetriv.sprout.feature.wallet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.kpetriv.sprout.core.designsystem.theme.SproutTheme
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.ui.LedgerEventRow
import dev.kpetriv.sprout.core.ui.SavingsGoalBar
import dev.kpetriv.sprout.core.ui.TierScaledText
import dev.kpetriv.sprout.core.ui.TierTextRole
import dev.kpetriv.sprout.core.ui.contentWidth

/**
 * Sprout (about 8 to 11): real currency with decimals, chores and shop buttons, the savings goal bar and the last 10
 * events.
 */
@Composable
internal fun SproutWalletContent(
    state: WalletUiState.Ready,
    modifier: Modifier = Modifier,
    onOpenChores: () -> Unit = {},
    onOpenShop: () -> Unit = {},
) {
    val tier = KidUiTier.SPROUT
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .contentWidth()
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
        ChoresAndShopButtons(onOpenChores = onOpenChores, onOpenShop = onOpenShop)
        state.savingsGoal?.let { SavingsGoalCard(it, tier) }
        SectionHeading(stringResource(R.string.feature_wallet_recent), tier)
        if (state.events.isEmpty()) {
            EmptyHistory(tier)
        } else {
            state.events.forEach { EventRow(it, tier) }
        }
    }
}

/**
 * The savings card: progress towards the kid's goal ("Saving for New bike", £12.50 of £40.00), or the savings
 * balance alone when there is no goal.
 */
@Composable
internal fun SavingsGoalCard(goal: SavingsGoalUi, tier: KidUiTier, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        SavingsGoalBar(
            title = goal.title?.let { stringResource(R.string.feature_wallet_saving_for, it) }
                ?: stringResource(R.string.feature_wallet_savings),
            savedText = goal.savedText,
            targetText = goal.targetText,
            progress = goal.progress,
            tier = tier,
            modifier = Modifier.padding(16.dp),
        )
    }
}

/** Two equal tonal buttons to the kid's chores and the rewards shop (Sprout and Sapling). */
@Composable
internal fun ChoresAndShopButtons(onOpenChores: () -> Unit, onOpenShop: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FilledTonalButton(
            onClick = onOpenChores,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = SproutTheme.tierTokens.minTouchTarget),
        ) {
            Text("${EventCategory.CHORES.icon}  ${stringResource(R.string.feature_wallet_chores)}")
        }
        FilledTonalButton(
            onClick = onOpenShop,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = SproutTheme.tierTokens.minTouchTarget),
        ) {
            Text("${EventCategory.SHOP.icon}  ${stringResource(R.string.feature_wallet_shop)}")
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

/** One history row; an excluded game play leads its [subtitle] with why it is not counted. */
@Composable
internal fun EventRow(event: EventUi, tier: KidUiTier, modifier: Modifier = Modifier, subtitle: String? = event.note) {
    val notCounted = event.excluded?.let { stringResource(it.textRes) }
    LedgerEventRow(
        icon = event.category.icon,
        title = stringResource(event.titleRes),
        amount = event.amount,
        currency = event.currency,
        direction = event.direction,
        tier = tier,
        subtitle = listOfNotNull(notCounted, subtitle).joinToString(" · ").ifEmpty { null },
        modifier = modifier,
    )
}

private val GameExclusion.textRes: Int
    get() = when (this) {
        GameExclusion.PLAYED_ON_OUT_OF_RANGE -> R.string.feature_wallet_game_not_counted_window
        GameExclusion.DAILY_CAP_EXCEEDED -> R.string.feature_wallet_game_not_counted_cap
    }
