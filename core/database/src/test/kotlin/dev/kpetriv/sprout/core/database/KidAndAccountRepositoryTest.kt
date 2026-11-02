package dev.kpetriv.sprout.core.database

import dev.kpetriv.sprout.core.crypto.DeviceSigner
import dev.kpetriv.sprout.core.crypto.InMemoryDeviceKeyStore
import dev.kpetriv.sprout.core.database.entity.AuditAction
import dev.kpetriv.sprout.core.domainledger.AccountKind
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.model.YearMonth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.ZoneOffset
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class KidAndAccountRepositoryTest {
    private val clock = testClock()
    private val database = inMemoryDatabase()
    private val authors = LocalOnlyAuthorProvider(DeviceSigner(InMemoryDeviceKeyStore(clock), Dispatchers.Unconfined))
    private val kids = RoomKidRepository(database, authors, clock)
    private val accounts = RoomAccountRepository(database, authors, clock)
    private val gbp = Currency.Fiat("GBP")
    private val bootstrap =
        HouseholdBootstrap(database, kids, accounts, authors, { gbp }, clock) { ZoneOffset.UTC }

    @After
    fun close() = database.close()

    @Test
    fun bootstrapSeedsOneDemoKidPerTierOnce() = runTest {
        assertTrue(bootstrap.ensureLocalHousehold())
        assertFalse(bootstrap.ensureLocalHousehold())

        val seeded = kids.observeKids().first().sortedBy { it.birthYearMonth }.asReversed()
        assertEquals(listOf("Mia", "Leo", "Zoe"), seeded.map { it.firstName })
        assertEquals(listOf(KidUiTier.SEEDLING, KidUiTier.SPROUT, KidUiTier.SAPLING), seeded.map { it.uiTier })
        // The fixed test clock is 2026-11-02.
        assertEquals(listOf(6, 9, 13), seeded.map { it.ageOn(YearMonth(2026, 11)) })
        seeded.forEach { kid ->
            assertEquals(LocalOnlyAuthorProvider.LOCAL_HOUSEHOLD, kid.householdId)
            val owned = accounts.accountsFor(kid.id).first().map { it.currency to it.kind }.toSet()
            assertEquals(
                setOf(gbp to AccountKind.MAIN, gbp to AccountKind.SAVINGS, Currency.Minutes to AccountKind.MAIN),
                owned,
            )
        }
    }

    @Test
    fun ensureDefaultAccountsIsIdempotent() = runTest {
        bootstrap.ensureLocalHousehold()
        val kid = kids.observeKids().first().first()
        val before = accounts.accountsFor(kid.id).first()

        assertEquals(before.toSet(), accounts.ensureDefaultAccounts(kid.id, gbp).toSet())
        val created = database.auditLogDao().all().count { it.action == AuditAction.ACCOUNT_CREATED }
        assertEquals(9, created)
    }

    @Test
    fun archiveHidesTheKidButKeepsTheProfile() = runTest {
        bootstrap.ensureLocalHousehold()
        val kid = kids.observeKids().first().first()

        assertTrue(kids.archive(kid.id))

        assertEquals(2, kids.observeKids().first().size)
        assertEquals(true, assertNotNull(kids.getKid(kid.id)).archived)
        assertNull(kids.getKid(KidId("nobody")))
        assertFalse(kids.archive(KidId("nobody")))
    }

    @Test
    fun auditLogHoldsNoNames() = runTest {
        bootstrap.ensureLocalHousehold()
        kids.archive(kids.observeKids().first().first().id)

        val audit = database.auditLogDao().all()
        assertEquals(3, audit.count { it.action == AuditAction.KID_UPSERTED })
        assertEquals(1, audit.count { it.action == AuditAction.KID_ARCHIVED })
        audit.forEach { row ->
            listOf("Mia", "Leo", "Zoe").forEach { name ->
                assertFalse(name in row.detailsJson || name in row.target, "audit row must not contain a name")
            }
        }
    }

    @Test
    fun fiatCurrencyComesFromTheLocaleWithAnEuroFallback() {
        assertEquals(Currency.Fiat("GBP"), LocaleFiatCurrencyProvider.forLocale(Locale.UK))
        assertEquals(Currency.Fiat("JPY"), LocaleFiatCurrencyProvider.forLocale(Locale.JAPAN))
        assertEquals(Currency.Fiat("USD"), LocaleFiatCurrencyProvider { Locale.US }.fiatCurrency())
        // No country, so no currency.
        assertEquals(LocaleFiatCurrencyProvider.FALLBACK, LocaleFiatCurrencyProvider.forLocale(Locale.ENGLISH))
        assertEquals(Currency.Fiat("EUR"), LocaleFiatCurrencyProvider.FALLBACK)
    }
}
