package dev.kpetriv.sprout.core.sync

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BackoffTest {
    @Test
    fun doublesFromTheBaseAndSaturatesAtTheMaximum() {
        val backoff = ExponentialBackoff(baseMs = 1_000, maxMs = 10_000, jitter = Jitter.None)
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 10_000L, 10_000L), (0..5).map { backoff.delayMs(it, null) })
        assertEquals(10_000, backoff.delayMs(Int.MAX_VALUE, null))
        assertEquals(1_000, backoff.delayMs(-3, null))
    }

    @Test
    fun neverOverflowsNearLongMaxValue() {
        val huge = ExponentialBackoff(baseMs = Long.MAX_VALUE / 3, maxMs = Long.MAX_VALUE, jitter = Jitter.None)
        val delays = (0..100).map { huge.delayMs(it, null) }
        assertTrue(delays.all { it > 0 }, "no wrap-around to a negative delay")
        assertEquals(delays.sorted(), delays, "monotonic")
        assertEquals(Long.MAX_VALUE, delays.last())

        val oneMs = ExponentialBackoff(baseMs = 1, maxMs = Long.MAX_VALUE, jitter = Jitter.None)
        assertEquals(1L shl 62, oneMs.delayMs(62, null))
        assertEquals(Long.MAX_VALUE, oneMs.delayMs(63, null))
        assertEquals(Long.MAX_VALUE, oneMs.delayMs(Int.MAX_VALUE, null))

        val jittered = ExponentialBackoff(baseMs = 1, maxMs = Long.MAX_VALUE, jitter = Jitter.equal(Random(1)))
        repeat(50) { assertTrue(jittered.delayMs(Int.MAX_VALUE, Long.MAX_VALUE) > 0) }
    }

    @Test
    fun equalJitterStaysWithinTheUpperHalf() {
        val jitter = Jitter.equal(Random(7))
        repeat(200) {
            val delay = jitter.apply(Long.MAX_VALUE)
            assertTrue(delay >= Long.MAX_VALUE / 2)
        }
        repeat(200) { assertTrue(jitter.apply(1_000) in 500..1_000) }
        assertEquals(0, jitter.apply(0))
    }

    @Test
    fun retryAfterIsAMinimumCappedAtTheMaximum() {
        val backoff = ExponentialBackoff(baseMs = 1_000, maxMs = 60_000, jitter = Jitter.None)
        assertEquals(30_000, backoff.delayMs(0, retryAfterMs = 30_000))
        assertEquals(60_000, backoff.delayMs(0, retryAfterMs = Long.MAX_VALUE))
        assertEquals(1_000, backoff.delayMs(0, retryAfterMs = -5))
    }
}
