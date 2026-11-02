package dev.kpetriv.sprout.core.time

import kotlin.time.Duration
import kotlin.time.Instant

/** A [Clock] that only moves when a test tells it to. Safe to read from any thread. */
class FakeClock(start: Instant = Instant.fromEpochSeconds(0)) : Clock {
    @Volatile
    private var current: Instant = start

    override fun now(): Instant = current

    @Synchronized
    fun advanceBy(duration: Duration) {
        require(!duration.isNegative()) { "FakeClock cannot go backwards" }
        current += duration
    }
}
