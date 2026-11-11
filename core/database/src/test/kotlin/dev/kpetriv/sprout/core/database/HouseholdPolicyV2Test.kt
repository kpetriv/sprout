package dev.kpetriv.sprout.core.database

import dev.kpetriv.sprout.core.database.entity.AuditAction
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.GameCreditResult
import dev.kpetriv.sprout.core.domainledger.LedgerAnomaly
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.games.Difficulty
import dev.kpetriv.sprout.core.games.GameId
import dev.kpetriv.sprout.core.games.GameKind
import dev.kpetriv.sprout.core.games.Score
import dev.kpetriv.sprout.core.sync.DocTypes
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Household policy v2 (M7 §1, D-M7-10): the chat retention and the daily game cap as synced household policy. */
@RunWith(RobolectricTestRunner::class)
class HouseholdPolicyV2Test {
    private val parent = parentDevice()
    private val kid = kidDevice()
    private val saplingSums = GameId(GameKind.ARITHMETIC, Difficulty.SAPLING)
    private val perfectTimed = Score(correct = 10, total = 10, perfect = true, timedBonus = true)

    @Before
    fun policy() = runTest {
        // The household's policy row, as bootstrap creates it: the v2 setters never create one (D-M7-57).
        parent.policies.ensure(parent.gbp)
    }

    @After
    fun close() {
        parent.close()
        kid.close()
    }

    @Test
    fun aDeviceWithoutThePolicyYetRefusesToCreateOne() = runTest {
        val fresh = parentDevice()
        try {
            assertFailsWith<NoHouseholdPolicyYetException> { fresh.policies.setChatRetention(30) }
            assertFailsWith<NoHouseholdPolicyYetException> { fresh.policies.setDailyGameCap(120) }
            assertNull(fresh.policies.get())
            assertTrue(fresh.queuedDocuments().none { it.docType == DocTypes.HOUSEHOLD_POLICY })
        } finally {
            fresh.close()
        }
    }

    @Test
    fun aRestoredPhoneWithoutThePolicyYetRefusesToRegisterAParentKey() = runTest {
        // A wiped phone signed in again: enrolment must not publish this phone's locale and defaults (D-REL-11).
        val fresh = parentDevice()
        try {
            val key = fresh.parentAuthorizer.createParentKey().encoded
            assertFailsWith<NoHouseholdPolicyYetException> { fresh.registerKey.register(key) }
            assertNull(fresh.policies.get())
            assertEquals(0, fresh.database.outboxDao().count(), "nothing queued")
            assertEquals(emptyList(), fresh.database.parentKeyDao().all())
            assertEquals(emptyList(), fresh.database.auditLogDao().withAction(AuditAction.PARENT_KEY_REGISTERED))
            assertEquals(emptyList(), fresh.database.auditLogDao().withAction(AuditAction.HOUSEHOLD_POLICY_UPDATED))
        } finally {
            fresh.close()
        }
    }

    private suspend fun policyDocument(on: TestHousehold) =
        assertNotNull(on.queuedDocuments().lastOrNull { it.docType == DocTypes.HOUSEHOLD_POLICY })

    private suspend fun balanceOf(on: TestHousehold): Money = on.ledger.observeBalances(KID_X).first()
        .filter { it.currency is Currency.Fiat }.sumOf { it.amount.minorUnits }.let(::Money)

    @Test
    fun aParentSetsBothSettingsAndTheDocumentCarriesThem() = runTest {
        val retention = parent.policies.setChatRetention(30)
        assertEquals(30, retention.chatRetentionDays)
        val capped = parent.policies.setDailyGameCap(120)
        assertEquals(30, capped.chatRetentionDays, "a write keeps the setting it does not change")
        assertEquals(120L, capped.dailyGameCapMinor)
        assertEquals(Money(120), capped.dailyGameCap)
        assertEquals(Money(120), capped.toLedgerPolicy().dailyGameCap)

        val payload = policyDocument(parent).payload
        assertEquals(30, payload.getValue("chatRetentionDays").jsonPrimitive.int)
        assertEquals(120L, payload.getValue("dailyGameCapMinor").jsonPrimitive.long)
        assertEquals("GBP", payload.getValue("fiatCurrency").jsonPrimitive.content)
        val audit = parent.database.auditLogDao().all().last { it.action == AuditAction.HOUSEHOLD_POLICY_UPDATED }
        assertTrue("\"dailyGameCapMinor\":120" in audit.detailsJson)

        // Back to the defaults: the keys leave the payload (a v1 payload again).
        parent.policies.setChatRetention(null)
        val reset = parent.policies.setDailyGameCap(null)
        assertNull(reset.chatRetentionDays)
        assertEquals(Money(50), reset.dailyGameCap)
        assertEquals(setOf("fiatCurrency", "requireParentSignature"), policyDocument(parent).payload.keys)
    }

    @Test
    fun outOfRangeValuesAndKidSessionsAreRefused() = runTest {
        for (days in listOf(0, -1, 3651)) {
            assertFailsWith<IllegalArgumentException> { parent.policies.setChatRetention(days) }
        }
        for (cap in listOf(-1L, 10_001L)) {
            assertFailsWith<IllegalArgumentException> { parent.policies.setDailyGameCap(cap) }
        }
        parent.policies.setChatRetention(1)
        parent.policies.setChatRetention(3650)
        parent.policies.setDailyGameCap(0)
        parent.policies.setDailyGameCap(10_000)

        assertFailsWith<IllegalStateException> { kid.policies.setChatRetention(30) }
        assertFailsWith<IllegalStateException> { kid.policies.setDailyGameCap(500) }
        assertNull(kid.policies.get())
    }

    @Test
    fun registeringAParentKeyKeepsTheV2Settings() = runTest {
        parent.policies.setChatRetention(90)
        parent.policies.setDailyGameCap(75)
        val policy = parent.registerParentKey()

        assertTrue(policy.requireParentSignature)
        assertEquals(90, policy.chatRetentionDays)
        assertEquals(75L, policy.dailyGameCapMinor)
        val payload = policyDocument(parent).payload
        assertEquals(90, payload.getValue("chatRetentionDays").jsonPrimitive.int)
        assertEquals(75L, payload.getValue("dailyGameCapMinor").jsonPrimitive.long)
    }

    @Test
    fun aPulledPolicyCarriesBothSettingsToAnotherDevice() = runTest {
        registerEachOther(parent, kid)
        parent.policies.setChatRetention(365)
        parent.policies.setDailyGameCap(30)

        kid.pullFrom(parent)

        val policy = assertNotNull(kid.policies.get())
        assertEquals(365, policy.chatRetentionDays)
        assertEquals(30L, policy.dailyGameCapMinor)
    }

    @Test
    fun theDecoderRefusesOutOfRangeOrMistypedSettingsAndIgnoresAbsentOnes() {
        fun decode(vararg extra: Pair<String, kotlinx.serialization.json.JsonElement>) =
            DocumentPayloads.decodeHouseholdPolicy(
                buildJsonObject {
                    put("fiatCurrency", "EUR")
                    put("requireParentSignature", true)
                    extra.forEach { (key, value) -> put(key, value) }
                },
            )

        val v1 = assertNotNull(decode())
        assertNull(v1.chatRetentionDays)
        assertNull(v1.dailyGameCapMinor)
        assertNull(assertNotNull(decode("chatRetentionDays" to JsonNull)).chatRetentionDays)
        assertEquals(3650, assertNotNull(decode("chatRetentionDays" to JsonPrimitive(3650))).chatRetentionDays)
        assertEquals(0L, assertNotNull(decode("dailyGameCapMinor" to JsonPrimitive(0))).dailyGameCapMinor)
        for (bad in listOf(
            "chatRetentionDays" to JsonPrimitive(0),
            "chatRetentionDays" to JsonPrimitive(3651),
            "chatRetentionDays" to JsonPrimitive("30"),
            "chatRetentionDays" to JsonPrimitive(30.5),
            "dailyGameCapMinor" to JsonPrimitive(-1),
            "dailyGameCapMinor" to JsonPrimitive(10_001),
            "dailyGameCapMinor" to JsonPrimitive(true),
        )) {
            assertNull(decode(bad), bad.toString())
        }
    }

    @Test
    fun theSyncedCapDecidesWhichPlaysCountOnEveryDevice() = runTest {
        registerEachOther(parent, kid)
        parent.addKid(KID_X.value)
        kid.applier.applyDocuments(parent.queuedDocuments())
        repeat(3) { assertIs<GameCreditResult.Credited>(kid.playGame(KID_X, saplingSums, perfectTimed)) }
        parent.applier.applyEvents(kid.queuedEvents())
        assertEquals(Money(45), balanceOf(parent))
        assertEquals(Money(45), balanceOf(kid))

        // The parent lowers the cap to 20: only the first 15-point play of the day still fits, on every device, at
        // once (the caches are rebuilt without a new event).
        parent.policies.setDailyGameCap(20)
        assertEquals(Money(15), balanceOf(parent))
        assertEquals(2, parent.ledger.observeAnomalies(KID_X).first().count { it is LedgerAnomaly.DailyCapExceeded })
        kid.applier.applyDocuments(parent.queuedDocuments())
        assertEquals(Money(15), balanceOf(kid))
        assertEquals(Money(5), kid.ledger.remainingGameCap(KID_X, kid.calendar.today(), kid.gbp))
        val clamped = assertIs<GameCreditResult.Credited>(kid.playGame(KID_X, saplingSums, perfectTimed))
        assertEquals(Money(5), clamped.amount)
        assertEquals(GameCreditResult.CapReached, kid.playGame(KID_X, saplingSums, perfectTimed))

        // Back to the default (50): every play counts again.
        parent.applier.applyEvents(kid.queuedEvents())
        parent.policies.setDailyGameCap(null)
        assertEquals(Money(50), balanceOf(parent))
        kid.applier.applyDocuments(parent.queuedDocuments())
        assertEquals(Money(50), balanceOf(kid))
        assertTrue(kid.ledger.observeAnomalies(KID_X).first().none { it is LedgerAnomaly.DailyCapExceeded })
    }
}
