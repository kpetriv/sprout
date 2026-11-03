package dev.kpetriv.sprout

import dev.kpetriv.sprout.core.database.AccountRepository
import dev.kpetriv.sprout.core.database.KidRepository
import dev.kpetriv.sprout.core.domainledger.Account
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.Kid
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.model.YearMonth
import dev.kpetriv.sprout.startup.StartupState
import dev.kpetriv.sprout.startup.StartupStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

fun kid(name: String, born: YearMonth, tier: KidUiTier, archived: Boolean = false): Kid = Kid(
    id = KidId("kid-${name.lowercase()}"),
    householdId = HouseholdId("local"),
    firstName = name,
    avatarId = "avatar-1",
    birthYearMonth = born,
    uiTier = tier,
    archived = archived,
)

/** In-memory [KidRepository]; [observeKids] hides archived kids like the Room one. */
class FakeKidRepository(initial: List<Kid>, var failReads: Boolean = false) : KidRepository {
    private val kids = MutableStateFlow(initial)

    override fun observeKids(): Flow<List<Kid>> =
        if (failReads) flow { error("read failed") } else kids.map { all -> all.filterNot(Kid::archived) }

    override suspend fun getKid(id: KidId): Kid? = kids.value.firstOrNull { it.id == id }

    override suspend fun upsert(kid: Kid) = kids.update { all -> all.filterNot { it.id == kid.id } + kid }

    override suspend fun archive(id: KidId): Boolean {
        val found = getKid(id) ?: return false
        upsert(found.copy(archived = true))
        return true
    }
}

/** [StartupStatus] whose state a test sets directly; counts [retry] calls. */
class FakeStartupStatus(initial: StartupState = StartupState.Ready) : StartupStatus {
    override val state = MutableStateFlow(initial)
    var retries = 0

    override fun retry() {
        retries += 1
    }
}

class FakeAccountRepository(private val accounts: Map<KidId, List<Account>>) : AccountRepository {
    override fun accountsFor(kidId: KidId): Flow<List<Account>> = MutableStateFlow(accounts[kidId].orEmpty())

    override suspend fun ensureDefaultAccounts(kidId: KidId, fiatCurrency: Currency.Fiat): List<Account> =
        accounts[kidId].orEmpty()
}
