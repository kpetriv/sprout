package dev.kpetriv.sprout.core.database

import dev.kpetriv.sprout.core.crypto.JceEcdsaVerifier
import dev.kpetriv.sprout.core.database.usecase.ReconcileTimeBankUseCase
import dev.kpetriv.sprout.core.database.usecase.StopTimeBankUseCase
import dev.kpetriv.sprout.core.database.usecase.TimeBankRefusal
import dev.kpetriv.sprout.core.database.usecase.TimeBankResult
import dev.kpetriv.sprout.core.database.usecase.TimeBankStatusReader
import dev.kpetriv.sprout.core.domainledger.AppendResult
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.domainledger.TimeBankCredited
import dev.kpetriv.sprout.core.model.KidId
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
class TimeBankUseCasesTest {
    private val household = testHousehold()

    @After
    fun close() = household.close()

    /** A kid with [minutes] in the time bank. */
    private suspend fun TestHousehold.kidWithMinutes(minutes: Long, id: String = "kid-leo"): KidId {
        val (kid, accounts) = addKid(id)
        if (minutes > 0) {
            val credit = writer.append(kid.id, accounts.minutes().id) {
                TimeBankCredited(it, Money(minutes), grantedBy = null, rewardId = "manual")
            }
            assertIs<AppendResult.Accepted>(credit)
        }
        return kid.id
    }

    private suspend fun TestHousehold.balance(kidId: KidId): Long =
        assertNotNull(timeBankStatus.status(kidId)).balance.minorUnits

    @Test
    fun startIsRefusedWithoutBalanceOrAccountAndWhileRunning() = runTest {
        val empty = household.kidWithMinutes(0)
        assertEquals(TimeBankResult.Refused(TimeBankRefusal.NO_BALANCE), household.startTimeBank(empty))
        assertEquals(TimeBankResult.Refused(TimeBankRefusal.NO_ACCOUNT), household.startTimeBank(KidId("nobody")))

        val kid = household.kidWithMinutes(30, "kid-mia")
        val started = assertIs<TimeBankResult.Started>(household.startTimeBank(kid))
        val status = assertNotNull(household.timeBankStatus.status(kid))
        assertTrue(status.running)
        assertEquals(household.clock.now(), status.startedAt, "elapsed time comes from the start event")
        assertEquals(started.startedAt, status.startedAt)
        assertEquals(TimeBankResult.Refused(TimeBankRefusal.ALREADY_RUNNING), household.startTimeBank(kid))
    }

    @Test
    fun stopDebitsWholeElapsedMinutes() = runTest {
        val kid = household.kidWithMinutes(30)
        assertEquals(TimeBankResult.Refused(TimeBankRefusal.NOT_RUNNING), household.stopTimeBank(kid))
        val started = assertIs<TimeBankResult.Started>(household.startTimeBank(kid))

        household.clock.advanceBy(7.minutes + 30.seconds)
        val stopped = household.stopTimeBank(kid)

        assertEquals(TimeBankResult.Stopped(started.sessionId, 7), stopped)
        assertEquals(23, household.balance(kid))
        assertFalse(assertNotNull(household.timeBankStatus.status(kid)).running)
    }

    @Test
    fun stopNeverDebitsMoreThanTheBalance() = runTest {
        val kid = household.kidWithMinutes(30)
        household.startTimeBank(kid)
        household.clock.advanceBy(45.minutes)

        assertEquals(30, assertIs<TimeBankResult.Stopped>(household.stopTimeBank(kid)).minutesUsed)
        assertEquals(0, household.balance(kid))
    }

    @Test
    fun aSessionSurvivesProcessDeath() = runTest {
        val kid = household.kidWithMinutes(30)
        household.startTimeBank(kid)
        household.clock.advanceBy(12.minutes)

        // A new process: fresh repository, writer and use cases over the same database; no timer survived.
        val ledger = RoomLedgerRepository(household.database, household.clock, household.signer, JceEcdsaVerifier())
        val writer = RoomLedgerWriter(household.database, ledger, household.signer, household.authors, household.clock)
        val reader = TimeBankStatusReader(ledger, household.accounts)
        val stop = StopTimeBankUseCase(reader, writer, household.clock)

        assertEquals(12, assertIs<TimeBankResult.Stopped>(stop(kid)).minutesUsed)
        assertEquals(18, household.balance(kid))
    }

    @Test
    fun reconcileStopsSessionsThatOutlivedTheirBalance() = runTest {
        val over = household.kidWithMinutes(30, "kid-over")
        val under = household.kidWithMinutes(30, "kid-under")
        household.startTimeBank(over)
        household.clock.advanceBy(20.minutes)
        household.startTimeBank(under)
        household.clock.advanceBy(15.minutes)

        // A new process after 35 minutes: "over" ran out 5 minutes ago, "under" still has 15 left.
        val ledger = RoomLedgerRepository(household.database, household.clock, household.signer, JceEcdsaVerifier())
        val writer = RoomLedgerWriter(household.database, ledger, household.signer, household.authors, household.clock)
        val reconcile = ReconcileTimeBankUseCase(
            ledger,
            TimeBankStatusReader(ledger, household.accounts),
            writer,
            household.sessions,
            household.clock,
        )
        assertEquals(listOf(over), reconcile())

        assertEquals(0, household.balance(over))
        assertFalse(assertNotNull(household.timeBankStatus.status(over)).running)
        assertTrue(assertNotNull(household.timeBankStatus.status(under)).running)
        assertEquals(emptyList(), reconcile(), "nothing left to reconcile")
    }

    @Test
    fun reconcileLeavesSessionsStartedOnAnotherDevice() = runTest {
        val parent = parentDevice()
        val kid = kidDevice()
        try {
            registerEachOther(parent, kid)
            val (kidX, accounts) = parent.addKid(KID_X.value)
            val minutesAccount = with(parent) { accounts.minutes() }
            // The household policy (and parent key) the kid's ingest needs before it accepts events.
            parent.registerParentKey()
            val credit = parent.writer.appendAsParent(kidX.id, minutesAccount.id, parent.authorization()) {
                TimeBankCredited(it, Money(10), grantedBy = null, rewardId = "manual")
            }
            assertIs<AppendResult.Accepted>(credit, credit.toString())
            kid.pullFrom(parent)
            assertIs<TimeBankResult.Started>(kid.startTimeBank(KID_X))
            parent.pullFrom(kid)
            assertTrue(assertNotNull(parent.timeBankStatus.status(KID_X)).running)

            parent.clock.advanceBy(60.minutes)
            kid.clock.advanceBy(60.minutes)

            assertEquals(emptyList(), parent.reconcileTimeBank(), "the kid's device owns its session")
            assertEquals(listOf(KID_X), kid.reconcileTimeBank())
            assertEquals(0, kid.balance(KID_X))
        } finally {
            parent.close()
            kid.close()
        }
    }
}
