package dev.kpetriv.sprout.feature.lock

import dev.kpetriv.sprout.core.crypto.LockoutDecision
import dev.kpetriv.sprout.core.crypto.PinLockoutPolicy
import dev.kpetriv.sprout.feature.lock.SessionFixture.Companion.PIN
import dev.kpetriv.sprout.feature.lock.SessionFixture.Companion.WRONG_PIN
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ParentSessionChangePinTest {
    private fun test(block: suspend TestScope.() -> Unit) = runTest(UnconfinedTestDispatcher()) { block() }

    private suspend fun TestScope.unlocked(): SessionFixture {
        val f = SessionFixture(backgroundScope, pin = PIN)
        assertEquals(PinUnlockResult.Unlocked, f.session.unlockWithPin(PIN.toCharArray()))
        return f
    }

    private suspend fun SessionFixture.change(current: String, new: String, confirm: String = new) =
        session.changePin(current.toCharArray(), new.toCharArray(), confirm.toCharArray())

    @Test
    fun `the right current PIN saves the new one and keeps the session unlocked`() = test {
        val f = unlocked()
        val before = f.session.state.value

        assertEquals(ChangePinResult.Changed, f.change(PIN, NEW_PIN))

        assertEquals(before, f.session.state.value)
        assertTrue(f.hasher.verify(NEW_PIN.toCharArray(), f.pinStore.hash!!))
        assertFalse(f.hasher.verify(PIN.toCharArray(), f.pinStore.hash!!))
        f.session.lock()
        assertIs<PinUnlockResult.WrongPin>(f.session.unlockWithPin(PIN.toCharArray()))
        assertEquals(PinUnlockResult.Unlocked, f.session.unlockWithPin(NEW_PIN.toCharArray()))
    }

    @Test
    fun `all three PIN arrays are zeroed`() = test {
        val f = unlocked()
        val current = PIN.toCharArray()
        val new = NEW_PIN.toCharArray()
        val confirm = NEW_PIN.toCharArray()

        f.session.changePin(current, new, confirm)

        assertTrue(listOf(current, new, confirm).all { array -> array.all { it == '\u0000' } })
    }

    @Test
    fun `a new PIN breaking the rules or not confirmed is refused without spending an attempt`() = test {
        val f = unlocked()
        val saves = f.pinStore.saves

        assertEquals(ChangePinResult.Invalid(PinRuleViolation.WRONG_LENGTH), f.change(PIN, "12345"))
        assertEquals(ChangePinResult.Invalid(PinRuleViolation.ALL_SAME), f.change(PIN, "777777"))
        assertEquals(ChangePinResult.Invalid(PinRuleViolation.SEQUENCE), f.change(PIN, "123456"))
        assertEquals(ChangePinResult.Mismatch, f.change(WRONG_PIN, NEW_PIN, "593017"))

        assertEquals(saves, f.pinStore.saves)
        assertEquals(0, f.lockoutStore.state?.consecutiveFailures ?: 0)
    }

    @Test
    fun `a wrong current PIN counts as a failed attempt and changes nothing`() = test {
        val f = unlocked()
        val stored = f.pinStore.hash

        val result = f.change(WRONG_PIN, NEW_PIN)

        assertEquals(ChangePinResult.WrongCurrentPin(LockoutDecision.Allowed), result)
        assertEquals(1, f.lockoutStore.state?.consecutiveFailures)
        assertEquals(stored, f.pinStore.hash)
        assertIs<ParentSessionState.Unlocked>(f.session.state.value)
    }

    @Test
    fun `during a lockout the current PIN is not checked`() = test {
        val f = unlocked()
        repeat(PinLockoutPolicy.FREE_ATTEMPTS + 1) { f.change(WRONG_PIN, NEW_PIN) }
        val stored = f.pinStore.hash

        val result = f.change(PIN, NEW_PIN)

        assertEquals(ChangePinResult.LockedOut(LockoutDecision.Wait(PinLockoutPolicy.BASE_LOCKOUT)), result)
        assertEquals(stored, f.pinStore.hash)
    }

    @Test
    fun `a locked session cannot change the PIN`() = test {
        val f = SessionFixture(backgroundScope, pin = PIN)

        assertEquals(ChangePinResult.NotAllowed, f.change(PIN, NEW_PIN))
        assertTrue(f.hasher.verify(PIN.toCharArray(), f.pinStore.hash!!))
    }

    @Test
    fun `an unreadable stored PIN fails and keeps the session unlocked`() = test {
        val f = unlocked()
        f.pinStore.unreadable = true

        assertEquals(ChangePinResult.Failed, f.change(PIN, NEW_PIN))
        assertIs<ParentSessionState.Unlocked>(f.session.state.value)
    }

    private companion object {
        const val NEW_PIN = "730514"
    }
}
