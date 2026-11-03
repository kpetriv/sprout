package dev.kpetriv.sprout.core.database

import androidx.room.withTransaction
import dev.kpetriv.sprout.core.model.Kid
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.model.YearMonth
import dev.kpetriv.sprout.core.time.Clock
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject

/**
 * TEMPORARY (M1 only, replaced by onboarding's household setup in M3): seeds the local household so the wallet has
 * data before sign-in exists.
 */
class HouseholdBootstrap
@Suppress("LongParameterList")
internal constructor(
    private val database: SproutDatabase,
    private val kids: KidRepository,
    private val accounts: AccountRepository,
    private val authors: SessionAuthorProvider,
    private val currency: FiatCurrencyProvider,
    private val clock: Clock,
    private val zone: () -> ZoneId,
) {
    @Inject
    constructor(
        database: SproutDatabase,
        kids: KidRepository,
        accounts: AccountRepository,
        authors: SessionAuthorProvider,
        currency: FiatCurrencyProvider,
        clock: Clock,
    ) : this(database, kids, accounts, authors, currency, clock, ZoneId::systemDefault)

    /**
     * If no kid exists (archived ones included), creates three demo kids, one per [KidUiTier] (Mia 6, Leo 9,
     * Zoe 13), each with its default accounts in the household's fiat currency. Returns true if it seeded.
     *
     * The author (whose device id is the Keystore key's fingerprint) is resolved before the transaction opens, so
     * the repositories' own author lookups inside it hit the provider's cache and no Keystore call runs while the
     * database is locked.
     */
    suspend fun ensureLocalHousehold(): Boolean {
        authors.currentAuthor()
        return seed()
    }

    private suspend fun seed(): Boolean = database.withTransaction {
        if (database.kidDao().count() > 0) return@withTransaction false
        val today = clock.now().toEpochMilliseconds().let {
            java.time.Instant.ofEpochMilli(it).atZone(zone()).let { date -> YearMonth(date.year, date.monthValue) }
        }
        val fiat = currency.fiatCurrency()
        DEMO_KIDS.forEachIndexed { index, (name, age) ->
            val kid = Kid(
                id = KidId(UUID.randomUUID().toString()),
                householdId = LocalOnlyAuthorProvider.LOCAL_HOUSEHOLD,
                firstName = name,
                avatarId = "avatar-${index + 1}",
                birthYearMonth = YearMonth(today.year - age, today.month),
                uiTier = KidUiTier.suggestedFor(age),
            )
            kids.upsert(kid)
            accounts.ensureDefaultAccounts(kid.id, fiat)
        }
        true
    }

    private companion object {
        /** First name and age in whole years: one kid per tier (Seedling, Sprout, Sapling). */
        val DEMO_KIDS = listOf("Mia" to 6, "Leo" to 9, "Zoe" to 13)
    }
}
