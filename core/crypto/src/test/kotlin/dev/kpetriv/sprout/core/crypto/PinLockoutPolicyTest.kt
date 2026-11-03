package dev.kpetriv.sprout.core.crypto

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class PinLockoutPolicyTest {
    private val store = InMemoryLockoutStateStore()
    private val bootClock = FakeBootClock(start = 1.hours)
    private var availability = ParentAuthAvailability.STRONG_BIOMETRIC_AVAILABLE

    /** Stands in for the lock feature's check of the registered parent key. */
    private var keyUsable = true
    private var keyHealthFails = false
    private val policy = newPolicy()

    private fun newPolicy() = PinLockoutPolicy(store, bootClock) {
        check(!keyHealthFails) { "registry unreadable" }
        availability == ParentAuthAvailability.STRONG_BIOMETRIC_AVAILABLE && keyUsable
    }

    private suspend fun fail(times: Int): LockoutDecision {
        var decision: LockoutDecision = LockoutDecision.Allowed
        repeat(times) { decision = policy.registerFailure() }
        return decision
    }

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
        assertEquals(11, PinLockoutPolicy.CAP_FAILURES)
    }

    @Test
    fun `first three failures do not lock`() = runTest {
        repeat(3) { assertEquals(LockoutDecision.Allowed, policy.registerFailure()) }
        assertEquals(LockoutDecision.Allowed, policy.decision())
        val state = assertNotNull(store.state)
        assertEquals(3, state.consecutiveFailures)
        assertNull(state.lockoutStartedAt)
    }

    @Test
    fun `fourth failure locks for 30 s and fifth for 60 s`() = runTest {
        assertEquals(LockoutDecision.Wait(30.seconds), fail(4))
        bootClock.advanceBy(29.seconds)
        assertEquals(LockoutDecision.Wait(1.seconds), policy.decision())
        bootClock.advanceBy(1.seconds)
        assertEquals(LockoutDecision.Allowed, policy.decision())
        assertEquals(LockoutDecision.Wait(60.seconds), policy.registerFailure())
    }

    @Test
    fun `success resets the counter`() = runTest {
        fail(5)
        policy.registerSuccess()
        assertEquals(PinLockoutState.Initial, store.state)
        assertEquals(LockoutDecision.Allowed, policy.decision())
        assertEquals(LockoutDecision.Allowed, policy.registerFailure())
    }

    @Test
    fun `reaching the cap requires a strong biometric instead of a timed wait`() = runTest {
        assertEquals(LockoutDecision.Wait(1920.seconds), fail(10))
        bootClock.advanceBy(1920.seconds)
        assertEquals(LockoutDecision.RequireBiometric, policy.registerFailure())
        // Waiting does not reopen the PIN path.
        bootClock.advanceBy(2.days)
        assertEquals(LockoutDecision.RequireBiometric, policy.decision())
        assertEquals(LockoutDecision.RequireBiometric, policy.registerFailure())

        policy.registerBiometricSuccess()
        assertEquals(LockoutDecision.Allowed, policy.decision())
        assertEquals(PinLockoutState.Initial, store.state)
    }

    @Test
    fun `without a strong biometric the cap is a one hour wait per failure`() = runTest {
        availability = ParentAuthAvailability.NOT_ENROLLED
        fail(10)
        bootClock.advanceBy(1920.seconds)
        assertEquals(LockoutDecision.Wait(1.hours), policy.registerFailure())
        bootClock.advanceBy(59.minutes)
        assertEquals(LockoutDecision.Wait(1.minutes), policy.decision())
        bootClock.advanceBy(1.minutes)
        assertEquals(LockoutDecision.Allowed, policy.decision())
        assertEquals(LockoutDecision.Wait(1.hours), policy.registerFailure())

        // Enrolling a biometric later switches the capped lockout to RequireBiometric.
        availability = ParentAuthAvailability.STRONG_BIOMETRIC_AVAILABLE
        assertEquals(LockoutDecision.RequireBiometric, policy.decision())
    }

    @Test
    fun `with an invalidated or unregistered parent key the cap is a one hour wait, never a closed PIN path`() =
        runTest {
            // Fingerprint changed, no key registered, or a key that differs from the registered one.
            keyUsable = false
            fail(10)
            bootClock.advanceBy(1920.seconds)
            assertEquals(LockoutDecision.Wait(1.hours), policy.registerFailure())
            bootClock.advanceBy(1.hours)
            assertEquals(LockoutDecision.Allowed, policy.decision())
            assertEquals(LockoutDecision.Wait(1.hours), policy.registerFailure())
            bootClock.advanceBy(2.days)
            assertEquals(LockoutDecision.Allowed, policy.decision())

            // Once the key is renewed, the capped lockout closes the PIN path again.
            keyUsable = true
            policy.registerFailure()
            assertEquals(LockoutDecision.RequireBiometric, policy.decision())
        }

    @Test
    fun `a failing key health check degrades to the timed wait`() = runTest {
        keyHealthFails = true
        fail(10)
        bootClock.advanceBy(1920.seconds)
        assertEquals(LockoutDecision.Wait(1.hours), policy.registerFailure())
    }

    @Test
    fun `lockout survives process death`() = runTest {
        fail(6)
        bootClock.advanceBy(50.seconds)
        val restarted = newPolicy()
        assertEquals(LockoutDecision.Wait(70.seconds), restarted.decision())
        assertEquals(LockoutDecision.Wait(240.seconds), restarted.registerFailure())
    }

    @Test
    fun `a reboot during a lockout makes the whole lockout due again`() = runTest {
        fail(9) // 960 s lockout
        bootClock.advanceBy(900.seconds)
        assertEquals(LockoutDecision.Wait(60.seconds), policy.decision())

        bootClock.reboot(uptimeAfterBoot = 20.seconds)
        val afterBoot = newPolicy()
        assertEquals(LockoutDecision.Wait(960.seconds), afterBoot.decision())
        val rebased = assertNotNull(store.state)
        assertEquals(bootClock.bootId(), rebased.bootId)
        assertEquals(20.seconds, rebased.lockoutStartedAt)
        assertEquals(9, rebased.consecutiveFailures)

        // Served on the new boot, it ends normally.
        bootClock.advanceBy(960.seconds)
        assertEquals(LockoutDecision.Allowed, afterBoot.decision())
    }

    @Test
    fun `repeated reboots never shorten a lockout`() = runTest {
        fail(5) // 60 s
        repeat(5) {
            bootClock.advanceBy(59.seconds)
            bootClock.reboot(uptimeAfterBoot = 3.seconds)
            assertEquals(LockoutDecision.Wait(60.seconds), newPolicy().decision())
        }
    }

    @Test
    fun `a lockout already served before a reboot does not come back`() = runTest {
        fail(4)
        bootClock.advanceBy(31.seconds)
        assertEquals(LockoutDecision.Allowed, policy.decision())
        assertNull(assertNotNull(store.state).lockoutStartedAt)

        bootClock.reboot()
        assertEquals(LockoutDecision.Allowed, newPolicy().decision())
        assertEquals(4, assertNotNull(store.state).consecutiveFailures)
    }

    @Test
    fun `elapsed time running backwards within a boot restarts the full lockout`() = runTest {
        fail(5) // 60 s from elapsed 1 h
        bootClock.setElapsed(10.minutes) // earlier than the start, same boot id: a missed reboot or edited state
        assertEquals(LockoutDecision.Wait(60.seconds), policy.decision())
        bootClock.advanceBy(60.seconds)
        assertEquals(LockoutDecision.Allowed, policy.decision())
    }

    @Test
    fun `a stored start far in the past cannot lengthen or skip the schedule`() = runTest {
        // Elapsed realtime moved far forward (for example days of deep sleep): the lockout is simply over.
        fail(7)
        bootClock.advanceBy(30.days)
        assertEquals(LockoutDecision.Allowed, policy.decision())

        // A stored start in the future of the same boot is treated as "time ran backwards": full lockout, no more.
        store.state = PinLockoutState(4, bootClock.elapsedRealtime() + 3650.hours, bootClock.bootId())
        assertEquals(LockoutDecision.Wait(30.seconds), newPolicy().decision())
    }

    @Test
    fun `unreadable state is treated as the cap`() = runTest {
        store.state = PinLockoutState(consecutiveFailures = 1)
        store.unreadable = true
        assertEquals(LockoutDecision.RequireBiometric, policy.decision())
        val replaced = assertNotNull(store.state)
        assertEquals(PinLockoutPolicy.CAP_FAILURES, replaced.consecutiveFailures)

        availability = ParentAuthAvailability.NO_HARDWARE
        store.unreadable = true
        assertEquals(LockoutDecision.Wait(1.hours), newPolicy().decision())
    }

    @Test
    fun `a plain check does not write when nothing changed`() = runTest {
        fail(4)
        val writes = store.writes
        policy.decision()
        bootClock.advanceBy(5.seconds)
        policy.decision()
        assertEquals(writes, store.writes)
    }

    @Test
    fun `state invariants are enforced`() {
        assertFailsWith<IllegalArgumentException> { PinLockoutState(-1) }
        assertFailsWith<IllegalArgumentException> { PinLockoutState(4, 1.seconds, null) }
        assertFailsWith<IllegalArgumentException> { PinLockoutState(4, null, "boot") }
        assertFailsWith<IllegalArgumentException> { PinLockoutState(4, (-1).seconds, "boot") }
    }
}
