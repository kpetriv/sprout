package dev.kpetriv.sprout.core.time

import kotlin.time.Instant

/**
 * Source of the current time. Inject this instead of reading the system clock so tests control time.
 * Use `FakeClock` (this module's test fixtures) in tests.
 */
interface Clock {
    fun now(): Instant
}

/** The real wall clock. */
object SystemClock : Clock {
    override fun now(): Instant = kotlin.time.Clock.System.now()
}
