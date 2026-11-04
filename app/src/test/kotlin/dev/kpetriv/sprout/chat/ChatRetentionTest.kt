package dev.kpetriv.sprout.chat

import dev.kpetriv.sprout.core.database.ChatPruneOutcome
import dev.kpetriv.sprout.core.database.ChatPruneResult
import dev.kpetriv.sprout.core.database.Session
import dev.kpetriv.sprout.core.database.entity.RegistrationStatus
import dev.kpetriv.sprout.core.syncfirebase.FunctionsError
import dev.kpetriv.sprout.core.syncfirebase.FunctionsException
import dev.kpetriv.sprout.core.time.FakeClock
import dev.kpetriv.sprout.feature.settings.ChatPurgeOutcome
import dev.kpetriv.sprout.kidSession
import dev.kpetriv.sprout.parentSession
import dev.kpetriv.sprout.trialSession
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** The chat retention as the app runs it (M7 §2, D-M7-8): who calls the server, with which cut, and how often. */
class ChatRetentionTest {
    private val clock = FakeClock(Instant.parse("2026-11-04T10:00:00Z"))
    private var session: Session? = parentSession()
    private var outcome: ChatPruneOutcome = ChatPruneOutcome.Pruned(result(clock.now() - 30.days))
    private var localPurgeFails = false
    private var policyUnsent = false
    private val calls = mutableListOf<Pair<Instant, Boolean>>()
    private val budgets = mutableListOf<Int>()
    private var answer: Result<Boolean> = Result.success(true)
    private val record = InMemoryRecord()
    private val retention = ChatRetention(
        session = { session },
        pruneLocal = { outcome },
        purgeLocal = {
            check(!localPurgeFails) { "disk" }
            result(clock.now())
        },
        policyUnsent = { policyUnsent },
        serverPrune = { before, purge, maxCalls, onCall ->
            budgets += maxCalls
            onCall()
            calls += before to purge
            answer
        },
        householdOf = { it.householdId.value },
        record = record,
        clock = clock,
    )

    @Test
    fun `a parent device asks the server to prune with the local cut, at most once a day`() = runTest {
        retention.run()
        assertEquals(listOf((clock.now() - 30.days) to false), calls)

        clock.advanceBy(2.hours)
        retention.run()
        assertEquals(1, calls.size)

        clock.advanceBy(22.hours)
        outcome = ChatPruneOutcome.Pruned(result(clock.now() - 30.days))
        retention.run()
        assertEquals(2, calls.size)
    }

    @Test
    fun `kid phones, the trial, pending devices and a household keeping messages never call the server`() = runTest {
        session = kidSession()
        retention.run()
        session = trialSession()
        retention.run()
        session = parentSession(status = RegistrationStatus.PENDING)
        retention.run()
        session = parentSession()
        outcome = ChatPruneOutcome.KeepAll
        retention.run()
        assertEquals(emptyList(), calls)
    }

    @Test
    fun `an unsent policy change waits, and a refused or failed call is tried again next run`() = runTest {
        policyUnsent = true
        retention.run()
        assertEquals(0, calls.size)

        policyUnsent = false
        answer = Result.failure(FunctionsException(FunctionsError.Unavailable))
        retention.run()
        retention.run()
        assertEquals(2, calls.size)

        // A household that keeps its messages counts as done for the day.
        answer = Result.failure(FunctionsException(FunctionsError.FailedPrecondition))
        retention.run()
        retention.run()
        assertEquals(3, calls.size)
    }

    @Test
    fun `delete now purges here, then sends the same instant to the server`() = runTest {
        assertEquals(ChatPurgeOutcome.PURGED, retention.purgeAll())
        assertEquals(listOf(clock.now() to true), calls)
        assertNull(record.pendingPurge(REAL))
    }

    @Test
    fun `delete now without the server stays pending and is sent again later, not more than every half hour`() =
        runTest {
            answer = Result.failure(FunctionsException(FunctionsError.Unavailable))
            val purgedAt = clock.now()
            assertEquals(ChatPurgeOutcome.PURGED_ON_THIS_PHONE, retention.purgeAll())
            assertEquals(purgedAt, record.pendingPurge(REAL))

            outcome = ChatPruneOutcome.KeepAll
            answer = Result.success(false) // The server has more to delete.
            retention.run()
            assertEquals(1, calls.size) // Too soon after the purge itself.

            clock.advanceBy(31.minutes)
            retention.run()
            retention.run()
            assertEquals(listOf(purgedAt to true, purgedAt to true), calls)
            assertEquals(purgedAt, record.pendingPurge(REAL))

            clock.advanceBy(31.minutes)
            answer = Result.success(true)
            retention.run()
            assertEquals(purgedAt to true, calls.last())
            assertNull(record.pendingPurge(REAL))
        }

    @Test
    fun `the process never sends more than eight requests an hour`() = runTest {
        answer = Result.failure(FunctionsException(FunctionsError.Unavailable))
        repeat(12) {
            outcome = ChatPruneOutcome.Pruned(result(clock.now() - 30.days))
            retention.run()
            clock.advanceBy(1.minutes)
        }
        assertEquals(ChatRetention.CALLS_PER_HOUR, calls.size)
        assertEquals(listOf(4, 4, 4, 4, 4, 3, 2, 1), budgets)

        clock.advanceBy(1.hours)
        retention.run()
        assertEquals(ChatRetention.CALLS_PER_HOUR + 1, calls.size)
    }

    @Test
    fun `delete now fails when nothing could be deleted here, and a kid phone keeps it local`() = runTest {
        localPurgeFails = true
        assertEquals(ChatPurgeOutcome.FAILED, retention.purgeAll())
        localPurgeFails = false
        session = trialSession()
        assertEquals(ChatPurgeOutcome.PURGED, retention.purgeAll())
        assertEquals(emptyList(), calls)
    }

    private fun result(before: Instant) =
        ChatPruneResult(before, messages = 1, blobs = 0, readMarks = 0, keptQueued = 0)

    private class InMemoryRecord : ChatPruneRecord {
        private val last = mutableMapOf<String, Instant>()
        private val pending = mutableMapOf<String, Instant>()

        override fun lastServerPrune(household: String): Instant? = last[household]

        override fun setLastServerPrune(household: String, at: Instant) {
            last[household] = at
        }

        override fun pendingPurge(household: String): Instant? = pending[household]

        override fun setPendingPurge(household: String, before: Instant?) {
            if (before == null) pending.remove(household) else pending[household] = before
        }
    }

    private companion object {
        val REAL = dev.kpetriv.sprout.REAL_HOUSEHOLD.value
    }
}
