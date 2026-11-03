package dev.kpetriv.sprout.feature.wallet

import dev.kpetriv.sprout.core.model.Kid
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.model.RewardId
import dev.kpetriv.sprout.core.model.RewardKind

/** Everything the shop screen shows. One instance per emission of [ShopViewModel.state]. */
sealed interface ShopUiState {
    data object Loading : ShopUiState

    /**
     * @property balanceText the kid's money balance as their tier reads it ("7 coins", "£7.45"); null without a money
     *   account.
     * @property items the rewards the kid can see, cheapest first.
     * @property canPurchase the kid is using the shop ([WalletMode.KID]); a parent only browses.
     * @property hasAllowance the kid has an active allowance, so "days until" and savings plans can be estimated.
     * @property selected the reward whose confirm dialog is open.
     * @property isPurchasing a purchase of [selected] is being written.
     * @property message the outcome of the last purchase, shown until [ShopEvent.MessageShown].
     */
    data class Ready(
        val kid: Kid,
        val tier: KidUiTier,
        val balanceText: String?,
        val items: List<ShopItemUi>,
        val canPurchase: Boolean,
        val hasAllowance: Boolean,
        val selected: ShopItemUi? = null,
        val isPurchasing: Boolean = false,
        val message: ShopMessage? = null,
    ) : ShopUiState {
        /** The Sapling wishlist, in catalogue order. */
        val wishlist: List<ShopItemUi> get() = items.filter { it.wishlisted }
    }

    data class Error(val reason: ShopError) : ShopUiState
}

/** Why the shop could not load. */
enum class ShopError {
    KID_NOT_FOUND,
    LOAD_FAILED,
}

/**
 * One reward in the shop.
 *
 * @property priceText the cost as the kid's tier reads it ("4 coins", "£3.99").
 * @property shortfallText how much more the kid needs, in the same wording; null when [canAfford].
 * @property daysUntil days until the kid's allowance alone covers the cost
 *   ([dev.kpetriv.sprout.core.domainhousehold.RewardAffordability.daysUntil]): 0 when affordable now, null when the
 *   allowance cannot help (none, inactive, another currency, or too far off).
 * @property weeksToAfford the Sapling savings plan: [daysUntil] in whole weeks, rounded up; 0 when affordable now.
 * @property minutesText for a screen-time reward, the minutes it grants ("30 min").
 * @property wishlisted on the Sapling wishlist.
 */
data class ShopItemUi(
    val rewardId: RewardId,
    val title: String,
    val kind: RewardKind,
    val priceText: String,
    val canAfford: Boolean,
    val shortfallText: String?,
    val daysUntil: Int?,
    val weeksToAfford: Int?,
    val minutesText: String?,
    val wishlisted: Boolean = false,
)

/** The outcome of a purchase, shown in a dialog and acknowledged with [ShopEvent.MessageShown]. */
sealed interface ShopMessage {
    /**
     * Bought. For a screen-time reward, [minutesText] is what was credited to the time bank; [minutesPending] when
     * the purchase went through but the time credit did not (yet).
     */
    data class Purchased(val title: String, val minutesText: String?, val minutesPending: Boolean = false) :
        ShopMessage

    data class Failed(val failure: PurchaseFailure) : ShopMessage
}

/** Why a purchase did not happen, in terms a kid can act on. */
sealed interface PurchaseFailure {
    /** The reward changed (price, minutes) or was removed between showing it and buying it. */
    data object CatalogueChanged : PurchaseFailure

    /** Not enough money; [shortfallText] (tier wording) is how much more is needed, when known. */
    data class NotEnoughMoney(val shortfallText: String?) : PurchaseFailure

    /** The kid has no account to pay from (or, for screen time, to credit). */
    data object NoAccount : PurchaseFailure

    /** A parent is browsing; purchases happen in kid mode. */
    data object NotAllowed : PurchaseFailure

    /** Anything else: storage failure, a clash with another device. */
    data object Failed : PurchaseFailure
}

/** Everything the shop screen can ask the [ShopViewModel] to do. */
sealed interface ShopEvent {
    data object Refresh : ShopEvent

    /** Opens the confirm dialog for [rewardId]. */
    data class Select(val rewardId: RewardId) : ShopEvent

    data object DismissConfirm : ShopEvent

    /** Buys the [ShopUiState.Ready.selected] reward. */
    data object ConfirmPurchase : ShopEvent

    /** Adds [rewardId] to, or removes it from, the wishlist. */
    data class ToggleWishlist(val rewardId: RewardId) : ShopEvent

    /** The current [ShopUiState.Ready.message] has been shown. */
    data object MessageShown : ShopEvent
}
