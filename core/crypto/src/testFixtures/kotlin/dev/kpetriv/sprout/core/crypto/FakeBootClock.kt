package dev.kpetriv.sprout.core.crypto

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** [BootClock] that only moves when a test tells it to, and can simulate reboots. Test fixture only. */
class FakeBootClock(start: Duration = 10.seconds, bootId: String = "boot-1") : BootClock {
    @Volatile
    private var elapsed: Duration = start

    @Volatile
    private var currentBootId: String = bootId

    private var boots = 1

    override fun elapsedRealtime(): Duration = elapsed

    override fun bootId(): String = currentBootId

    @Synchronized
    fun advanceBy(duration: Duration) {
        require(!duration.isNegative()) { "Elapsed realtime cannot go backwards; use setElapsed to simulate that" }
        elapsed += duration
    }

    /** Sets elapsed realtime to any value, including an earlier one (a missed reboot or an edited state). */
    @Synchronized
    fun setElapsed(value: Duration) {
        elapsed = value
    }

    /** Simulates a reboot: a new boot id and elapsed realtime restarting at [uptimeAfterBoot]. */
    @Synchronized
    fun reboot(uptimeAfterBoot: Duration = 5.seconds) {
        boots++
        currentBootId = "boot-$boots"
        elapsed = uptimeAfterBoot
    }
}
