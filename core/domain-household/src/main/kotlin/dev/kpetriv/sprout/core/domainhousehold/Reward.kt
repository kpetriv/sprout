package dev.kpetriv.sprout.core.domainhousehold

import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.RewardId
import dev.kpetriv.sprout.core.model.RewardKind
import kotlinx.serialization.Serializable

/**
 * An item in the household's rewards catalogue. Buying it debits [cost] from the kid's fiat MAIN account
 * (`RewardPurchased`); a [RewardKind.SCREEN_TIME] reward also credits [minutesGranted] to the time bank.
 *
 * @property cost positive, in minor units of [currency], which must be a fiat currency.
 * @property minutesGranted set (and positive) exactly when [kind] is [RewardKind.SCREEN_TIME].
 * @property forKids the kids who see the reward; empty means every kid in the household.
 */
@Serializable
data class Reward(
    val id: RewardId,
    val householdId: HouseholdId,
    val title: String,
    val kind: RewardKind,
    val cost: Money,
    val currency: Currency,
    val minutesGranted: Int? = null,
    val active: Boolean = true,
    val forKids: Set<KidId> = emptySet(),
) {
    init {
        requireTitle("reward", title)
        require(cost.isPositive) { "cost must be positive" }
        require(currency is Currency.Fiat) { "rewards are priced in a fiat currency" }
        if (kind == RewardKind.SCREEN_TIME) {
            require(minutesGranted != null && minutesGranted > 0) { "a screen-time reward grants positive minutes" }
        } else {
            require(minutesGranted == null) { "only a screen-time reward grants minutes" }
        }
    }

    /** Whether [kidId] can see and buy this reward now. */
    fun isAvailableTo(kidId: KidId): Boolean = active && (forKids.isEmpty() || kidId in forKids)
}
