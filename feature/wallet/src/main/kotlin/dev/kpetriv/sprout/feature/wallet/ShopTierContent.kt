package dev.kpetriv.sprout.feature.wallet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.kpetriv.sprout.core.designsystem.theme.SproutTheme
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.ui.AdaptiveColumns
import dev.kpetriv.sprout.core.ui.ContentWidth
import dev.kpetriv.sprout.core.ui.TierScaledText
import dev.kpetriv.sprout.core.ui.TierTextRole
import dev.kpetriv.sprout.core.ui.withGutters

/**
 * Seedling shop: big picture tiles, green with a tick when the kid can buy the reward and grey with a piggy bank when
 * not (never colour alone, M7 §8). Two columns on a phone, more on a wider window (tiles at least
 * [SEEDLING_TILE_MIN_WIDTH], the grid capped at [GRID_MAX_WIDTH], D-M7-2). Tapping a tile opens the confirm (or "not
 * yet") dialog; a parent browsing cannot tap.
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
            contentPadding = PaddingValues(24.dp).withGutters(gutters),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
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
    val tier = KidUiTier.SEEDLING
    val status = stringResource(
        if (item.canAfford) R.string.feature_wallet_shop_can_afford else R.string.feature_wallet_shop_cannot_afford,
    )
    val description = listOf(item.title, item.priceText, status).joinToString(", ")
    val colors = if (item.canAfford) {
        CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            disabledContainerColor = MaterialTheme.colorScheme.primaryContainer,
            disabledContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    } else {
        CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Card(
        onClick = onClick,
        enabled = enabled,
        colors = colors,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = SproutTheme.tierTokens.minTouchTarget * 2)
            .clearAndSetSemantics { contentDescription = description },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = item.kind.icon, style = MaterialTheme.typography.displayMedium)
            TierScaledText(text = item.title, role = TierTextRole.Body, tier = tier, textAlign = TextAlign.Center)
            TierScaledText(text = item.priceText, role = TierTextRole.Label, tier = tier, textAlign = TextAlign.Center)
            TierScaledText(
                text = (if (item.canAfford) "✓ " else "🐷 ") + status,
                role = TierTextRole.Label,
                tier = tier,
                textAlign = TextAlign.Center,
            )
        }
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
            contentPadding = PaddingValues(16.dp).withGutters(gutters),
            verticalArrangement = Arrangement.spacedBy(12.dp),
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
            contentPadding = PaddingValues(16.dp).withGutters(gutters),
            verticalArrangement = Arrangement.spacedBy(12.dp),
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
 * One reward in the Sprout and Sapling lists: picture, title, price, the affordability line (Sprout) or savings plan
 * (Sapling), the screen time it grants, a Buy button (kid mode, enabled when affordable) and, with
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
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = item.kind.icon,
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.clearAndSetSemantics { },
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TierScaledText(text = item.title, role = TierTextRole.Body, tier = tier)
                TierScaledText(text = item.priceText, role = TierTextRole.Title, tier = tier)
                item.minutesText?.let {
                    TierScaledText(
                        text = stringResource(R.string.feature_wallet_shop_grants_minutes, it),
                        role = TierTextRole.Label,
                        tier = tier,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val status = if (tier == KidUiTier.SAPLING) item.savingsPlanText() else item.affordabilityText()
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
            if (onToggleWishlist != null) WishlistToggle(item, onToggleWishlist)
            if (canPurchase) {
                Button(
                    onClick = onBuy,
                    enabled = item.canAfford,
                    modifier = Modifier.heightIn(min = SproutTheme.tierTokens.minTouchTarget),
                ) {
                    Text(stringResource(R.string.feature_wallet_shop_buy))
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
    val colors = MaterialTheme.colorScheme
    IconToggleButton(
        checked = item.wishlisted,
        onCheckedChange = { onToggle() },
        modifier = Modifier.semantics { contentDescription = description },
    ) {
        Text(
            text = if (item.wishlisted) "♥" else "♡",
            style = MaterialTheme.typography.headlineSmall,
            color = if (item.wishlisted) colors.error else colors.onSurfaceVariant,
            modifier = Modifier.clearAndSetSemantics { },
        )
    }
}

private const val SEEDLING_COLUMNS = 2
private val SEEDLING_TILE_MIN_WIDTH = 160.dp
private val GRID_MAX_WIDTH = 960.dp
