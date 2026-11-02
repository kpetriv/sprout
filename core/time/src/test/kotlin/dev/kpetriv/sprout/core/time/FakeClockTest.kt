package dev.kpetriv.sprout.core.time

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class FakeClockTest {
    @Test
    fun startsAtTheEpochByDefault() {
        assertEquals(Instant.fromEpochSeconds(0), FakeClock().now())
    }

    @Test
    fun advancesOnlyWhenAsked() {
        val clock = FakeClock(Instant.fromEpochSeconds(100))
        assertEquals(Instant.fromEpochSeconds(100), clock.now())
        clock.advanceBy(5.seconds)
        assertEquals(Instant.fromEpochSeconds(105), clock.now())
    }

    @Test
    fun refusesToGoBackwards() {
        assertFailsWith<IllegalArgumentException> { FakeClock().advanceBy((-1).seconds) }
    }

    @Test
    fun advancesFromConcurrentCallersWithoutLosingUpdates() {
        val clock = FakeClock()
        val threads = List(THREADS) {
            Thread { repeat(ADVANCES_PER_THREAD) { clock.advanceBy(1.seconds) } }
        }
        threads.forEach(Thread::start)
        threads.forEach(Thread::join)
        assertEquals(Instant.fromEpochSeconds((THREADS * ADVANCES_PER_THREAD).toLong()), clock.now())
    }

    private companion object {
        const val THREADS = 4
        const val ADVANCES_PER_THREAD = 1_000
    }
}
