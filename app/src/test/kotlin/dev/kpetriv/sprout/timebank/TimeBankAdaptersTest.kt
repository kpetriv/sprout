package dev.kpetriv.sprout.timebank

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import dev.kpetriv.sprout.core.database.TestHousehold
import dev.kpetriv.sprout.core.database.usecase.TimeBankStatusReader
import dev.kpetriv.sprout.core.domainledger.Account
import dev.kpetriv.sprout.core.domainledger.AppendResult
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.domainledger.TimeBankCredited
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.feature.timebank.TimeBankRefusal
import dev.kpetriv.sprout.feature.timebank.TimeBankRefusedException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** The time bank adapters over a real Room household (the trial one) and Robolectric's notification manager. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class) // Not the Hilt app: its startup opens the encrypted database.
class TimeBankAdaptersTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val household = TestHousehold(context)
    private val kidId = KidId("kid-leo")
    private val scheduled = mutableListOf<Pair<KidId, Long>>()
    private val cancelled = mutableListOf<KidId>()
    private val autoStop = object : TimeBankAutoStop {
        override fun schedule(kidId: KidId, startedAt: Instant, balanceMinutes: Long) {
            scheduled += kidId to balanceMinutes
        }

        override fun cancel(kidId: KidId) {
            cancelled += kidId
        }
    }
    private val notifier = TimeBankNotifier(context)
    private val reader = TimeBankStatusReader(household.ledger, household.accounts)
    private val control = UseCaseTimeBankControl(
        household.startTimeBank,
        household.stopTimeBank,
        reader,
        notifier,
        autoStop,
    )
    private val source = LedgerTimeBankStateSource(household.ledger, reader)

    @After
    fun close() = household.close()

    private val notifications get() = shadowOf(context.getSystemService(NotificationManager::class.java))

    private var minutesAccount: Account? = null

    private suspend fun withMinutes(minutes: Long) {
        val (_, accounts) = household.addKid(kidId.value)
        minutesAccount = accounts.first { it.currency == Currency.Minutes }
        grant(minutes)
    }

    private suspend fun grant(minutes: Long) {
        if (minutes == 0L) return
        val credit = household.writer.append(kidId, requireNotNull(minutesAccount).id) {
            TimeBankCredited(it, Money(minutes), grantedBy = null, rewardId = "manual")
        }
        assertIs<AppendResult.Accepted>(credit)
    }

    @Test
    fun `start posts the ongoing notification and the auto-stop, stop debits and removes both`() = runTest {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        withMinutes(30)

        assertTrue(control.start(kidId).isSuccess)
        assertEquals(listOf(kidId to 30L), scheduled)
        val posted = notifications.allNotifications.single()
        assertTrue(posted.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(TimeBankNotifier.CHANNEL_ID, posted.channelId)
        assertEquals(setOf(kidId), notifier.activeKids())
        val running = source.observe(kidId).first()
        assertTrue(running?.running == true)

        household.clock.advanceBy(12.minutes + 30.seconds)
        assertEquals(12L, control.stop(kidId).getOrThrow())
        assertTrue(notifications.allNotifications.isEmpty())
        assertEquals(listOf(kidId), cancelled)
        val stopped = source.observe(kidId).first()
        assertEquals(18L, stopped?.balanceMinutes)
        assertEquals(listOf(12L), stopped?.sessions?.map { it.minutesUsed })
    }

    @Test
    fun `refusals reach the screen as such, and the timer works without notification permission`() = runTest {
        withMinutes(0)
        val empty = assertIs<TimeBankRefusedException>(control.start(kidId).exceptionOrNull())
        assertEquals(TimeBankRefusal.NO_BALANCE, empty.reason)
        val idle = assertIs<TimeBankRefusedException>(control.stop(kidId).exceptionOrNull())
        assertEquals(TimeBankRefusal.NOT_RUNNING, idle.reason)

        grant(5)
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(control.start(kidId).isSuccess)
        assertTrue(notifications.allNotifications.isEmpty())
        val twice = assertIs<TimeBankRefusedException>(control.start(kidId).exceptionOrNull())
        assertEquals(TimeBankRefusal.ALREADY_RUNNING, twice.reason)
    }

    @Test
    fun `the auto-stop is due a little after the balance runs out, never in the past`() {
        val start = Instant.fromEpochSeconds(1_000)
        assertEquals(10.minutes + 5.seconds, WorkManagerTimeBankAutoStop.delayUntilEmpty(start, 10, start))
        assertEquals(5.seconds, WorkManagerTimeBankAutoStop.delayUntilEmpty(start, 10, start + 10.minutes))
        assertEquals(0.seconds, WorkManagerTimeBankAutoStop.delayUntilEmpty(start, 10, start + 1.minutes * 60))
    }

    @Test
    fun `finished sessions are listed newest first with their start time`() = runTest {
        withMinutes(60)
        val firstStart = household.clock.now()
        control.start(kidId)
        household.clock.advanceBy(5.minutes)
        control.stop(kidId)
        household.clock.advanceBy(1.minutes)
        val secondStart = household.clock.now()
        control.start(kidId)
        household.clock.advanceBy(3.minutes)
        control.stop(kidId)

        val sessions = assertNotNull(source.observe(kidId).first()).sessions
        assertEquals(listOf(secondStart to 3L, firstStart to 5L), sessions.map { it.startedAt to it.minutesUsed })
    }
}
