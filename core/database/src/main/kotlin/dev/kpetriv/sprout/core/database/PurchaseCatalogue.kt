package dev.kpetriv.sprout.core.database

import dev.kpetriv.sprout.core.domainledger.RewardPurchased
import dev.kpetriv.sprout.core.model.ID_PATTERN
import dev.kpetriv.sprout.core.model.RewardId

/** Checks a kid-authored [RewardPurchased] against the reward catalogue. [matches] runs inside a transaction. */
internal fun interface PurchaseCatalogue {
    suspend fun matches(event: RewardPurchased): Boolean
}

/**
 * The production [PurchaseCatalogue]: the purchase names a reward in `rewards` that is active and available to the
 * purchasing kid, and its amount, currency and granted minutes are exactly the reward's cost, currency and
 * `minutesGranted`. The ledger itself cannot know the catalogue, so this is where a kid device is stopped from
 * buying screen time at its own price.
 */
internal class RewardTableCatalogue(private val database: SproutDatabase) : PurchaseCatalogue {
    override suspend fun matches(event: RewardPurchased): Boolean {
        val reward = event.rewardId.takeIf(ID_PATTERN::matches)
            ?.let { database.rewardDao().get(RewardId(it)) }
            ?.toDomain()
        return reward != null &&
            reward.isAvailableTo(event.envelope.kidId) &&
            reward.cost == event.amount &&
            reward.currency == event.currency &&
            reward.minutesGranted?.toLong() == event.minutesGranted?.minorUnits
    }
}
