package dev.kpetriv.sprout.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.kpetriv.sprout.core.domainledger.Account
import dev.kpetriv.sprout.core.domainledger.AccountKind
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.model.AccountId
import dev.kpetriv.sprout.core.model.KidId

/** A ledger account. One per (kid, currency, kind), enforced by a unique index. */
@Entity(
    tableName = "accounts",
    indices = [Index(value = ["kidId", "currency", "kind"], unique = true)],
)
data class AccountEntity(
    @PrimaryKey val accountId: AccountId,
    val kidId: KidId,
    val currency: Currency,
    val kind: AccountKind,
    val createdAtEpochMs: Long,
) {
    fun toDomain(): Account = Account(id = accountId, kidId = kidId, currency = currency, kind = kind)

    companion object {
        fun from(account: Account, createdAtEpochMs: Long) = AccountEntity(
            accountId = account.id,
            kidId = account.kidId,
            currency = account.currency,
            kind = account.kind,
            createdAtEpochMs = createdAtEpochMs,
        )
    }
}
