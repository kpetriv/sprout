package dev.kpetriv.sprout.sync

import android.app.Application
import androidx.room.Room
import app.cash.turbine.test
import dev.kpetriv.sprout.core.database.SproutDatabase
import dev.kpetriv.sprout.core.database.entity.OutboxEntity
import dev.kpetriv.sprout.core.database.entity.OutboxKind
import dev.kpetriv.sprout.core.time.FakeClock
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * [OutboxSyncTrigger] over a real Room database (D-M3-43): an outbox write whose head is due requests a sync; a head
 * in backoff requests one when its next attempt time arrives (review M6), and rows behind it do not jump it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class) // Not the Hilt app: its startup opens the encrypted database.
class OutboxSyncTriggerTest {
    private val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), SproutDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val clock = FakeClock(Instant.fromEpochSeconds(1_800_000_000))
    private val trigger = OutboxSyncTrigger(database, clock)

    @After
    fun close() = database.close()

    /** Lets Room's invalidation (on its own executor) reach the trigger before virtual time moves. */
    private fun kotlinx.coroutines.test.TestScope.awaitHeadRead() {
        repeat(5) {
            Thread.sleep(50)
            runCurrent()
        }
    }

    private fun row(refId: String, nextAttemptAt: Instant = clock.now()) = OutboxEntity(
        kind = OutboxKind.LEDGER_EVENT,
        refId = refId,
        payload = "{}",
        createdAtEpochMs = clock.now().toEpochMilliseconds(),
        nextAttemptAtEpochMs = nextAttemptAt.toEpochMilliseconds(),
    )

    @Test
    fun aDueOutboxRowRequestsASync() = runTest {
        trigger.requests().test {
            database.outboxDao().insert(row("e1:h1"))
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun anEmptyOutboxRequestsNothing() = runTest {
        trigger.requests().test {
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun aHeadInBackoffWakesTheLoopAtItsNextAttemptTime() = runTest {
        database.outboxDao().insert(row("e1:h1", nextAttemptAt = clock.now() + 30.minutes))
        trigger.requests().test {
            awaitHeadRead()
            expectNoEvents()
            advanceTimeBy(30.minutes - 1.seconds)
            expectNoEvents()
            advanceTimeBy(2.seconds)
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun aRowBehindAHeadInBackoffDoesNotJumpTheQueue() = runTest {
        database.outboxDao().insert(row("e1:h1", nextAttemptAt = clock.now() + 30.minutes))
        trigger.requests().test {
            // A later row behind a head that is still backing off does not jump the queue.
            database.outboxDao().insert(row("e2:h2"))
            awaitHeadRead()
            expectNoEvents()
            clock.advanceBy(31.minutes)
            database.outboxDao().insert(row("e3:h3"))
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
    }
}
