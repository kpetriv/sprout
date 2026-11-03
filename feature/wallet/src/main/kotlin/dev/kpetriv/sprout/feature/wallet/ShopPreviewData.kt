package dev.kpetriv.sprout.feature.wallet

import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.model.RewardId
import dev.kpetriv.sprout.core.model.RewardKind
import dev.kpetriv.sprout.core.ui.MoneyFormatter
import java.util.Locale

/** Fixed shop states for previews, screen tests and screenshot goldens. */
internal object ShopPreviewData {
    private val gbp = Currency.Fiat("GBP")
    private val formatter = MoneyFormatter(Locale.UK)
    private const val RICH_MINOR = 2_000L
    private const val POOR_MINOR = 120L
    private const val WEEKLY_ALLOWANCE_MINOR = 200L
    private const val DAYS_PER_WEEK = 7

    private class PreviewReward(
        val id: String,
        val title: String,
        val kind: RewardKind,
        val cost: Long,
        val minutes: Int?,
    )

    private val catalogue = listOf(
        PreviewReward("reward-sticker", "Sticker pack", RewardKind.OTHER, 150, null),
        PreviewReward("reward-tablet", "30 min tablet time", RewardKind.SCREEN_TIME, 300, 30),
        PreviewReward("reward-ice-cream", "Ice cream", RewardKind.TREAT, 450, null),
        PreviewReward("reward-cinema", "Cinema trip", RewardKind.OUTING, 1_200, null),
    )

    /**
     * The shop on [tier]: with [affordable] the kid has £20.00 and can buy everything; otherwise £1.20, so nothing is
     * affordable and each reward shows how many weekly £2.00 payments it needs (paid on the next 7th day).
     */
    fun ready(
        tier: KidUiTier,
        affordable: Boolean,
        canPurchase: Boolean = true,
        wishlist: Set<String> = setOf("reward-cinema"),
    ): ShopUiState.Ready {
        val balance = if (affordable) RICH_MINOR else POOR_MINOR
        val items = catalogue.map { reward ->
            val shortfall = (reward.cost - balance).coerceAtLeast(0)
            val payments = (shortfall + WEEKLY_ALLOWANCE_MINOR - 1) / WEEKLY_ALLOWANCE_MINOR
            val days = (payments * DAYS_PER_WEEK).toInt()
            ShopItemUi(
                rewardId = RewardId(reward.id),
                title = reward.title,
                kind = reward.kind,
                priceText = formatter.formatPriceForTier(Money(reward.cost), gbp, tier),
                canAfford = shortfall == 0L,
                shortfallText = shortfall.takeIf { it > 0 }?.let { formatter.formatPriceForTier(Money(it), gbp, tier) },
                daysUntil = days,
                weeksToAfford = (days + DAYS_PER_WEEK - 1) / DAYS_PER_WEEK,
                minutesText = reward.minutes?.let { formatter.formatMinutes(it.toLong()) },
                wishlisted = reward.id in wishlist,
            )
        }
        return ShopUiState.Ready(
            kid = WalletPreviewData.kid(tier),
            tier = tier,
            balanceText = formatter.formatForTier(Money(balance), gbp, tier),
            items = items,
            canPurchase = canPurchase,
            hasAllowance = true,
        )
    }
}
