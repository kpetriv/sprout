package dev.kpetriv.sprout.core.domainhousehold

import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.model.CalendarDate

/**
 * Whether a kid can buy a [Reward] from their MAIN balance, and if not, how far off they are.
 *
 * @property shortfall minor units still missing; zero when [canAfford].
 */
data class RewardAffordability(val reward: Reward, val balance: Money, val shortfall: Money) {
    val canAfford: Boolean get() = shortfall == Money.ZERO

    /**
     * Whole days from [today] until allowance alone covers the [shortfall]: 0 if affordable now, otherwise the days
     * until the allowance payment that closes the gap, counting only payments after [today] (today's is assumed
     * already credited). Null when [allowance] cannot help (inactive, or in another currency than the reward) or the
     * gap needs more than 520 payments. An estimate: it ignores chores, games and spending in between.
     */
    fun daysUntil(allowance: AllowanceSchedule, today: CalendarDate): Int? {
        val amount = allowance.amount.minorUnits
        val paymentsNeeded = shortfall.minorUnits / amount + if (shortfall.minorUnits % amount == 0L) 0 else 1
        val helps = allowance.active && allowance.currency == reward.currency
        return when {
            canAfford -> 0

            !helps || paymentsNeeded > MAX_PAYMENTS_LOOKED_AHEAD -> null

            // The paymentsNeeded-th payment after today; null if the calendar ends first.
            else -> generateSequence(AllowancePlanner.nextDueDate(allowance, today)) {
                AllowancePlanner.nextDueDate(allowance, it)
            }.elementAtOrNull(paymentsNeeded.toInt() - 1)?.let { today.daysUntil(it).toInt() }
        }
    }

    companion object {
        /** Beyond this many allowance payments (ten years of weekly ones) the estimate is not useful. */
        private const val MAX_PAYMENTS_LOOKED_AHEAD = 520L

        /** Affordability of [reward] for a MAIN [balance] in the reward's currency. A negative balance is allowed. */
        fun of(reward: Reward, balance: Money): RewardAffordability {
            val shortfall = if (balance >= reward.cost) Money.ZERO else reward.cost - balance
            return RewardAffordability(reward, balance, shortfall)
        }
    }
}
