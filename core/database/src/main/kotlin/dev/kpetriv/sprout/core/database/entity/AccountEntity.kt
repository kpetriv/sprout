package dev.kpetriv.sprout.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.kpetriv.sprout.core.domainledger.Account
import dev.kpetriv.sprout.core.domainledger.AccountKind
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.model.AccountId
import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.model.KidId

/**
 * A ledger account. One per (kid, currency, kind), enforced by a unique index.
 *
 * @property lastAccruedOn device-local interest bookkeeping for a SAVINGS account (schema version 2): the latest
 *   accrual day already planned (`InterestPlanner`'s `lastAccruedOn`). Null until the first planning run, which
 *   starts interest from that day. Device-local, never synced.
 * @property lamport the account document's lamport (schema version 3); 0 for rows written before.
 * @property stamp author and signature of the account document (schema version 3).
 */
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
    val lastAccruedOn: CalendarDate? = null,
    @ColumnInfo(defaultValue = "0") val lamport: Long = 0,
    @Embedded val stamp: DocumentStamp = DocumentStamp(),
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
