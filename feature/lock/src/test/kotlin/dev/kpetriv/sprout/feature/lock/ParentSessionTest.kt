package dev.kpetriv.sprout.feature.lock

import dev.kpetriv.sprout.core.crypto.FakeParentAuthorizer
import dev.kpetriv.sprout.core.crypto.LockoutDecision
import dev.kpetriv.sprout.core.crypto.ParentAuthAvailability
import dev.kpetriv.sprout.core.crypto.PinLockoutPolicy
import dev.kpetriv.sprout.feature.lock.SessionFixture.Companion.PIN
import dev.kpetriv.sprout.feature.lock.SessionFixture.Companion.WRONG_PIN
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ParentSessionTest {
    private fun TestScope.fixture(pin: String? = PIN, lockWhenLeaving: Boolean = true) =
        SessionFixture(backgroundScope, pin = pin, lockWhenLeaving = lockWhenLeaving)

    private fun test(block: suspend TestScope.() -> Unit) = runTest(UnconfinedTestDispatcher()) { block() }

    @Test
    fun `with a stored PIN the session starts locked`() = test {
        assertEquals(ParentSessionState.Locked, fixture().session.state.value)
    }

    @Test
    fun `without a PIN the session requires setup and refuses PIN unlock`() = test {
        val f = fixture(pin = null)
        assertEquals(ParentSessionState.SetupRequired, f.session.state.value)
        assertEquals(PinUnlockResult.SetupRequired, f.session.unlockWithPin(PIN.toCharArray()))
        assertEquals(BiometricUnlockRequest.SetupRequired, f.session.unlockWithBiometric())
    }

    @Test
    fun `a stored but unreadable PIN never offers setup`() = test {
        val f = SessionFixture(backgroundScope, unreadablePin = true)
        assertEquals(ParentSessionState.Locked, f.session.state.value)
        assertEquals(PinUnlockResult.Unavailable, f.session.unlockWithPin(PIN.toCharArray()))
        assertEquals(ParentSessionState.Locked, f.session.state.value)
        assertEquals(PinSetupResult.NotAllowed, f.session.setupPin(PIN.toCharArray(), PIN.toCharArray()))
    }

    @Test
    fun `the correct PIN unlocks, clears earlier failures and zeroes the PIN`() = test {
        val f = fixture()
        f.session.unlockWithPin(WRONG_PIN.toCharArray())
        assertEquals(1, f.lockoutStore.state?.consecutiveFailures)

        val pin = PIN.toCharArray()
        assertEquals(PinUnlockResult.Unlocked, f.session.unlockWithPin(pin))

        val state = assertIs<ParentSessionState.Unlocked>(f.session.state.value)
        assertEquals(UnlockMethod.PIN, state.method)
        assertEquals(f.clock.now(), state.since)
        assertEquals(0, f.lockoutStore.state?.consecutiveFailures)
        assertTrue(pin.all { it == '\u0000' })
    }

    @Test
    fun `a wrong PIN stays locked, counts the failure and returns the next decision`() = test {
        val f = fixture()
        repeat(PinLockoutPolicy.FREE_ATTEMPTS - 1) { attempt ->
            assertEquals(
                PinUnlockResult.WrongPin(LockoutDecision.Allowed),
                f.session.unlockWithPin(WRONG_PIN.toCharArray()),
            )
            assertEquals(attempt + 1, f.lockoutStore.state?.consecutiveFailures)
        }
        f.session.unlockWithPin(WRONG_PIN.toCharArray())

        // The fourth failure starts the first timed lockout.
        val result = f.session.unlockWithPin(WRONG_PIN.toCharArray())
        assertEquals(PinUnlockResult.WrongPin(LockoutDecision.Wait(PinLockoutPolicy.BASE_LOCKOUT)), result)
        assertEquals(PinLockoutPolicy.FREE_ATTEMPTS + 1, f.lockoutStore.state?.consecutiveFailures)
        assertEquals(ParentSessionState.Locked, f.session.state.value)
    }

    @Test
    fun `during a lockout even the correct PIN is not checked and the wait is surfaced`() = test {
        val f = fixture()
        repeat(PinLockoutPolicy.FREE_ATTEMPTS + 1) { f.session.unlockWithPin(WRONG_PIN.toCharArray()) }
        f.fakeBootClock.advanceBy(10.seconds)

        val result = f.session.unlockWithPin(PIN.toCharArray())

        assertEquals(PinUnlockResult.LockedOut(LockoutDecision.Wait(20.seconds)), result)
        assertEquals(ParentSessionState.Locked, f.session.state.value)
        assertEquals(PinLockoutPolicy.FREE_ATTEMPTS + 1, f.lockoutStore.state?.consecutiveFailures)

        f.fakeBootClock.advanceBy(20.seconds)
        assertEquals(LockoutDecision.Allowed, f.session.lockoutDecision())
        assertEquals(PinUnlockResult.Unlocked, f.session.unlockWithPin(PIN.toCharArray()))
    }

    @Test
    fun `after the cap the PIN path closes until a biometric unlock`() = test {
        val f = fixture()
        f.enrolKeyDirectly()
        repeat(PinLockoutPolicy.CAP_FAILURES) {
            f.fakeBootClock.advanceBy(PinLockoutPolicy.MAX_LOCKOUT)
            f.session.unlockWithPin(WRONG_PIN.toCharArray())
        }
        f.fakeBootClock.advanceBy(PinLockoutPolicy.MAX_LOCKOUT * 2)
        assertEquals(LockoutDecision.RequireBiometric, f.session.lockoutDecision())
        assertEquals(
            PinUnlockResult.LockedOut(LockoutDecision.RequireBiometric),
            f.session.unlockWithPin(PIN.toCharArray()),
        )

        val request = assertIs<BiometricUnlockRequest.Ready>(f.session.unlockWithBiometric())
        assertEquals(BiometricUnlockResult.UNLOCKED, f.session.completeBiometricUnlock(request, request.signature))

        assertEquals(UnlockMethod.BIOMETRIC, assertIs<ParentSessionState.Unlocked>(f.session.state.value).method)
        assertEquals(LockoutDecision.Allowed, f.session.lockoutDecision())
    }

    @Test
    fun `after the cap without a usable parent key the PIN path stays open after a one hour wait`() = test {
        val f = fixture()
        f.enrolKeyDirectly()
        f.authorizer.invalidateKey() // a fingerprint was added: biometric unlock cannot succeed any more
        repeat(PinLockoutPolicy.CAP_FAILURES) {
            f.fakeBootClock.advanceBy(PinLockoutPolicy.MAX_LOCKOUT)
            f.session.unlockWithPin(WRONG_PIN.toCharArray())
        }
        assertEquals(LockoutDecision.Wait(PinLockoutPolicy.MAX_LOCKOUT), f.session.lockoutDecision())
        f.fakeBootClock.advanceBy(PinLockoutPolicy.MAX_LOCKOUT)
        assertEquals(PinUnlockResult.Unlocked, f.session.unlockWithPin(PIN.toCharArray()))
    }

    @Test
    fun `a retired v1 hash is never verified and reads as unavailable`() = test {
        val f = SessionFixture(backgroundScope, legacyPin = PIN)
        val legacy = f.pinStore.hash!!
        assertTrue(legacy.startsWith("v1\$"))

        assertEquals(PinUnlockResult.Unavailable, f.session.unlockWithPin(PIN.toCharArray()))

        assertEquals(ParentSessionState.Locked, f.session.state.value)
        assertEquals(legacy, f.pinStore.hash)
        assertEquals(0, f.pinStore.saves)
        // Not counted as a wrong guess either.
        assertEquals(null, f.lockoutStore.state?.consecutiveFailures?.takeIf { it > 0 })
    }

    @Test
    fun `a hash with weaker parameters is upgraded after a successful unlock`() = test {
        val f = SessionFixture(backgroundScope, weakPin = PIN)
        val weak = f.pinStore.hash!!
        assertTrue(f.hasher.needsRehash(weak))

        assertEquals(PinUnlockResult.Unlocked, f.session.unlockWithPin(PIN.toCharArray()))

        val upgraded = f.pinStore.hash!!
        assertNotEquals(weak, upgraded)
        assertFalse(f.hasher.needsRehash(upgraded))
        assertTrue(f.hasher.verify(PIN.toCharArray(), upgraded))
        assertEquals(1, f.pinStore.saves)
    }

    @Test
    fun `a failed rehash still unlocks with the old hash`() = test {
        val f = SessionFixture(backgroundScope, weakPin = PIN)
        val weak = f.pinStore.hash
        f.pinStore.failWrites = true

        assertEquals(PinUnlockResult.Unlocked, f.session.unlockWithPin(PIN.toCharArray()))
        assertEquals(weak, f.pinStore.hash)
    }

    @Test
    fun `setup checks the rules and the confirmation, then saves and unlocks`() = test {
        val f = fixture(pin = null)
        assertEquals(
            PinSetupResult.Invalid(PinRuleViolation.SEQUENCE),
            f.session.setupPin("123456".toCharArray(), "123456".toCharArray()),
        )
        assertEquals(PinSetupResult.Mismatch, f.session.setupPin(PIN.toCharArray(), WRONG_PIN.toCharArray()))
        assertEquals(ParentSessionState.SetupRequired, f.session.state.value)

        val pin = PIN.toCharArray()
        val confirmation = PIN.toCharArray()
        assertEquals(PinSetupResult.Saved, f.session.setupPin(pin, confirmation))

        assertEquals(UnlockMethod.PIN_SETUP, assertIs<ParentSessionState.Unlocked>(f.session.state.value).method)
        assertTrue(pin.all { it == '\u0000' } && confirmation.all { it == '\u0000' })
        assertTrue(f.hasher.verify(PIN.toCharArray(), f.pinStore.hash!!))
        // A second setup is refused: changing the PIN is a separate flow.
        assertEquals(PinSetupResult.NotAllowed, f.session.setupPin("777123".toCharArray(), "777123".toCharArray()))
    }

    @Test
    fun `biometric unlock is unavailable without a strong biometric`() = test {
        val f = fixture()
        f.authorizer.availability = ParentAuthAvailability.NOT_ENROLLED
        assertEquals(
            BiometricUnlockRequest.Unavailable(ParentAuthAvailability.NOT_ENROLLED),
            f.session.unlockWithBiometric(),
        )
    }

    @Test
    fun `without a registered parent key biometric unlock is not offered and no key is created`() = test {
        val f = fixture()

        assertEquals(BiometricUnlockRequest.NotEnrolled, f.session.unlockWithBiometric())

        assertNull(f.authorizer.existingParentPublicKey())
        assertEquals(0, f.authorizer.keysCreated)
        assertEquals(0, f.authorizer.signaturesIssued)
        assertEquals(ParentKeyStatus.NOT_REGISTERED, f.session.parentKeyStatus())
    }

    @Test
    fun `a local key that is not the registered one fails closed`() = test {
        val f = fixture()
        f.enrolKeyDirectly()
        // Someone minted another key on this device (or the registration belongs to another key).
        f.registry.key = FakeParentAuthorizer().createParentKey()

        assertEquals(BiometricUnlockRequest.KeyInvalidated, f.session.unlockWithBiometric())
        assertEquals(0, f.authorizer.signaturesIssued)
        assertEquals(ParentKeyStatus.NEEDS_RENEWAL, f.session.parentKeyStatus())
        assertEquals(ParentSessionState.Locked, f.session.state.value)
    }

    @Test
    fun `a registered key without a local key fails closed and creates nothing`() = test {
        val f = fixture()
        f.registry.key = FakeParentAuthorizer().createParentKey()

        assertEquals(BiometricUnlockRequest.KeyInvalidated, f.session.unlockWithBiometric())
        assertEquals(0, f.authorizer.keysCreated)
    }

    @Test
    fun `an unreadable registry falls back to the PIN`() = test {
        val f = fixture()
        f.enrolKeyDirectly()
        f.registry.unreadable = true

        assertEquals(BiometricUnlockRequest.Failed, f.session.unlockWithBiometric())
        assertNull(f.session.parentKeyStatus())
        assertEquals(PinUnlockResult.Unlocked, f.session.unlockWithPin(PIN.toCharArray()))
    }

    @Test
    fun `the unlock challenge is verified against the registered key`() = test {
        val f = fixture()
        val registered = f.enrolKeyDirectly()

        val request = assertIs<BiometricUnlockRequest.Ready>(f.session.unlockWithBiometric())
        assertEquals(registered, request.publicKey)
        assertEquals(BiometricUnlockResult.UNLOCKED, f.session.completeBiometricUnlock(request, request.signature))
    }

    @Test
    fun `biometric unlock rejects a missing, foreign or reused signature`() = test {
        val f = fixture()
        f.enrolKeyDirectly()
        val first = assertIs<BiometricUnlockRequest.Ready>(f.session.unlockWithBiometric())
        assertEquals(BiometricUnlockResult.REJECTED, f.session.completeBiometricUnlock(first, null))

        val second = assertIs<BiometricUnlockRequest.Ready>(f.session.unlockWithBiometric())
        assertEquals(BiometricUnlockResult.REJECTED, f.session.completeBiometricUnlock(second, first.signature))

        val third = assertIs<BiometricUnlockRequest.Ready>(f.session.unlockWithBiometric())
        assertEquals(BiometricUnlockResult.UNLOCKED, f.session.completeBiometricUnlock(third, third.signature))
        f.session.lock()
        assertEquals(BiometricUnlockResult.REJECTED, f.session.completeBiometricUnlock(third, third.signature))
        assertEquals(ParentSessionState.Locked, f.session.state.value)
    }

    @Test
    fun `an invalidated parent key is reported and blocks biometric unlock until renewal`() = test {
        val f = fixture()
        f.enrolKeyDirectly()
        assertIs<BiometricUnlockRequest.Ready>(f.session.unlockWithBiometric())
        f.authorizer.invalidateKey()

        assertEquals(BiometricUnlockRequest.KeyInvalidated, f.session.unlockWithBiometric())
        assertEquals(ParentKeyStatus.NEEDS_RENEWAL, f.session.parentKeyStatus())

        f.session.unlockWithPin(PIN.toCharArray())
        assertEquals(ParentKeyChangeResult.Registered, f.renewal.renew(PIN.toCharArray()))
        f.session.lock()
        val request = assertIs<BiometricUnlockRequest.Ready>(f.session.unlockWithBiometric())
        assertEquals(BiometricUnlockResult.UNLOCKED, f.session.completeBiometricUnlock(request, request.signature))
    }

    @Test
    fun `leaving the app locks at once when lock-when-leaving is on`() = test {
        val f = fixture()
        f.session.unlockWithPin(PIN.toCharArray())

        f.foreground.foreground.value = false

        assertEquals(ParentSessionState.Locked, f.session.state.value)
    }

    @Test
    fun `with lock-when-leaving off the session locks after two minutes in the background`() = test {
        val f = fixture(lockWhenLeaving = false)
        f.session.unlockWithPin(PIN.toCharArray())

        f.foreground.foreground.value = false
        advanceTimeBy(ParentSession.AUTO_LOCK_AFTER - 1.seconds)
        runCurrent()
        assertIs<ParentSessionState.Unlocked>(f.session.state.value)

        advanceTimeBy(2.seconds)
        runCurrent()
        assertEquals(ParentSessionState.Locked, f.session.state.value)
    }

    @Test
    fun `coming back within two minutes keeps the session`() = test {
        val f = fixture(lockWhenLeaving = false)
        f.session.unlockWithPin(PIN.toCharArray())

        f.foreground.foreground.value = false
        f.fakeBootClock.advanceBy(1.minutes)
        advanceTimeBy(1.minutes)
        f.foreground.foreground.value = true
        advanceTimeBy(5.minutes)
        runCurrent()

        assertIs<ParentSessionState.Unlocked>(f.session.state.value)
    }

    @Test
    fun `coming back after two minutes of elapsed realtime locks even if the timer never ran`() = test {
        val f = fixture(lockWhenLeaving = false)
        f.session.unlockWithPin(PIN.toCharArray())

        f.foreground.foreground.value = false
        // Doze can hold the coroutine timer back; the boot clock still moves.
        f.fakeBootClock.advanceBy(ParentSession.AUTO_LOCK_AFTER)
        f.foreground.foreground.value = true

        assertEquals(ParentSessionState.Locked, f.session.state.value)
    }

    @Test
    fun `moving the wall clock does not affect auto-lock`() = test {
        val f = fixture(lockWhenLeaving = false)
        f.session.unlockWithPin(PIN.toCharArray())

        f.foreground.foreground.value = false
        f.clock.advanceBy(10.minutes)
        f.fakeBootClock.advanceBy(30.seconds)
        f.foreground.foreground.value = true

        assertIs<ParentSessionState.Unlocked>(f.session.state.value)
    }
}
