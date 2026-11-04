package dev.kpetriv.sprout.feature.wallet

import dev.kpetriv.sprout.core.database.usecase.PurchaseResult
import dev.kpetriv.sprout.core.database.usecase.PurchaseRewardUseCase
import dev.kpetriv.sprout.core.domainledger.Money
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

/**
 * "Scan a receipt" in the manual adjustment sheet (M6 §3.4, D-M6-5). The sheet launches the system photo picker and
 * passes the picked image's content URI; the app reads it (the shared `PickedImageReader`) and runs the on-device
 * AI gateway's `receiptPrefill` (on-device text recognition plus rules parsing, no model call; the image and its text
 * stay on the phone, though Google Play services records that the scanner was used, D-M6-51). Returns null when
 * nothing usable was found. Implementations must not log the image or the text read from it.
 */
fun interface ReceiptScanner {
    suspend fun scan(imageUri: String): AdjustmentPrefill?

    /** Whether the sheet offers scanning at all (the app's: while the On-device AI switch is on, D-M6-51). */
    suspend fun isAvailable(): Boolean = true
}

/**
 * What a scanned receipt suggests for the adjustment: the total as a debit and the shop's name for the reason. The
 * parent still reviews both and signs (D-M6-5); nothing is stored until then (D-M6-7).
 *
 * @property amount the receipt's total in the main account's currency (minor units), or null when none was found.
 * @property merchant the first line of the receipt (at most 40 characters), or null.
 */
data class AdjustmentPrefill(val amount: Money?, val merchant: String?) {
    override fun toString(): String = "AdjustmentPrefill(hasAmount=${amount != null}, hasMerchant=${merchant != null})"
}
