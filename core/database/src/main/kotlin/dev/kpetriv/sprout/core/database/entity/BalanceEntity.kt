package dev.kpetriv.sprout.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.kpetriv.sprout.core.domainledger.Balance
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.model.AccountId
import dev.kpetriv.sprout.core.model.KidId
import kotlin.time.Instant

/**
 * Projection cache of one account's balance, for the UI. Never the source of truth and never synced (ADR-0002);
 * it can be dropped and rebuilt from `ledger_events` at any time.
 */
@Entity(tableName = "balances", indices = [Index(value = ["kidId"])])
data class BalanceEntity(
    @PrimaryKey val accountId: AccountId,
    val kidId: KidId,
    val currency: Currency,
    val amountMinor: Money,
    val asOfEpochMs: Long,
    val lastLamport: Long,
) {
    fun toDomain(): Balance = Balance(
        accountId = accountId,
        currency = currency,
        amount = amountMinor,
        asOf = Instant.fromEpochMilliseconds(asOfEpochMs),
        lastLamport = lastLamport,
    )

    companion object {
        fun from(kidId: KidId, balance: Balance) = BalanceEntity(
            accountId = balance.accountId,
            kidId = kidId,
            currency = balance.currency,
            amountMinor = balance.amount,
            asOfEpochMs = balance.asOf.toEpochMilliseconds(),
            lastLamport = balance.lastLamport,
        )
    }
}
