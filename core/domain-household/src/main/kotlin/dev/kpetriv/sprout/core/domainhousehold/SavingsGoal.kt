package dev.kpetriv.sprout.core.domainhousehold

import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.model.GoalId
import dev.kpetriv.sprout.core.model.KidId
import kotlinx.serialization.Serializable

/**
 * Something a kid is saving towards. Progress is a projection over the kid's SAVINGS account balance (see
 * [SavingsProgress]), not stored here.
 *
 * @property target positive, in minor units of the savings account's currency.
 * @property achievedOn the date the goal was first reached, if it has been; not before [createdOn].
 */
@Serializable
data class SavingsGoal(
    val id: GoalId,
    val kidId: KidId,
    val title: String,
    val target: Money,
    val createdOn: CalendarDate,
    val achievedOn: CalendarDate? = null,
) {
    init {
        requireTitle("savings goal", title)
        require(target.isPositive) { "target must be positive" }
        require(achievedOn == null || achievedOn >= createdOn) { "achievedOn must not be before createdOn" }
    }
}
