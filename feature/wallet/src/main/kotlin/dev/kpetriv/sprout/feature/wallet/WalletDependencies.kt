package dev.kpetriv.sprout.feature.wallet

import dev.kpetriv.sprout.core.database.usecase.PurchaseResult
import dev.kpetriv.sprout.core.database.usecase.PurchaseRewardUseCase
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.RewardId

/**
 * Buys a reward for a kid. Bound in [dev.kpetriv.sprout.feature.wallet.di.WalletModule] to [PurchaseRewardUseCase];
 * a seam so the shop ViewModel can be tested without a database.
 */
fun interface RewardPurchaser {
    suspend fun purchase(kidId: KidId, rewardId: RewardId): PurchaseResult
}

/**
 * Localised text of the parent prompt for a manual adjustment, for example "Adjust Mia's balance by +£2.50". Bound in
 * [dev.kpetriv.sprout.feature.wallet.di.WalletModule] to the wallet's string resources.
 */
fun interface AdjustmentPromptText {
    /** [kidName] is the kid's first name; [signedAmount] is the formatted, signed amount. */
    fun adjust(kidName: String, signedAmount: String): String
}
