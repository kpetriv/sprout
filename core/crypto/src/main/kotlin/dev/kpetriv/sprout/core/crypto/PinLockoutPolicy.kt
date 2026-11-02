package dev.kpetriv.sprout.core.crypto

import dev.kpetriv.sprout.core.time.Clock
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Persisted by the lock feature between attempts (and across process death). */
data class PinLockoutState(
    val consecutiveFailures: Int = 0,
    /** Wall-clock end of the current lockout, or null when not locked. */
    val lockedUntil: Instant? = null,
) {
    companion object {
        val Initial = PinLockoutState()
    }
}

/**
 * Exponential lockout for failed parent-PIN attempts (threat T7). The first [FREE_ATTEMPTS] failures cost
 * nothing; each later failure locks for [BASE_LOCKOUT] doubled per extra failure, capped at [MAX_LOCKOUT]:
 * 0, 0, 0, 30 s, 60 s, 120 s, ... 1 h.
 *
 * Pure logic over an injected [Clock]. Moving the wall clock backwards cannot extend a lockout beyond its
 * scheduled length; moving it forwards does shorten it, so the lock feature should also persist and check a
 * monotonic (elapsed-realtime) deadline while the process is alive.
 */
class PinLockoutPolicy @Inject constructor(private val clock: Clock) {
    /** Records a wrong PIN. Callers check [canAttempt] first; this does not refuse attempts itself. */
    fun registerFailure(state: PinLockoutState): PinLockoutState {
        val failures = state.consecutiveFailures.coerceAtLeast(0) + 1
        val lockout = lockoutAfter(failures)
        return PinLockoutState(
            consecutiveFailures = failures,
            lockedUntil = if (lockout == Duration.ZERO) null else clock.now() + lockout,
        )
    }

    /** A correct PIN clears the failure count. */
    fun registerSuccess(): PinLockoutState = PinLockoutState.Initial

    /** Time left before the next attempt is allowed; zero when not locked. */
    fun remainingLockout(state: PinLockoutState): Duration {
        val until = state.lockedUntil ?: return Duration.ZERO
        val remaining = (until - clock.now()).coerceAtLeast(Duration.ZERO)
        return remaining.coerceAtMost(lockoutAfter(state.consecutiveFailures))
    }

    fun canAttempt(state: PinLockoutState): Boolean = remainingLockout(state) == Duration.ZERO

    companion object {
        const val FREE_ATTEMPTS = 3
        val BASE_LOCKOUT: Duration = 30.seconds
        val MAX_LOCKOUT: Duration = 1.hours

        /** Doubling stops well before overflow; 30 s * 2^7 already exceeds the 1 h cap. */
        private const val MAX_DOUBLINGS = 16

        /** Lockout imposed right after the [failures]-th consecutive failure. */
        fun lockoutAfter(failures: Int): Duration {
            if (failures <= FREE_ATTEMPTS) return Duration.ZERO
            val doublings = (failures - FREE_ATTEMPTS - 1).coerceAtMost(MAX_DOUBLINGS)
            return (BASE_LOCKOUT * (1 shl doublings)).coerceAtMost(MAX_LOCKOUT)
        }
    }
}
