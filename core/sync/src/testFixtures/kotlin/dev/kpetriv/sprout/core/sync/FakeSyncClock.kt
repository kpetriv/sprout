package dev.kpetriv.sprout.core.sync

/** A [SyncClock] that only moves when a test tells it to. */
class FakeSyncClock(start: Long = 0) : SyncClock {
    @Volatile
    var nowMs: Long = start
        private set

    override fun nowEpochMs(): Long = nowMs

    @Synchronized
    fun advanceBy(ms: Long) {
        require(ms >= 0) { "FakeSyncClock cannot go backwards" }
        nowMs += ms
    }
}
