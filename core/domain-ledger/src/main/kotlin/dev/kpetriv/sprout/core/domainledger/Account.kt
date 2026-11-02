package dev.kpetriv.sprout.core.domainledger

import dev.kpetriv.sprout.core.model.AccountId
import dev.kpetriv.sprout.core.model.KidId
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/** What an account is for. */
@Serializable
enum class AccountKind {
    /** Day-to-day spending: chores, allowance and games credit it, the shop debits it. */
    MAIN,

    /** A savings goal; funded by [TransferToSavings] and earns [InterestAccrued]. */
    SAVINGS,
}

/** One account per (kid, currency, kind). Every [Money] posted to it is in [currency]. */
@Serializable
data class Account(val id: AccountId, val kidId: KidId, val currency: Currency, val kind: AccountKind)

/**
 * A projected balance: the fold of every applied event that posted to [accountId]. Never the source of truth.
 *
 * @property amount can be negative only when merged offline debits overdrew the account; see
 *   [LedgerAnomaly.OverdraftDetected].
 * @property asOf `createdAt` of the last event applied (display only; not used for ordering).
 * @property lastLamport the highest lamport applied to this account.
 */
data class Balance(
    val accountId: AccountId,
    val currency: Currency,
    val amount: Money,
    val asOf: Instant,
    val lastLamport: Long,
)
