package dev.kpetriv.sprout.core.database.usecase

import dev.kpetriv.sprout.core.database.AccountRepository
import dev.kpetriv.sprout.core.database.RoomLedgerRepository
import dev.kpetriv.sprout.core.domainledger.AppendResult
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.EventId
import dev.kpetriv.sprout.core.domainledger.LedgerWriter
import dev.kpetriv.sprout.core.domainledger.RewardGrant
import dev.kpetriv.sprout.core.domainledger.TimeBankCredited
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Outcome of [RedeemOwedGrantsUseCase].
 *
 * @property credited the purchase eventIds whose minutes were credited now.
 * @property failed owed grants that could not be credited (no minutes account, or the ledger refused), with why; they
 *   stay owed and are tried again on the next run.
 */
data class RedeemResult(val credited: List<EventId>, val failed: Map<EventId, AppendResult>)

/**
 * Repairs screen-time purchases whose minutes were never credited: every [RewardGrant] in the projection with no
 * `creditedBy` gets the kid-authored `TimeBankCredited` it is owed (`grantedBy` = the purchase, same reward and
 * minutes, so the ledger accepts it without a parent signature). Purchases are atomic since R1, so this only finds
 * grants from before that, or written by another path; it runs with every plan ([PlanScheduledActionsUseCase]: at
 * startup and daily). Idempotent: a credited grant is no longer owed, and the ledger refuses a second credit.
 */
class RedeemOwedGrantsUseCase @Inject constructor(
    private val ledger: RoomLedgerRepository,
    private val accounts: AccountRepository,
    private val writer: LedgerWriter,
) {
    suspend operator fun invoke(): RedeemResult {
        val owed = ledger.projection().rewardGrants.values.filter { it.creditedBy == null }
        val credited = mutableListOf<EventId>()
        val failed = mutableMapOf<EventId, AppendResult>()
        for (grant in owed) {
            val minutesAccount = accounts.accountsFor(grant.kidId).first().main(Currency.Minutes)
            val result = if (minutesAccount == null) {
                AppendResult.Rejected(NO_ACCOUNT_REJECTION)
            } else {
                writer.appendAsKid(grant.kidId, minutesAccount.id) { envelope ->
                    TimeBankCredited(envelope, grant.minutes, grantedBy = grant.purchaseId, rewardId = grant.rewardId)
                }
            }
            if (result == AppendResult.Accepted) credited += grant.purchaseId else failed[grant.purchaseId] = result
        }
        return RedeemResult(credited, failed)
    }
}
