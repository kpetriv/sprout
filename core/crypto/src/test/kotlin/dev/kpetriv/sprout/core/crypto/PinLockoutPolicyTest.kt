package dev.kpetriv.sprout.core.crypto

import dev.kpetriv.sprout.core.time.FakeClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class PinLockoutPolicyTest {
    private val clock = FakeClock(Instant.fromEpochSeconds(1_800_000_000))
    private val policy = PinLockoutPolicy(clock)

    @Test
    fun `schedule is free for three failures then doubles from 30 s up to 1 h`() {
        val expected = listOf(
            Duration.ZERO, Duration.ZERO, Duration.ZERO,
            30.seconds, 60.seconds, 120.seconds, 240.seconds, 480.seconds, 960.seconds, 1920.seconds,
            1.hours, 1.hours,
        )
        assertEquals(expected, (1..12).map { PinLockoutPolicy.lockoutAfter(it) })
        assertEquals(1.hours, PinLockoutPolicy.lockoutAfter(1_000))
        assertEquals(1.hours, PinLockoutPolicy.lockoutAfter(Int.MAX_VALUE))
        assertEquals(Duration.ZERO, PinLockoutPolicy.lockoutAfter(0))
    }

    @Test
    fun `first three failures do not lock`() {
        var state = PinLockoutState.Initial
        repeat(3) {
            state = policy.registerFailure(state)
            assertNull(state.lockedUntil)
            assertTrue(policy.canAttempt(state))
        }
        assertEquals(3, state.consecutiveFailures)
    }

    @Test
    fun `fourth failure locks for 30 s and fifth for 60 s`() {
        var state = PinLockoutState.Initial
        repeat(4) { state = policy.registerFailure(state) }
        assertFalse(policy.canAttempt(state))
        assertEquals(30.seconds, policy.remainingLockout(state))

        clock.advanceBy(29.seconds)
        assertEquals(1.seconds, policy.remainingLockout(state))
        assertFalse(policy.canAttempt(state))

        clock.advanceBy(1.seconds)
        assertTrue(policy.canAttempt(state))

        state = policy.registerFailure(state)
        assertEquals(60.seconds, policy.remainingLockout(state))
    }

    @Test
    fun `success resets the counter`() {
        var state = PinLockoutState.Initial
        repeat(5) { state = policy.registerFailure(state) }
        state = policy.registerSuccess()
        assertEquals(PinLockoutState.Initial, state)
        assertTrue(policy.canAttempt(state))
        state = policy.registerFailure(state)
        assertNull(state.lockedUntil)
    }

    @Test
    fun `lockout is capped at one hour`() {
        var state = PinLockoutState.Initial
        repeat(20) { state = policy.registerFailure(state) }
        assertEquals(1.hours, policy.remainingLockout(state))
        clock.advanceBy(59.minutes)
        assertEquals(1.minutes, policy.remainingLockout(state))
    }

    @Test
    fun `a lockout end far in the future cannot exceed the scheduled length`() {
        // Simulates the wall clock having been moved back after the lockout was recorded.
        val state = PinLockoutState(consecutiveFailures = 4, lockedUntil = clock.now() + 3650.hours)
        assertEquals(30.seconds, policy.remainingLockout(state))
    }
}
