package dev.kpetriv.sprout.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import dev.kpetriv.sprout.core.domainhousehold.Reward
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.RewardId
import dev.kpetriv.sprout.core.model.RewardKind
import kotlinx.serialization.builtins.ListSerializer

/**
 * An item of the rewards catalogue ([Reward]). A synced document. Kid-authored `RewardPurchased` events are checked
 * against this row (cost, currency, minutes) before they are stored.
 *
 * @property forKidsJson JSON array of the kid ids that see the reward; `[]` means every kid.
 */
@Entity(tableName = "rewards")
data class RewardEntity(
    @PrimaryKey val rewardId: RewardId,
    val householdId: HouseholdId,
    val title: String,
    val kind: RewardKind,
    val cost: Money,
    val currency: Currency,
    val minutesGranted: Int?,
    val active: Boolean,
    val forKidsJson: String,
    val lamport: Long,
) {
    fun toDomain(): Reward = Reward(
        id = rewardId,
        householdId = householdId,
        title = title,
        kind = kind,
        cost = cost,
        currency = currency,
        minutesGranted = minutesGranted,
        active = active,
        forKids = DocumentJson.json.decodeFromString(KID_IDS, forKidsJson).toSet(),
    )

    override fun toString(): String = "RewardEntity(rewardId=$rewardId, kind=$kind, active=$active, title=<redacted>)"

    companion object {
        private val KID_IDS = ListSerializer(KidId.serializer())

        fun from(reward: Reward, lamport: Long) = RewardEntity(
            rewardId = reward.id,
            householdId = reward.householdId,
            title = reward.title,
            kind = reward.kind,
            cost = reward.cost,
            currency = reward.currency,
            minutesGranted = reward.minutesGranted,
            active = reward.active,
            forKidsJson = DocumentJson.json.encodeToString(KID_IDS, reward.forKids.sortedBy { it.value }),
            lamport = lamport,
        )
    }
}
