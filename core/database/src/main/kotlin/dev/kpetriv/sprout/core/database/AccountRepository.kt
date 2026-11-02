package dev.kpetriv.sprout.core.database

import androidx.room.withTransaction
import dev.kpetriv.sprout.core.database.entity.AccountEntity
import dev.kpetriv.sprout.core.database.entity.AuditAction
import dev.kpetriv.sprout.core.domainledger.Account
import dev.kpetriv.sprout.core.domainledger.AccountKind
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.model.AccountId
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.put
import java.util.UUID
import javax.inject.Inject

/** Ledger accounts. */
interface AccountRepository {
    fun accountsFor(kidId: KidId): Flow<List<Account>>

    /**
     * Makes sure [kidId] has its default accounts: MAIN and SAVINGS in [fiatCurrency], and MAIN in minutes.
     * Idempotent; returns every account of the kid.
     */
    suspend fun ensureDefaultAccounts(kidId: KidId, fiatCurrency: Currency.Fiat): List<Account>
}

/** [AccountRepository] on Room. Account creation is audited in the same transaction. */
class RoomAccountRepository @Inject constructor(
    private val database: SproutDatabase,
    private val authors: SessionAuthorProvider,
    private val clock: Clock,
) : AccountRepository {
    private val accounts = database.accountDao()
    private val audit = database.auditLogDao()

    override fun accountsFor(kidId: KidId): Flow<List<Account>> =
        accounts.observeForKid(kidId).map { rows -> rows.map(AccountEntity::toDomain) }

    override suspend fun ensureDefaultAccounts(kidId: KidId, fiatCurrency: Currency.Fiat): List<Account> {
        val author = authors.currentAuthor()
        val defaults = listOf(
            fiatCurrency to AccountKind.MAIN,
            fiatCurrency to AccountKind.SAVINGS,
            Currency.Minutes to AccountKind.MAIN,
        )
        return database.withTransaction {
            val now = clock.now().toEpochMilliseconds()
            for ((currency, kind) in defaults) {
                val account = Account(AccountId(UUID.randomUUID().toString()), kidId, currency, kind)
                if (accounts.insertIfAbsent(AccountEntity.from(account, now)) != NOT_INSERTED) {
                    audit.insert(
                        auditEntry(author, now, AuditAction.ACCOUNT_CREATED, account.id.value) {
                            put("kidId", kidId.value)
                            put("currency", Converters.currencyToString(currency))
                            put("kind", kind.name)
                        },
                    )
                }
            }
            accounts.forKid(kidId).map(AccountEntity::toDomain)
        }
    }

    private companion object {
        /** Room's row id for an `INSERT OR IGNORE` that was ignored. */
        const val NOT_INSERTED = -1L
    }
}
