package dev.kpetriv.sprout.core.sync

/** Wall-clock time for backoff scheduling; injected so tests control it. */
fun interface SyncClock {
    fun nowEpochMs(): Long
}
