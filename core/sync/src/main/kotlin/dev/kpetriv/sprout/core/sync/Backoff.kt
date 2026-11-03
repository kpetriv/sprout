package dev.kpetriv.sprout.core.sync

import kotlin.random.Random

/** How long to wait before retrying an outbox item that failed. */
fun interface Backoff {
    /**
     * Delay in milliseconds before the next attempt of an item that has now failed [attempts] + 1 times (so
     * [attempts] is the count of earlier failures, 0 on the first). [retryAfterMs] is the server's minimum, if any.
     */
    fun delayMs(attempts: Int, retryAfterMs: Long?): Long
}

/** Randomises a computed delay so devices that failed together do not retry together. */
fun interface Jitter {
    fun apply(delayMs: Long): Long

    companion object {
        /** No jitter (tests). */
        val None: Jitter = Jitter { it }

        /** "Equal jitter": half the delay, plus a random amount up to the other half. */
        fun equal(random: Random = Random.Default): Jitter = Jitter { delay ->
            val half = delay / 2
            val rest = delay - half
            // rest is at most Long.MAX_VALUE / 2 + 1, so neither rest + 1 nor half + rest can overflow.
            half + if (rest > 0) random.nextLong(rest + 1) else 0
        }
    }
}

/**
 * `min(maxMs, baseMs * 2^attempts)` with [jitter] applied, never less than the server's `retryAfterMs` (itself
 * capped at [maxMs]). Defaults: 1 s doubling to 1 h.
 */
class ExponentialBackoff(
    private val baseMs: Long = 1_000,
    private val maxMs: Long = 3_600_000,
    private val jitter: Jitter = Jitter.equal(),
) : Backoff {
    init {
        require(baseMs > 0) { "baseMs must be positive" }
        require(maxMs >= baseMs) { "maxMs must be at least baseMs" }
    }

    override fun delayMs(attempts: Int, retryAfterMs: Long?): Long {
        // Doubling saturates at maxMs; compare against half of it so the multiplication never overflows, even with
        // maxMs near Long.MAX_VALUE.
        var delay = baseMs
        repeat(attempts.coerceIn(0, MAX_DOUBLINGS)) { delay = if (delay > maxMs / 2) maxMs else delay * 2 }
        val jittered = jitter.apply(delay).coerceIn(0, maxMs)
        return maxOf(jittered, (retryAfterMs ?: 0).coerceIn(0, maxMs))
    }

    private companion object {
        const val MAX_DOUBLINGS = 63
    }
}
