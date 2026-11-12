package dev.kpetriv.sprout.feature.wallet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.kpetriv.sprout.core.designsystem.components.CardMeaning
import dev.kpetriv.sprout.core.designsystem.components.Pictogram
import dev.kpetriv.sprout.core.designsystem.components.SproutButton
import dev.kpetriv.sprout.core.designsystem.components.SproutCard
import dev.kpetriv.sprout.core.designsystem.icon.SproutIcons
import dev.kpetriv.sprout.core.designsystem.theme.ColourLevel
import dev.kpetriv.sprout.core.designsystem.theme.SproutSpacing
import dev.kpetriv.sprout.core.designsystem.theme.SproutTheme
import dev.kpetriv.sprout.core.designsystem.theme.amount
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.model.RewardKind
import dev.kpetriv.sprout.core.ui.AdaptiveColumns
import dev.kpetriv.sprout.core.ui.ContentWidth
import dev.kpetriv.sprout.core.ui.TierScaledText
import dev.kpetriv.sprout.core.ui.TierTextRole
import dev.kpetriv.sprout.core.ui.withGutters

/**
 * Seedling shop, as the Morning garden render: picture tiles ([SproutCard] with a [Pictogram], the price in the
 * display face), tinted with a tick and "You can buy this now" when the kid can buy the reward and white with a piggy
 * bank and "Keep saving" when not (never colour alone, M7 §8). Two columns on a phone, more on a wider window (tiles
 * at least [SEEDLING_TILE_MIN_WIDTH], the grid capped at [GRID_MAX_WIDTH], D-M7-2). Tapping a tile opens the confirm
 * (or "not yet") dialog; a parent browsing cannot tap.
 */
@Composable
internal fun SeedlingShopContent(
    state: ShopUiState.Ready,
    onSelect: (ShopItemUi) -> Unit,
    modifier: Modifier = Modifier,
) {
    ContentWidth(modifier = modifier.fillMaxSize(), max = GRID_MAX_WIDTH) { gutters ->
        LazyVerticalGrid(
            columns = AdaptiveColumns(minColumnWidth = SEEDLING_TILE_MIN_WIDTH, minColumns = SEEDLING_COLUMNS),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = SproutSpacing.gutter, vertical = SproutSpacing.md)
                .withGutters(gutters),
            horizontalArrangement = Arrangement.spacedBy(SproutSpacing.md),
            verticalArrangement = Arrangement.spacedBy(SproutSpacing.md),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) { ShopBalanceHeader(state) }
            items(state.items, key = { it.rewardId.value }) { item ->
                PictureTile(item, enabled = state.canPurchase, onClick = { onSelect(item) })
            }
        }
    }
}

@Composable
private fun PictureTile(item: ShopItemUi, enabled: Boolean, onClick: () -> Unit) {
    val status = stringResource(
        if (item.canAfford) R.string.feature_wallet_shop_can_afford else R.string.feature_wallet_shop_cannot_afford,
    )
    val description = listOf(item.title, item.priceText, status).joinToString(", ")
    SproutCard(
        meaning = if (item.canAfford) item.kind.tileMeaning else CardMeaning.Content,
        onClick = onClick.takeIf { enabled },
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(SproutSpacing.md),
    ) {
        // One description for the whole tile; the card itself keeps its click action and button role.
        Column(
            modifier = Modifier.clearAndSetSemantics { contentDescription = description },
            verticalArrangement = Arrangement.spacedBy(SproutSpacing.md - SproutSpacing.xs),
        ) {
            Pill {
                Box(Modifier.size(PriceDot).background(MaterialTheme.colorScheme.secondary, CircleShape))
                Text(text = item.priceText, style = MaterialTheme.typography.amount)
            }
            Pictogram(
                item.kind.pictogram,
                size = TilePictogramSize,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Text(text = item.title, style = MaterialTheme.typography.titleLarge)
            Pill(modifier = Modifier.fillMaxWidth()) {
                Icon(
                    imageVector = if (item.canAfford) SproutIcons.CheckCircle else SproutIcons.Savings,
                    contentDescription = null,
                    tint = if (item.canAfford) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Text(text = status, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            }
        }
    }
}

/**
 * The Seedling tile's tint when the kid can buy it (Leaf, Sky or Honey on `FULL`, white elsewhere); a reward the kid
 * cannot buy yet stays white, and its "Keep saving" pill says so in words.
 */
private val RewardKind.tileMeaning: CardMeaning
    get() = when (this) {
        RewardKind.SCREEN_TIME -> CardMeaning.Time
        RewardKind.TREAT -> CardMeaning.Attention
        RewardKind.OUTING -> CardMeaning.Goal
        RewardKind.OTHER -> CardMeaning.Money
    }

/** A small white card with a hairline: a tile's price and its "can buy" line. */
@Composable
private fun Pill(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    SproutCard(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = SproutSpacing.md - SproutSpacing.xs, vertical = SproutSpacing.sm),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SproutSpacing.sm),
            content = content,
        )
    }
}

/** Sprout shop: a list with prices, whether the kid can buy each reward now, and if not, the days of pocket money. */
@Composable
internal fun SproutShopContent(
    state: ShopUiState.Ready,
    onSelect: (ShopItemUi) -> Unit,
    modifier: Modifier = Modifier,
) {
    ContentWidth(modifier = modifier.fillMaxSize()) { gutters ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = SproutSpacing.gutter, vertical = SproutSpacing.md)
                .withGutters(gutters),
            verticalArrangement = Arrangement.spacedBy(SproutSpacing.md),
        ) {
            item { ShopBalanceHeader(state) }
            items(state.items, key = { it.rewardId.value }) { item ->
                RewardRow(item, KidUiTier.SPROUT, state.canPurchase, onBuy = { onSelect(item) })
            }
        }
    }
}

/**
 * Sapling shop: the wishlist first, then the full catalogue; every reward that is not affordable yet gets a savings
 * plan (weeks of pocket money at the current allowance).
 */
@Composable
internal fun SaplingShopContent(
    state: ShopUiState.Ready,
    onSelect: (ShopItemUi) -> Unit,
    onToggleWishlist: (ShopItemUi) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tier = KidUiTier.SAPLING
    ContentWidth(modifier = modifier.fillMaxSize()) { gutters ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = SproutSpacing.gutter, vertical = SproutSpacing.md)
                .withGutters(gutters),
            verticalArrangement = Arrangement.spacedBy(SproutSpacing.md),
        ) {
            item { ShopBalanceHeader(state) }
            if (state.items.isNotEmpty()) {
                item { SectionHeading(stringResource(R.string.feature_wallet_shop_wishlist), tier) }
                val wishlist = state.wishlist
                if (wishlist.isEmpty()) {
                    item {
                        TierScaledText(
                            text = stringResource(R.string.feature_wallet_shop_wishlist_empty),
                            role = TierTextRole.Body,
                            tier = tier,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    items(wishlist, key = { "wish-${it.rewardId.value}" }) { item ->
                        RewardRow(item, tier, state.canPurchase, { onSelect(item) }, onToggleWishlist = {
                            onToggleWishlist(item)
                        })
                    }
                }
                item { SectionHeading(stringResource(R.string.feature_wallet_shop_all_rewards), tier) }
                items(state.items, key = { it.rewardId.value }) { item ->
                    RewardRow(item, tier, state.canPurchase, { onSelect(item) }, onToggleWishlist = {
                        onToggleWishlist(item)
                    })
                }
            }
        }
    }
}

/**
 * One reward in the Sprout and Sapling lists: picture, title, price (display face), the screen time it grants, the
 * affordability line (Sprout) or savings plan (Sapling), a Buy pill (kid mode, enabled when affordable) and, with
 * [onToggleWishlist], a wishlist heart.
 */
@Composable
private fun RewardRow(
    item: ShopItemUi,
    tier: KidUiTier,
    canPurchase: Boolean,
    onBuy: () -> Unit,
    onToggleWishlist: (() -> Unit)? = null,
) {
    SproutCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(SproutSpacing.sm)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(SproutSpacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Pictogram(item.kind.pictogram, size = RowPictogramSize)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(SproutSpacing.xs)) {
                    TierScaledText(text = item.title, role = TierTextRole.Body, tier = tier)
                    Text(text = item.priceText, style = MaterialTheme.typography.titleLarge)
                }
                if (onToggleWishlist != null) WishlistToggle(item, onToggleWishlist)
            }
            item.minutesText?.let {
                TierScaledText(
                    text = stringResource(R.string.feature_wallet_shop_grants_minutes, it),
                    role = TierTextRole.Label,
                    tier = tier,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val status = if (tier == KidUiTier.SAPLING) item.savingsPlanText() else item.affordabilityText()
            if (status != null || canPurchase) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(SproutSpacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        status?.let {
                            TierScaledText(
                                text = it,
                                role = TierTextRole.Label,
                                tier = tier,
                                color = if (item.canAfford) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                    if (canPurchase) {
                        SproutButton(
                            text = stringResource(R.string.feature_wallet_shop_buy),
                            onClick = onBuy,
                            enabled = item.canAfford,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WishlistToggle(item: ShopItemUi, onToggle: () -> Unit) {
    val label = if (item.wishlisted) {
        R.string.feature_wallet_shop_wishlist_remove
    } else {
        R.string.feature_wallet_shop_wishlist_add
    }
    val description = stringResource(label, item.title)
    val scheme = MaterialTheme.colorScheme
    IconToggleButton(
        checked = item.wishlisted,
        onCheckedChange = { onToggle() },
        modifier = Modifier.semantics { contentDescription = description },
    ) {
        Icon(
            imageVector = if (item.wishlisted) SproutIcons.FavoriteFilled else SproutIcons.Favorite,
            contentDescription = null,
            // Filled or outlined carries the state; the colour is Botanical, or Ink where colour is restrained.
            tint = if (SproutTheme.tierTokens.colourLevel ==
                ColourLevel.RESTRAINED
            ) {
                scheme.onSurface
            } else {
                scheme.primary
            },
        )
    }
}

private const val SEEDLING_COLUMNS = 2
private val PriceDot = 10.dp
private val TilePictogramSize = 88.dp
private val RowPictogramSize = 56.dp
private val SEEDLING_TILE_MIN_WIDTH = 160.dp
private val GRID_MAX_WIDTH = 960.dp
