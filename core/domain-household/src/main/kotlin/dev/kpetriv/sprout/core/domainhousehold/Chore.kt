package dev.kpetriv.sprout.core.domainhousehold

import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.model.ChoreId
import dev.kpetriv.sprout.core.model.ChoreSchedule
import dev.kpetriv.sprout.core.model.ChoreState
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.MAX_DESCRIPTION_LENGTH
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * What approving one occurrence of a chore pays: [money] in minor units of the kid's fiat MAIN account currency,
 * [minutes] of screen time, or both. At least one is set and every set amount is positive. Each set amount becomes
 * one `ChoreRewarded` ledger event in its currency.
 */
@Serializable
data class ChoreReward(val money: Money? = null, val minutes: Money? = null) {
    init {
        require(money != null || minutes != null) { "a chore reward needs money, minutes or both" }
        require(money == null || money.isPositive) { "money reward must be positive" }
        require(minutes == null || minutes.isPositive) { "minutes reward must be positive" }
    }
}

/**
 * A chore assigned to one kid.
 *
 * Lives in core/domain-household rather than core/model because [ChoreReward] holds [Money], which is defined in
 * core/domain-ledger, and core/domain-ledger depends on core/model (the reverse edge would be a cycle).
 *
 * @property title shown on kid screens, 1 to `MAX_TITLE_LENGTH` (80) characters, not blank.
 * @property description optional, at most [MAX_DESCRIPTION_LENGTH] characters; redacted by [toString].
 * @property state [ChoreState.PROPOSED] for a Sapling kid's proposal until a parent accepts it.
 */
@Serializable
data class Chore(
    val id: ChoreId,
    val householdId: HouseholdId,
    val kidId: KidId,
    val title: String,
    val description: String? = null,
    val reward: ChoreReward,
    val schedule: ChoreSchedule,
    val createdAt: Instant,
    val state: ChoreState = ChoreState.ACTIVE,
) {
    init {
        requireTitle("chore", title)
        require(description == null || description.length <= MAX_DESCRIPTION_LENGTH) {
            "description must be at most $MAX_DESCRIPTION_LENGTH characters"
        }
    }

    /** Whether the chore is retired (see [ChoreState.ARCHIVED]). */
    val archived: Boolean get() = state == ChoreState.ARCHIVED

    override fun toString(): String =
        "Chore(id=$id, householdId=$householdId, kidId=$kidId, title=$title, description=${redacted(description)}, " +
            "reward=$reward, schedule=$schedule, createdAt=$createdAt, state=$state)"
}
