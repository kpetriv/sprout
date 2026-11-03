package dev.kpetriv.sprout.core.database

import dev.kpetriv.sprout.core.crypto.DeviceSigner
import dev.kpetriv.sprout.core.database.entity.AccountEntity
import dev.kpetriv.sprout.core.database.entity.AuditAction
import dev.kpetriv.sprout.core.domainledger.Account
import dev.kpetriv.sprout.core.domainledger.AccountKind
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.model.AccountId
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.sync.DocTypes
import dev.kpetriv.sprout.core.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.put
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

/**
 * Deterministic account ids (D-M3-4): `<kidId>.<currency>.<kind>`, so every device of a household that creates a
 * kid's default accounts creates the same ones. Accounts created before M3 keep their random ids.
 */
object AccountIds {
    fun of(kidId: KidId, currency: Currency, kind: AccountKind): AccountId =
        AccountId("${kidId.value}.${Converters.currencyToString(currency)}.${kind.name}")
}

/**
 * [AccountRepository] on Room. Accounts are synced documents ([DocTypes.ACCOUNT]); creating one takes a lamport,
 * queues the signed document and is audited in the same transaction. New accounts get [AccountIds] ids.
 */
class RoomAccountRepository @Inject constructor(
    private val database: SproutDatabase,
    private val authors: SessionAuthorProvider,
    private val clock: Clock,
    signer: DeviceSigner,
) : AccountRepository {
    private val accounts = database.accountDao()
    private val writes = DocumentWrites(database, clock, signer)

    override fun accountsFor(kidId: KidId): Flow<List<Account>> =
        accounts.observeForKid(kidId).map { rows -> rows.map(AccountEntity::toDomain) }

    override suspend fun ensureDefaultAccounts(kidId: KidId, fiatCurrency: Currency.Fiat): List<Account> {
        val author = authors.currentAuthor()
        val defaults = listOf(
            fiatCurrency to AccountKind.MAIN,
            fiatCurrency to AccountKind.SAVINGS,
            Currency.Minutes to AccountKind.MAIN,
        )
        return writes.transaction {
            val now = clock.now().toEpochMilliseconds()
            for ((currency, kind) in defaults) {
                if (accounts.find(kidId, currency, kind) != null) continue
                val account = Account(AccountIds.of(kidId, currency, kind), kidId, currency, kind)
                val lamport = writes.nextLamport(author.deviceId)
                val stamp = writes.record(
                    author,
                    DocTypes.ACCOUNT,
                    account.id.value,
                    lamport,
                    DocumentPayloads.encode(Account.serializer(), account),
                    AuditAction.ACCOUNT_CREATED,
                ) {
                    put("kidId", kidId.value)
                    put("currency", Converters.currencyToString(currency))
                    put("kind", kind.name)
                }
                accounts.insertIfAbsent(AccountEntity.from(account, now).copy(lamport = lamport, stamp = stamp))
            }
            accounts.forKid(kidId).map(AccountEntity::toDomain)
        }
    }
}
