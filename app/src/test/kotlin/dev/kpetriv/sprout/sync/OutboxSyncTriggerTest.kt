package dev.kpetriv.sprout.sync

import android.app.Application
import androidx.room.Room
import app.cash.turbine.test
import dev.kpetriv.sprout.core.database.SproutDatabase
import dev.kpetriv.sprout.core.database.entity.OutboxEntity
import dev.kpetriv.sprout.core.database.entity.OutboxKind
import dev.kpetriv.sprout.core.time.FakeClock
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** [OutboxSyncTrigger] over a real Room database (D-M3-43): an outbox write whose head is due requests a sync. */
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
    fun anEmptyOutboxOrAHeadInBackoffRequestsNothing() = runTest {
        database.outboxDao().insert(row("e1:h1", nextAttemptAt = clock.now() + 30.minutes))
        trigger.requests().test {
            // A later row behind a head that is still backing off does not jump the queue.
            database.outboxDao().insert(row("e2:h2"))
            expectNoEvents()
            clock.advanceBy(31.minutes)
            database.outboxDao().insert(row("e3:h3"))
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
    }
}
