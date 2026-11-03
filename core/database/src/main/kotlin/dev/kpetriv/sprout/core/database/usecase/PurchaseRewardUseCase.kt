package dev.kpetriv.sprout.core.database.usecase

import dev.kpetriv.sprout.core.database.AccountRepository
import dev.kpetriv.sprout.core.database.RewardRepository
import dev.kpetriv.sprout.core.domainledger.AppendResult
import dev.kpetriv.sprout.core.domainledger.BatchAppendResult
import dev.kpetriv.sprout.core.domainledger.BatchEntry
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.EventId
import dev.kpetriv.sprout.core.domainledger.LedgerWriter
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.domainledger.RewardPurchased
import dev.kpetriv.sprout.core.domainledger.TimeBankCredited
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.RewardId
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/** Which event of a purchase the ledger refused. */
enum class PurchaseStep {
    /** The `RewardPurchased` debit. */
    PURCHASE,

    /** The `TimeBankCredited` a screen-time reward grants; the purchase was rolled back with it. */
    TIME_CREDIT,
}

/** Outcome of [PurchaseRewardUseCase]. */
sealed interface PurchaseResult {
    /**
     * The purchase [purchaseId] is in the ledger. For a screen-time reward its `TimeBankCredited` ([timeCreditId]) went
     * in with it, in the same transaction; null for other rewards.
     */
    data class Purchased(val purchaseId: EventId, val timeCreditId: EventId?) : PurchaseResult

    /** The reward does not exist, is inactive, or is not offered to this kid. */
    data object Unavailable : PurchaseResult

    /** The kid has no MAIN account in the reward's currency (or, for screen time, no minutes account). */
    data object NoAccount : PurchaseResult

    /**
     * The ledger refused the [step] event ([result]), for example the purchase for insufficient funds. Nothing was
     * written: a refused time credit rolls the purchase back too.
     */
    data class Refused(val result: AppendResult, val step: PurchaseStep = PurchaseStep.PURCHASE) : PurchaseResult
}

/**
 * A kid buys a reward from the shop. Appends a kid-authored `RewardPurchased` built from the catalogue (cost,
 * currency and minutes; the repository rejects anything that does not match), and for a screen-time reward a
 * kid-authored `TimeBankCredited` that names the purchase (`grantedBy`), so no parent signature is needed for it.
 *
 * Both events go through [LedgerWriter.appendAllAsKid]: built, signed and stored together, **all or nothing** (R1). A
 * crash or a refusal between them can no longer leave a paid purchase without its minutes; a grant that is still owed
 * (from before this, or written by another path) is repaired by [RedeemOwedGrantsUseCase].
 */
class PurchaseRewardUseCase @Inject constructor(
    private val rewards: RewardRepository,
    private val accounts: AccountRepository,
    private val writer: LedgerWriter,
) {
    @Suppress("ReturnCount") // One early return per PurchaseResult outcome.
    suspend operator fun invoke(kidId: KidId, rewardId: RewardId): PurchaseResult {
        val reward = rewards.get(rewardId)?.takeIf { it.isAvailableTo(kidId) } ?: return PurchaseResult.Unavailable
        val kidAccounts = accounts.accountsFor(kidId).first()
        val main = kidAccounts.main(reward.currency) ?: return PurchaseResult.NoAccount
        val minutes = reward.minutesGranted?.let { Money(it.toLong()) }
        val minutesAccount = minutes?.let { kidAccounts.main(Currency.Minutes) ?: return PurchaseResult.NoAccount }

        val purchase = BatchEntry(main.id) { envelope, _ ->
            RewardPurchased(envelope, reward.currency, reward.cost, reward.id.value, minutes)
        }
        val credit = if (minutes != null && minutesAccount != null) {
            BatchEntry(minutesAccount.id) { envelope, previous ->
                TimeBankCredited(envelope, minutes, grantedBy = previous.first().eventId, rewardId = reward.id.value)
            }
        } else {
            null
        }
        return when (val result = writer.appendAllAsKid(kidId, listOfNotNull(purchase, credit))) {
            is BatchAppendResult.Accepted ->
                PurchaseResult.Purchased(result.events.first().eventId, result.events.getOrNull(1)?.eventId)

            is BatchAppendResult.Rejected -> PurchaseResult.Refused(
                result.result,
                if (result.index == 0) PurchaseStep.PURCHASE else PurchaseStep.TIME_CREDIT,
            )
        }
    }
}
