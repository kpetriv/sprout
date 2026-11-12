package dev.kpetriv.sprout.feature.wallet

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.kpetriv.sprout.core.designsystem.components.CardMeaning
import dev.kpetriv.sprout.core.designsystem.components.SproutButton
import dev.kpetriv.sprout.core.designsystem.components.SproutButtonStyle
import dev.kpetriv.sprout.core.designsystem.components.SproutCard
import dev.kpetriv.sprout.core.designsystem.components.SproutChip
import dev.kpetriv.sprout.core.designsystem.components.StatTile
import dev.kpetriv.sprout.core.designsystem.components.accentTint
import dev.kpetriv.sprout.core.designsystem.icon.SproutIcons
import dev.kpetriv.sprout.core.designsystem.theme.STACK_FONT_SCALE
import dev.kpetriv.sprout.core.designsystem.theme.SproutSpacing
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.ui.ContentWidth
import dev.kpetriv.sprout.core.ui.withGutters
import java.text.DateFormat
import java.util.Date

/**
 * Sapling (about 12 and up) on `RESTRAINED`, as the Morning garden render: every account as a Linen tile (the
 * screen-time tile opens the time bank in kid mode), the Chores and Shop pills, the savings goal, interest with
 * an explanation, a budget placeholder, CSV export and the full history with category chips in one card.
 */
@Composable
internal fun SaplingWalletContent(
    state: WalletUiState.Ready,
    onFilterChange: (EventCategory?) -> Unit,
    actions: WalletActions,
    modifier: Modifier = Modifier,
) {
    val tier = KidUiTier.SAPLING
    val locale = LocalConfiguration.current.locales[0]
    val dateFormat = remember(locale) { DateFormat.getDateInstance(DateFormat.MEDIUM, locale) }
    ContentWidth(modifier = modifier.fillMaxSize()) { gutters ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = SproutSpacing.gutter, vertical = SproutSpacing.md)
                .withGutters(gutters),
            verticalArrangement = Arrangement.spacedBy(SproutSpacing.md),
        ) {
            item { SectionHeading(stringResource(R.string.feature_wallet_accounts), tier) }
            item(key = "accounts") { AccountTiles(state.balances, onOpenTimeBank = actions.onOpenTimeBank) }
            item {
                ChoresAndShopButtons(
                    onOpenChores = actions.onOpenChores,
                    onOpenShop = actions.onOpenShop,
                    shopStyle = SproutButtonStyle.Tertiary,
                )
            }
            state.savingsGoal?.takeIf { it.title != null }?.let { goal -> item { SavingsGoalCard(goal) } }
            item { InterestCard(state.interestEarned) }
            item { BudgetPlaceholderCard() }
            item {
                SproutButton(
                    text = stringResource(R.string.feature_wallet_export_csv),
                    onClick = actions.onExport,
                    style = SproutButtonStyle.Tertiary,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item { SectionHeading(stringResource(R.string.feature_wallet_history), tier) }
            item { FilterChips(state.availableFilters, state.filter, onFilterChange) }
            item(key = "history") {
                HistoryCard(state.events, tier) { event ->
                    listOfNotNull(event.note, dateFormat.format(Date(event.createdAt.toEpochMilliseconds())))
                        .joinToString(" · ")
                }
            }
        }
    }
}

/**
 * The accounts as [StatTile]s, three to a row (one at large font sizes). In kid mode the screen-time tile (with a
 * small sun) is a button to the time bank; on `RESTRAINED` it stays a Linen tile like the others (M9 §2.6).
 */
@Composable
private fun AccountTiles(balances: List<BalanceUi>, onOpenTimeBank: (() -> Unit)?) {
    // At large font sizes one tile per row, so no amount breaks mid-way.
    val perRow = if (LocalDensity.current.fontScale >= STACK_FONT_SCALE) 1 else TILES_PER_ROW
    Column(verticalArrangement = Arrangement.spacedBy(SproutSpacing.sm)) {
        balances.chunked(perRow).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(SproutSpacing.sm),
            ) {
                row.forEach { balance ->
                    val minutes = balance.currency == Currency.Minutes
                    val open = onOpenTimeBank?.takeIf { minutes }
                    StatTile(
                        label = balance.label(),
                        value = balance.kidFormatted,
                        icon = if (minutes) SproutIcons.WbSunny else null,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .then(
                                if (open == null) {
                                    Modifier
                                } else {
                                    Modifier
                                        .clip(MaterialTheme.shapes.medium)
                                        .clickable(role = Role.Button, onClick = open)
                                },
                            ),
                    )
                }
                repeat(perRow - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun InterestCard(earned: String?) {
    InfoCard(
        icon = SproutIcons.Eco,
        meaning = CardMeaning.Money,
        title = earned?.let { stringResource(R.string.feature_wallet_interest_earned, it) }
            ?: stringResource(R.string.feature_wallet_interest_none),
        overline = stringResource(R.string.feature_wallet_interest_title),
        body = stringResource(R.string.feature_wallet_interest_explainer),
    )
}

@Composable
private fun BudgetPlaceholderCard() {
    InfoCard(
        icon = SproutIcons.Payments,
        meaning = CardMeaning.Content,
        title = stringResource(R.string.feature_wallet_budget_title),
        body = stringResource(R.string.feature_wallet_budget_placeholder),
    )
}

/** A white card with a tinted icon circle, an optional small [overline], a [title] and a [body] in Ink soft. */
@Composable
private fun InfoCard(icon: ImageVector, meaning: CardMeaning, title: String, body: String, overline: String? = null) {
    val tint = meaning.accentTint()
    SproutCard(modifier = Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(SproutSpacing.md)) {
            Box(
                modifier = Modifier.size(IconCircle).background(tint.container, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = tint.content)
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(SproutSpacing.xs)) {
                if (overline != null) {
                    Text(
                        text = overline,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(text = title, style = MaterialTheme.typography.titleLarge)
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
        horizontalArrangement = Arrangement.spacedBy(SproutSpacing.sm),
    ) {
        SproutChip(
            selected = selected == null,
            label = stringResource(R.string.feature_wallet_filter_all),
            onClick = { onFilterChange(null) },
        )
        // Keep the selected chip visible even when the filtered category has dropped out of the loaded window.
        (available + listOfNotNull(selected)).distinct().forEach { category ->
            SproutChip(
                selected = selected == category,
                label = stringResource(category.filterLabelRes),
                onClick = { onFilterChange(if (selected == category) null else category) },
            )
        }
    }
}

private const val TILES_PER_ROW = 3
private val IconCircle = 40.dp
