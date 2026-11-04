package dev.kpetriv.sprout.settings

import dev.kpetriv.sprout.core.crypto.LockoutDecision
import dev.kpetriv.sprout.feature.lock.PinEntryError
import dev.kpetriv.sprout.feature.lock.PinEntryEvent
import dev.kpetriv.sprout.feature.lock.PinUnlockResult
import dev.kpetriv.sprout.feature.settings.ConfirmationOutcome
import dev.kpetriv.sprout.feature.settings.ConfirmationPurpose
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** The parent PIN as the fallback of the M7 parent confirmation (no strong biometric on this phone). */
class PinConfirmationHostTest {
    private val typed = mutableListOf<String>()
    private val answers = ArrayDeque<PinUnlockResult>()
    private val scope = TestScope()
    private val host = PinConfirmationHost(scope) { pin ->
        typed += String(pin)
        answers.removeFirst()
    }

    @Test
    fun `without a hosted dialog it fails at once`() = scope.runTest {
        assertEquals(ConfirmationOutcome.FAILED, host.confirm(ConfirmationPurpose.DELETE_CHAT_HISTORY))
    }

    @Test
    fun `the right PIN confirms, a wrong one may be retried, and the digits are never kept`() = scope.runTest {
        host.attach()
        val outcome = async { host.confirm(ConfirmationPurpose.EXPORT_HOUSEHOLD) }
        runCurrent()
        assertEquals(ConfirmationPurpose.EXPORT_HOUSEHOLD, host.request.value)

        answers += PinUnlockResult.WrongPin(LockoutDecision.Allowed)
        type("111111")
        runCurrent()
        assertEquals(PinEntryError.WRONG_PIN, host.state.value.error)
        assertEquals(0, host.state.value.enteredDigits)

        answers += PinUnlockResult.Unlocked
        type("1234")
        host.onEvent(PinEntryEvent.Backspace)
        type("567")
        runCurrent()

        assertEquals(ConfirmationOutcome.CONFIRMED, outcome.await())
        assertEquals(listOf("111111", "123567"), typed)
        assertNull(host.request.value)
    }

    @Test
    fun `a lockout or an unreadable PIN store fails, dismissing cancels`() = scope.runTest {
        host.attach()
        val locked = async { host.confirm(ConfirmationPurpose.CHANGE_GAME_CAP) }
        runCurrent()
        answers += PinUnlockResult.WrongPin(LockoutDecision.Wait(1.minutes))
        type("000000")
        runCurrent()
        assertEquals(ConfirmationOutcome.FAILED, locked.await())

        val unavailable = async { host.confirm(ConfirmationPurpose.CHANGE_GAME_CAP) }
        runCurrent()
        answers += PinUnlockResult.Unavailable
        type("000000")
        runCurrent()
        assertEquals(ConfirmationOutcome.FAILED, unavailable.await())

        val dismissed = async { host.confirm(ConfirmationPurpose.CHANGE_CHAT_RETENTION) }
        runCurrent()
        type("12")
        host.dismiss()
        assertEquals(ConfirmationOutcome.CANCELLED, dismissed.await())
        assertEquals(0, host.state.value.enteredDigits)
    }

    @Test
    fun `a dialog that goes away cancels the waiting confirmation`() = scope.runTest {
        host.attach()
        val waiting = async { host.confirm(ConfirmationPurpose.DELETE_CHAT_HISTORY) }
        runCurrent()
        host.detach()
        assertEquals(ConfirmationOutcome.CANCELLED, waiting.await())
        assertTrue(typed.isEmpty())
    }

    private fun type(digits: String) = digits.forEach { host.onEvent(PinEntryEvent.Digit(it)) }
}
