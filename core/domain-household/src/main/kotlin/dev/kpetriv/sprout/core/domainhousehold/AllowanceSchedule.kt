package dev.kpetriv.sprout.core.domainhousehold

import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.model.AccountId
import dev.kpetriv.sprout.core.model.AllowanceCadence
import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.ScheduleId
import kotlinx.serialization.Serializable

/**
 * A kid's pocket money: [amount] of [currency] credited to [accountId] on every [cadence] date from [startsOn].
 * Payments are planned by [AllowancePlanner] and signed by a parent (`AllowancePaid`, parent only).
 *
 * @property id becomes the `scheduleId` of every `AllowancePaid` event.
 * @property accountId the kid's MAIN account in [currency].
 * @property amount positive.
 */
@Serializable
data class AllowanceSchedule(
    val id: ScheduleId,
    val kidId: KidId,
    val accountId: AccountId,
    val amount: Money,
    val currency: Currency,
    val cadence: AllowanceCadence,
    val startsOn: CalendarDate,
    val active: Boolean = true,
) {
    init {
        require(amount.isPositive) { "allowance amount must be positive" }
    }
}
