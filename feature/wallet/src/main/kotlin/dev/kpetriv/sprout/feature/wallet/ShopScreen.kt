package dev.kpetriv.sprout.feature.wallet

import android.os.Bundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kpetriv.sprout.core.designsystem.theme.ProvideKidTier
import dev.kpetriv.sprout.core.designsystem.theme.SproutTheme
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.model.RewardKind
import dev.kpetriv.sprout.core.ui.TierScaledText
import dev.kpetriv.sprout.core.ui.TierTextRole

/**
 * Navigation entry for the rewards shop of [kidId]. Creates a [ShopViewModel] scoped to the current
 * `ViewModelStoreOwner` (the nav entry), passing [kidId] and [mode] through its `SavedStateHandle`.
 *
 * @param mode [WalletMode.KID] lets the kid buy; [WalletMode.PARENT] only browses.
 */
@Composable
fun ShopRoute(kidId: KidId, mode: WalletMode, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val viewModel = featureViewModel<ShopViewModel>(
        key = "shop:${kidId.value}:${mode.name}",
        args = Bundle().apply {
            putString(ShopViewModel.KID_ID_KEY, kidId.value)
            putString(ShopViewModel.MODE_KEY, mode.name)
        },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    ShopScreen(state = state, onEvent = viewModel::onEvent, modifier = modifier, onBack = onBack)
}

/**
 * The shop for any [ShopUiState]. Ready content renders inside [ProvideKidTier] for the kid's tier and switches to
 * [SeedlingShopContent] (picture tiles), [SproutShopContent] (prices and "days until") or [SaplingShopContent] (full
 * catalogue, wishlist and savings plans). The confirm dialog and the purchase outcome dialog use the tier's touch
 * target, so Seedling gets big buttons.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShopScreen(
    state: ShopUiState,
    onEvent: (ShopEvent) -> Unit,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
) {
    val ready = state as? ShopUiState.Ready
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = ready?.let { stringResource(R.string.feature_wallet_shop_title_named, it.kid.firstName) }
                            ?: stringResource(R.string.feature_wallet_shop_title),
                        modifier = Modifier.semantics { heading() },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.feature_wallet_ic_arrow_back),
                            contentDescription = stringResource(R.string.feature_wallet_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (state) {
                ShopUiState.Loading -> ShopLoading()
                is ShopUiState.Error -> ShopErrorContent(state.reason, onRetry = { onEvent(ShopEvent.Refresh) })
                is ShopUiState.Ready -> ProvideKidTier(state.tier) { ShopReadyContent(state, onEvent) }
            }
        }
    }
    if (ready != null) {
        ProvideKidTier(ready.tier) {
            ready.selected?.let { item -> ConfirmDialog(item, ready.tier, ready.isPurchasing, onEvent) }
            ready.message?.let { message -> OutcomeDialog(message, ready.tier) { onEvent(ShopEvent.MessageShown) } }
        }
    }
}

@Composable
private fun ShopReadyContent(state: ShopUiState.Ready, onEvent: (ShopEvent) -> Unit) {
    val select: (ShopItemUi) -> Unit = { onEvent(ShopEvent.Select(it.rewardId)) }
    when (state.tier) {
        KidUiTier.SEEDLING -> SeedlingShopContent(state, onSelect = select)

        KidUiTier.SPROUT -> SproutShopContent(state, onSelect = select)

        KidUiTier.SAPLING -> SaplingShopContent(
            state = state,
            onSelect = select,
            onToggleWishlist = { onEvent(ShopEvent.ToggleWishlist(it.rewardId)) },
        )
    }
}

/** Decorative picture for a reward; the title carries the meaning for accessibility. */
internal val RewardKind.icon: String
    get() = when (this) {
        RewardKind.SCREEN_TIME -> "📺"
        RewardKind.TREAT -> "🍦"
        RewardKind.OUTING -> "🎡"
        RewardKind.OTHER -> "🎁"
    }

/** The status line under a reward on Sprout: ready to buy, days of pocket money to go, or the amount missing. */
@Composable
internal fun ShopItemUi.affordabilityText(): String {
    val days = daysUntil
    return when {
        canAfford -> stringResource(R.string.feature_wallet_shop_can_afford)

        days != null && shortfallText != null ->
            pluralStringResource(
                R.plurals.feature_wallet_shop_days_until,
                days,
                shortfallText,
                days,
            )

        shortfallText != null -> stringResource(R.string.feature_wallet_shop_need_more, shortfallText)

        else -> stringResource(R.string.feature_wallet_shop_cannot_afford)
    }
}

/** The Sapling savings plan line: weeks of pocket money to go; null once the reward is affordable. */
@Composable
internal fun ShopItemUi.savingsPlanText(): String? {
    val weeks = weeksToAfford
    return when {
        canAfford -> null
        weeks != null -> pluralStringResource(R.plurals.feature_wallet_shop_savings_plan, weeks, weeks)
        else -> stringResource(R.string.feature_wallet_shop_savings_plan_none)
    }
}

@Composable
internal fun ShopBalanceHeader(state: ShopUiState.Ready, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        state.balanceText?.let {
            TierScaledText(
                text = stringResource(R.string.feature_wallet_shop_balance, it),
                role = TierTextRole.Title,
                tier = state.tier,
                modifier = Modifier.semantics { heading() },
            )
        }
        if (!state.canPurchase) {
            TierScaledText(
                text = stringResource(R.string.feature_wallet_shop_parent_preview),
                role = TierTextRole.Label,
                tier = state.tier,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.items.isEmpty()) {
            TierScaledText(
                text = stringResource(R.string.feature_wallet_shop_empty),
                role = TierTextRole.Body,
                tier = state.tier,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Confirm (affordable) or "not yet" (too expensive) for [item]; buttons at least the tier's touch target. */
@Composable
private fun ConfirmDialog(item: ShopItemUi, tier: KidUiTier, isPurchasing: Boolean, onEvent: (ShopEvent) -> Unit) {
    val dismiss = { onEvent(ShopEvent.DismissConfirm) }
    if (item.canAfford) {
        ShopDialog(
            icon = item.kind.icon,
            title = stringResource(R.string.feature_wallet_shop_confirm_title, item.title),
            body = stringResource(R.string.feature_wallet_shop_confirm_body, item.priceText),
            tier = tier,
            onDismiss = dismiss,
        ) {
            DialogButton(onClick = { onEvent(ShopEvent.ConfirmPurchase) }, enabled = !isPurchasing, primary = true) {
                if (isPurchasing) {
                    val buying = stringResource(R.string.feature_wallet_shop_buying)
                    CircularProgressIndicator(modifier = Modifier.semantics { contentDescription = buying })
                } else {
                    Text(stringResource(R.string.feature_wallet_shop_confirm_yes))
                }
            }
            DialogButton(onClick = dismiss, enabled = !isPurchasing, primary = false) {
                Text(stringResource(R.string.feature_wallet_shop_confirm_no))
            }
        }
    } else {
        ShopDialog(
            icon = item.kind.icon,
            title = stringResource(R.string.feature_wallet_shop_not_yet_title),
            body = item.affordabilityText(),
            tier = tier,
            onDismiss = dismiss,
        ) {
            DialogButton(onClick = dismiss, enabled = true, primary = true) {
                Text(stringResource(R.string.feature_wallet_shop_ok))
            }
        }
    }
}

@Composable
private fun OutcomeDialog(message: ShopMessage, tier: KidUiTier, onDismiss: () -> Unit) {
    val (title, body) = when (message) {
        is ShopMessage.Purchased -> stringResource(R.string.feature_wallet_shop_enjoy) to listOfNotNull(
            stringResource(R.string.feature_wallet_shop_bought, message.title),
            message.minutesText?.let {
                if (message.minutesPending) {
                    stringResource(R.string.feature_wallet_shop_minutes_pending)
                } else {
                    stringResource(R.string.feature_wallet_shop_minutes_added, it)
                }
            },
        ).joinToString("\n")

        is ShopMessage.Failed -> stringResource(R.string.feature_wallet_shop_oops) to message.failure.message()
    }
    ShopDialog(
        icon = if (message is ShopMessage.Purchased) "🎉" else "🙈",
        title = title,
        body = body,
        tier = tier,
        onDismiss = onDismiss,
    ) {
        DialogButton(onClick = onDismiss, enabled = true, primary = true) {
            Text(stringResource(R.string.feature_wallet_shop_ok))
        }
    }
}

@Composable
internal fun PurchaseFailure.message(): String = when (this) {
    PurchaseFailure.CatalogueChanged -> stringResource(R.string.feature_wallet_shop_failed_catalogue)

    is PurchaseFailure.NotEnoughMoney -> shortfallText?.let {
        stringResource(R.string.feature_wallet_shop_need_more, it)
    } ?: stringResource(R.string.feature_wallet_shop_failed_funds)

    PurchaseFailure.NoAccount -> stringResource(R.string.feature_wallet_shop_failed_no_account)

    PurchaseFailure.NotAllowed -> stringResource(R.string.feature_wallet_shop_failed_not_allowed)

    PurchaseFailure.Failed -> stringResource(R.string.feature_wallet_shop_failed)
}

/** A centred dialog: big picture, title, body, then [buttons] stacked full width. */
@Composable
private fun ShopDialog(
    icon: String,
    title: String,
    body: String,
    tier: KidUiTier,
    onDismiss: () -> Unit,
    buttons: @Composable () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(text = icon, style = MaterialTheme.typography.displayMedium)
                TierScaledText(
                    text = title,
                    role = TierTextRole.Title,
                    tier = tier,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { heading() },
                )
                TierScaledText(
                    text = body,
                    role = TierTextRole.Body,
                    tier = tier,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                buttons()
            }
        }
    }
}

@Composable
private fun DialogButton(onClick: () -> Unit, enabled: Boolean, primary: Boolean, content: @Composable () -> Unit) {
    val modifier = Modifier
        .fillMaxWidth()
        .heightIn(min = SproutTheme.tierTokens.minTouchTarget)
    if (primary) {
        Button(onClick = onClick, enabled = enabled, modifier = modifier) { content() }
    } else {
        OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier) { content() }
    }
}

@Composable
private fun ShopLoading() {
    val description = stringResource(R.string.feature_wallet_shop_loading)
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(modifier = Modifier.semantics { contentDescription = description })
    }
}

@Composable
private fun ShopErrorContent(reason: ShopError, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(
                when (reason) {
                    ShopError.KID_NOT_FOUND -> R.string.feature_wallet_shop_error_kid_not_found
                    ShopError.LOAD_FAILED -> R.string.feature_wallet_shop_error_load_failed
                },
            ),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        Button(onClick = onRetry) { Text(stringResource(R.string.feature_wallet_retry)) }
    }
}
