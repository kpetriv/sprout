package dev.kpetriv.sprout.core.database

import dev.kpetriv.sprout.core.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * A [MonotonicClock] for tests that follows [wall] (a device whose clock nobody touches): its elapsed realtime moves
 * as [wall] does, from [uptime] after boot [bootCount]. [advance] moves it alone, which is what a wall clock set back
 * by as much looks like; [reboot] starts a new boot.
 */
class FakeMonotonicClock(private val wall: Clock, uptime: Duration = 1.hours, private var boot: Long? = 1) :
    MonotonicClock {
    private var anchorWallMs = wall.now().toEpochMilliseconds()
    private var anchorElapsedMs = uptime.inWholeMilliseconds

    override fun elapsedRealtimeMs(): Long = anchorElapsedMs + (wall.now().toEpochMilliseconds() - anchorWallMs)

    override fun bootCount(): Long? = boot

    /** Moves the monotonic clock by [by] without the wall clock. */
    fun advance(by: Duration) {
        anchorElapsedMs += by.inWholeMilliseconds
    }

    /** A restart: the boot count goes up (it stays unknown when it was) and the elapsed realtime restarts at [uptime]. */
    fun reboot(uptime: Duration = Duration.ZERO) {
        boot = boot?.plus(1)
        anchorWallMs = wall.now().toEpochMilliseconds()
        anchorElapsedMs = uptime.inWholeMilliseconds
    }
}
