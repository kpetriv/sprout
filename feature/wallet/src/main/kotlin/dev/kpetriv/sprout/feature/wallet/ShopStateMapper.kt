package dev.kpetriv.sprout.feature.wallet

import dev.kpetriv.sprout.core.database.RoomLedgerRepository
import dev.kpetriv.sprout.core.database.usecase.PurchaseResult
import dev.kpetriv.sprout.core.database.usecase.PurchaseStep
import dev.kpetriv.sprout.core.domainhousehold.AllowanceSchedule
import dev.kpetriv.sprout.core.domainhousehold.Reward
import dev.kpetriv.sprout.core.domainhousehold.RewardAffordability
import dev.kpetriv.sprout.core.domainledger.Account
import dev.kpetriv.sprout.core.domainledger.AccountKind
import dev.kpetriv.sprout.core.domainledger.AppendResult
import dev.kpetriv.sprout.core.domainledger.Balance
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.domainledger.Violation
import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.model.Kid
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.model.RewardId
import dev.kpetriv.sprout.core.ui.MoneyFormatter

/** What the data sources currently say about the shop for one kid. */
internal data class ShopSnapshot(
    val kid: Kid,
    val accounts: List<Account>,
    val balances: List<Balance>,
    val rewards: List<Reward>,
    val allowances: List<AllowanceSchedule>,
)

/** Screen-local shop state the data sources do not own (the wishlist lives in the SavedStateHandle). */
internal data class ShopLocalState(
    val selected: RewardId? = null,
    val isPurchasing: Boolean = false,
    val message: ShopMessage? = null,
)

/** Pure mapping from catalogue, balances and allowance to [ShopUiState]; no Android, no coroutines. */
internal class ShopStateMapper(private val formatter: MoneyFormatter) {
    fun ready(
        snapshot: ShopSnapshot,
        local: ShopLocalState,
        wishlist: Set<String>,
        mode: WalletMode,
        today: CalendarDate,
    ): ShopUiState.Ready {
        val tier = snapshot.kid.uiTier
        val main = snapshot.accounts.firstOrNull { it.kind == AccountKind.MAIN && it.currency is Currency.Fiat }
        val items = snapshot.rewards.map { reward -> item(reward, snapshot, wishlist, tier, today) }
        return ShopUiState.Ready(
            kid = snapshot.kid,
            tier = tier,
            balanceText = main?.let { formatter.formatForTier(balanceOf(it, snapshot.balances), it.currency, tier) },
            items = items,
            canPurchase = mode == WalletMode.KID,
            hasAllowance = snapshot.allowances.any { it.active },
            // A reward that left the catalogue while its dialog was open closes the dialog.
            selected = local.selected?.let { selected -> items.firstOrNull { it.rewardId == selected } },
            isPurchasing = local.isPurchasing,
            message = local.message,
        )
    }

    private fun item(
        reward: Reward,
        snapshot: ShopSnapshot,
        wishlist: Set<String>,
        tier: KidUiTier,
        today: CalendarDate,
    ): ShopItemUi {
        val account = snapshot.accounts.firstOrNull { it.kind == AccountKind.MAIN && it.currency == reward.currency }
        val balance = account?.let { balanceOf(it, snapshot.balances) } ?: Money.ZERO
        val affordability = RewardAffordability.of(reward, balance)
        val days = if (affordability.canAfford) {
            0
        } else {
            allowanceFor(reward, snapshot.allowances)
                ?.let { affordability.daysUntil(it, today) }
        }
        return ShopItemUi(
            rewardId = reward.id,
            title = reward.title,
            kind = reward.kind,
            priceText = formatter.formatPriceForTier(reward.cost, reward.currency, tier),
            canAfford = affordability.canAfford,
            shortfallText = affordability.shortfall.takeUnless { affordability.canAfford }
                ?.let { formatter.formatPriceForTier(it, reward.currency, tier) },
            daysUntil = days,
            weeksToAfford = days?.let { (it + DAYS_PER_WEEK - 1) / DAYS_PER_WEEK },
            minutesText = reward.minutesGranted?.let { formatter.formatMinutes(it.toLong()) },
            wishlisted = reward.id.value in wishlist,
        )
    }

    /** The success or failure message for [result] of buying [reward] as a kid on [tier]. */
    fun messageFor(result: PurchaseResult, reward: Reward, tier: KidUiTier): ShopMessage = when (result) {
        is PurchaseResult.Purchased -> ShopMessage.Purchased(
            title = reward.title,
            minutesText = reward.minutesGranted?.let { formatter.formatMinutes(it.toLong()) },
            // The credit goes in with the purchase (appendAllAsKid); a missing one is owed and redeemed later.
            minutesPending = reward.minutesGranted != null && result.timeCreditId == null,
        )

        PurchaseResult.Unavailable -> ShopMessage.Failed(PurchaseFailure.CatalogueChanged)

        PurchaseResult.NoAccount -> ShopMessage.Failed(PurchaseFailure.NoAccount)

        is PurchaseResult.Refused -> ShopMessage.Failed(
            when (result.step) {
                PurchaseStep.PURCHASE -> refusal(result.result, reward, tier)

                // The minutes could not be credited, so the purchase was rolled back with them: nothing was spent.
                PurchaseStep.TIME_CREDIT -> PurchaseFailure.Failed
            },
        )
    }

    private fun refusal(result: AppendResult, reward: Reward, tier: KidUiTier): PurchaseFailure {
        val rejected = result as? AppendResult.Rejected
        val funds = rejected?.violations?.firstNotNullOfOrNull { it as? Violation.InsufficientFunds }
        return when {
            rejected?.reason == RoomLedgerRepository.CATALOGUE_REJECTION -> PurchaseFailure.CatalogueChanged

            funds != null -> {
                val shortfall = funds.requested - funds.available
                PurchaseFailure.NotEnoughMoney(
                    shortfall.takeIf { it.isPositive }?.let { formatter.formatPriceForTier(it, reward.currency, tier) },
                )
            }

            else -> PurchaseFailure.Failed
        }
    }

    private fun balanceOf(account: Account, balances: List<Balance>): Money =
        balances.firstOrNull { it.accountId == account.id }?.amount ?: Money.ZERO

    /** The kid's active allowance in the reward's currency, else any active one (which then cannot help). */
    private fun allowanceFor(reward: Reward, allowances: List<AllowanceSchedule>): AllowanceSchedule? =
        allowances.firstOrNull { it.active && it.currency == reward.currency } ?: allowances.firstOrNull { it.active }

    private companion object {
        const val DAYS_PER_WEEK = 7
    }
}
