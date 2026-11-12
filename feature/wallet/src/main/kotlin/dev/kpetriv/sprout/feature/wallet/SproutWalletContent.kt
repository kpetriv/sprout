package dev.kpetriv.sprout.feature.wallet

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kpetriv.sprout.core.designsystem.components.CardMeaning
import dev.kpetriv.sprout.core.designsystem.components.GoalBar
import dev.kpetriv.sprout.core.designsystem.components.LedgerRow
import dev.kpetriv.sprout.core.designsystem.components.SproutButton
import dev.kpetriv.sprout.core.designsystem.components.SproutButtonStyle
import dev.kpetriv.sprout.core.designsystem.components.SproutCard
import dev.kpetriv.sprout.core.designsystem.components.accentTint
import dev.kpetriv.sprout.core.designsystem.icon.SproutIcons
import dev.kpetriv.sprout.core.designsystem.theme.STACK_FONT_SCALE
import dev.kpetriv.sprout.core.designsystem.theme.SproutSpacing
import dev.kpetriv.sprout.core.designsystem.theme.SproutTheme
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.ui.TierScaledText
import dev.kpetriv.sprout.core.ui.TierTextRole
import dev.kpetriv.sprout.core.ui.contentWidth
import dev.kpetriv.sprout.core.ui.ledgerAmountText
import dev.kpetriv.sprout.core.ui.rememberMoneyFormatter

/**
 * Sprout (about 8 to 11), the "Morning garden" wallet of the renders: a white money hero with the balance in the
 * display face, a Leaf-tint tier chip and the Honey screen-time badge (which opens the time bank in kid mode), the
 * Chores and Shop pills, the savings goal card and the last 10 events in one card.
 */
@Composable
internal fun SproutWalletContent(state: WalletUiState.Ready, actions: WalletActions, modifier: Modifier = Modifier) {
    val tier = KidUiTier.SPROUT
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .contentWidth()
            .padding(horizontal = SproutSpacing.gutter, vertical = SproutSpacing.md),
        verticalArrangement = Arrangement.spacedBy(SproutSpacing.md),
    ) {
        if (state.mainBalance != null || state.minutesBalance != null) {
            MoneyHero(state = state, tier = tier, onOpenTimeBank = actions.onOpenTimeBank)
        }
        ChoresAndShopButtons(onOpenChores = actions.onOpenChores, onOpenShop = actions.onOpenShop)
        state.savingsGoal?.let { SavingsGoalCard(it) }
        SectionHeading(stringResource(R.string.feature_wallet_recent), tier)
        HistoryCard(state.events, tier)
    }
}

/**
 * The Sprout money hero: "Your money" over the balance (42 sp, display face, tabular), the tier chip, and the
 * screen-time badge beside them (under them when the text is too large to share a line).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MoneyHero(state: WalletUiState.Ready, tier: KidUiTier, onOpenTimeBank: (() -> Unit)?) {
    SproutCard(modifier = Modifier.fillMaxWidth(), meaning = CardMeaning.Money) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(SproutSpacing.md),
        ) {
            Column(
                modifier = Modifier.semantics(mergeDescendants = true) {},
                verticalArrangement = Arrangement.spacedBy(SproutSpacing.sm),
            ) {
                state.mainBalance?.let { main ->
                    Text(
                        text = stringResource(R.string.feature_wallet_balance),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = main.kidFormatted,
                        style = MaterialTheme.typography.displayLarge.copy(
                            fontSize = HeroAmountSize,
                            lineHeight = HeroAmountLine,
                        ),
                    )
                }
                TierChip(tier)
            }
            state.minutesBalance?.let { ScreenTimeBadge(it, onClick = onOpenTimeBank) }
        }
    }
}

/** The Leaf-tint chip naming the kid's tier, with a Botanical dot. */
@Composable
private fun TierChip(tier: KidUiTier) {
    val tint = CardMeaning.Money.accentTint()
    val name = stringResource(
        when (tier) {
            KidUiTier.SEEDLING -> R.string.feature_wallet_tier_seedling
            KidUiTier.SPROUT -> R.string.feature_wallet_tier_sprout
            KidUiTier.SAPLING -> R.string.feature_wallet_tier_sapling
        },
    )
    Row(
        modifier = Modifier
            .background(tint.container, CircleShape)
            .padding(horizontal = SproutSpacing.md - SproutSpacing.xs, vertical = SproutSpacing.xs + 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SproutSpacing.sm),
    ) {
        Box(modifier = Modifier.size(8.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
        Text(
            text = stringResource(R.string.feature_wallet_tier_chip, name),
            style = MaterialTheme.typography.labelLarge,
            color = tint.content,
        )
    }
}

/**
 * The Honey screen-time badge (M9 §2.6), Sprout's hero: a sun, the minutes in the display face and "Screen time".
 * With [onClick] (kid mode) it is one button that opens the time bank; otherwise it only shows the balance. Read as
 * one node.
 */
@Composable
internal fun ScreenTimeBadge(minutes: BalanceUi, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    val tint = CardMeaning.Attention.accentTint()
    val shape = MaterialTheme.shapes.medium
    val border = BorderStroke(1.dp, SproutTheme.colors.honeyBorder)
    val content: @Composable () -> Unit = {
        Column(
            modifier = Modifier
                .heightIn(min = SproutTheme.tierTokens.minTouchTarget)
                .padding(horizontal = SproutSpacing.md, vertical = SproutSpacing.md - SproutSpacing.xs),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(SproutSpacing.xs),
        ) {
            Icon(SproutIcons.WbSunny, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
            Text(text = minutes.kidFormatted, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Text(
                text = stringResource(R.string.feature_wallet_screen_time),
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = modifier.semantics { role = Role.Button },
            shape = shape,
            color = tint.container,
            contentColor = tint.content,
            border = border,
            content = content,
        )
    } else {
        Surface(
            modifier = modifier.semantics(mergeDescendants = true) {},
            shape = shape,
            color = tint.container,
            contentColor = tint.content,
            border = border,
            content = content,
        )
    }
}

/**
 * The savings card: a [GoalBar] towards the kid's goal ("Saving for a new bike", "£27.50 to go"), or the savings
 * balance alone when there is no goal. On Seedling, [stars] replace the bar.
 */
@Composable
internal fun SavingsGoalCard(
    goal: SavingsGoalUi,
    modifier: Modifier = Modifier,
    stars: (@Composable () -> Unit)? = null,
) {
    SproutCard(modifier = modifier.fillMaxWidth(), meaning = CardMeaning.Goal) {
        val title = goal.title
        if (title == null) {
            Column(
                modifier = Modifier.semantics(mergeDescendants = true) {},
                verticalArrangement = Arrangement.spacedBy(SproutSpacing.xs),
            ) {
                Text(
                    text = stringResource(R.string.feature_wallet_savings),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(text = goal.savedText, style = MaterialTheme.typography.headlineSmall)
            }
        } else {
            GoalBar(
                title = stringResource(R.string.feature_wallet_saving_for, title),
                saved = goal.savedText,
                target = goal.targetText.orEmpty(),
                percent = goal.percent,
                caption = if (goal.isComplete) {
                    stringResource(R.string.feature_wallet_goal_reached)
                } else {
                    goal.remainingText?.let { stringResource(R.string.feature_wallet_goal_to_go, it) }
                },
                stars = stars,
            )
        }
    }
}

/**
 * The Chores (Botanical) and Shop pills, side by side (stacked at large font sizes, so neither label breaks mid-word).
 * Shop is Honey ([SproutButtonStyle.Secondary]) where colour may carry meaning and Linen
 * ([SproutButtonStyle.Tertiary]) on Sapling, as in the renders.
 */
@Composable
internal fun ChoresAndShopButtons(
    onOpenChores: () -> Unit,
    onOpenShop: () -> Unit,
    modifier: Modifier = Modifier,
    shopStyle: SproutButtonStyle = SproutButtonStyle.Secondary,
) {
    val chores = stringResource(R.string.feature_wallet_chores)
    val shop = stringResource(R.string.feature_wallet_shop)
    if (LocalDensity.current.fontScale >= STACK_FONT_SCALE) {
        Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SproutSpacing.sm)) {
            SproutButton(chores, onOpenChores, Modifier.fillMaxWidth(), icon = SproutIcons.Checklist)
            SproutButton(shop, onOpenShop, Modifier.fillMaxWidth(), style = shopStyle, icon = SproutIcons.ShoppingBag)
        }
    } else {
        Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(SproutSpacing.md)) {
            SproutButton(chores, onOpenChores, Modifier.weight(1f), icon = SproutIcons.Checklist)
            SproutButton(shop, onOpenShop, Modifier.weight(1f), style = shopStyle, icon = SproutIcons.ShoppingBag)
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

/**
 * [events] as [LedgerRow]s in one white card (M9 §3), each with [subtitle] under its title; "Nothing here yet."
 * without any.
 */
@Composable
internal fun HistoryCard(
    events: List<EventUi>,
    tier: KidUiTier,
    modifier: Modifier = Modifier,
    subtitle: @Composable (EventUi) -> String? = { it.note },
) {
    if (events.isEmpty()) {
        EmptyHistory(tier, modifier)
        return
    }
    SproutCard(modifier = modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
        events.forEachIndexed { index, event ->
            EventRow(event, tier, subtitle = subtitle(event), divider = index < events.lastIndex)
        }
    }
}

/**
 * One history row: the category's icon in a circle tinted by [rowKind], the title, [subtitle] (an excluded
 * game play leads it with why it is not counted) and the amount as [tier] words it. Read as "title, details, amount".
 */
@Composable
internal fun EventRow(
    event: EventUi,
    tier: KidUiTier,
    modifier: Modifier = Modifier,
    subtitle: String? = event.note,
    divider: Boolean = true,
) {
    val notCounted = event.excluded?.let { stringResource(it.textRes) }
    val title = stringResource(event.titleRes)
    val detail = listOfNotNull(notCounted, subtitle).joinToString(" · ").ifEmpty { null }
    val magnitude = if (event.amount.isNegative) -event.amount else event.amount
    val amount = ledgerAmountText(magnitude, event.currency, event.direction, tier, rememberMoneyFormatter())
    val description = stringResource(
        R.string.feature_wallet_event_row_description,
        listOfNotNull(title, detail).joinToString(", "),
        amount,
    )
    LedgerRow(
        icon = event.category.icon,
        kind = event.rowKind,
        title = title,
        detail = detail,
        amount = amount,
        modifier = modifier.clearAndSetSemantics { contentDescription = description },
        divider = divider,
    )
}

private val GameExclusion.textRes: Int
    get() = when (this) {
        GameExclusion.PLAYED_ON_OUT_OF_RANGE -> R.string.feature_wallet_game_not_counted_window
        GameExclusion.DAILY_CAP_EXCEEDED -> R.string.feature_wallet_game_not_counted_cap
    }

/** The hero balance of the Sprout render: 42 sp on a 48 sp line (D-M9-2), before the user's font scale. */
private val HeroAmountSize = 42.sp
private val HeroAmountLine = 48.sp
