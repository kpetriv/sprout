package dev.kpetriv.sprout.core.database

import dev.kpetriv.sprout.core.crypto.DeviceSigner
import dev.kpetriv.sprout.core.crypto.InMemoryDeviceKeyStore
import dev.kpetriv.sprout.core.crypto.JceEcdsaVerifier
import dev.kpetriv.sprout.core.database.entity.AuditAction
import dev.kpetriv.sprout.core.domainledger.AccountKind
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.model.RewardKind
import dev.kpetriv.sprout.core.model.YearMonth
import dev.kpetriv.sprout.core.sync.DocTypes
import dev.kpetriv.sprout.core.time.HouseholdCalendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
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
    private val signer = DeviceSigner(InMemoryDeviceKeyStore(clock), Dispatchers.Unconfined)
    private val sessions = RoomSessionStore(database, clock)
    private val authors = SessionBackedAuthorProvider(sessions)
    private val photos = testPhotoStore(database, authors, clock)
    private val kids = RoomKidRepository(database, authors, clock, photos, signer)
    private val accounts = RoomAccountRepository(database, authors, clock, signer)
    private val gbp = Currency.Fiat("GBP")
    private val targets = HouseholdSeedTargets(
        kids = kids,
        accounts = accounts,
        chores = RoomChoreRepository(database, authors, clock, NothingPaid, photos, signer),
        rewards = RoomRewardRepository(database, authors, clock, signer),
        allowances = RoomAllowanceRepository(database, authors, clock, signer),
        goals = RoomSavingsGoalRepository(database, authors, clock, signer),
        policies = RoomHouseholdPolicyRepository(
            database,
            authors,
            { gbp },
            clock,
            signer,
            RoomLedgerRepository(database, clock, signer, JceEcdsaVerifier()),
        ),
    )
    private val bootstrap = HouseholdBootstrap(
        database,
        targets,
        sessions,
        signer,
        { gbp },
        clock,
        HouseholdCalendar(clock) { ZoneOffset.UTC },
    )

    @Before
    fun trialSession() = runBlocking {
        sessions.ensureLocalTrialSession(signer.deviceId())
        Unit
    }

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
            assertEquals(LocalTrial.HOUSEHOLD, kid.householdId)
            val owned = accounts.accountsFor(kid.id).first().map { it.currency to it.kind }.toSet()
            assertEquals(
                setOf(gbp to AccountKind.MAIN, gbp to AccountKind.SAVINGS, Currency.Minutes to AccountKind.MAIN),
                owned,
            )
        }
    }

    @Test
    fun bootstrapSeedsChoresRewardsAnAllowanceForLeoAndAGoalForZoe() = runTest {
        assertTrue(bootstrap.ensureLocalHousehold())
        val (mia, leo, zoe) = kids.observeKids().first().sortedBy { it.birthYearMonth }.asReversed()

        listOf(mia, leo, zoe).forEach { kid -> assertEquals(2, targets.chores.observeChores(kid.id).first().size) }
        val rewards = targets.rewards.observeRewards().first()
        assertEquals(
            setOf(RewardKind.SCREEN_TIME, RewardKind.TREAT, RewardKind.OUTING),
            rewards.map {
                it.kind
            }.toSet(),
        )
        assertEquals(30, rewards.single { it.kind == RewardKind.SCREEN_TIME }.minutesGranted)
        assertTrue(rewards.all { it.currency == gbp })
        assertEquals(listOf(leo.id), targets.allowances.observeSchedules().first().map { it.kidId })
        assertEquals(1, targets.goals.observeGoals(zoe.id).first().size)
        assertEquals(gbp, targets.policies.get()?.fiatCurrency)
        // Kids, accounts, the policy, chores, rewards, the allowance and the goal are synced documents.
        assertEquals(
            mapOf(
                DocTypes.KID to 3,
                DocTypes.ACCOUNT to 9,
                DocTypes.HOUSEHOLD_POLICY to 1,
                DocTypes.CHORE to 6,
                DocTypes.REWARD to 3,
                DocTypes.ALLOWANCE_SCHEDULE to 1,
                DocTypes.SAVINGS_GOAL to 1,
            ),
            database.outboxDao().all().mapNotNull { it.docType }.groupingBy { it }.eachCount(),
        )
        // Seeding twice does nothing.
        assertFalse(bootstrap.ensureLocalHousehold())
        assertEquals(3, targets.rewards.observeRewards().first().size)
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
            listOf("Make your bed", "Ice cream", "New bike").forEach { title ->
                assertFalse(title in row.detailsJson, "audit row must not contain a title")
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
