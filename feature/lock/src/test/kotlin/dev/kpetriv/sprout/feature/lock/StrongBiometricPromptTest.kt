package dev.kpetriv.sprout.feature.lock

import androidx.biometric.BiometricPrompt
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.security.Signature
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** The lifecycle and timeout handling of [authenticateStrong], over a fake prompt (reviewer #5). */
@OptIn(ExperimentalCoroutinesApi::class)
class StrongBiometricPromptTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val timeouts = PromptTimeouts(resume = 5.seconds, overall = 2.minutes)
    private val signature: Signature = Signature.getInstance("SHA256withECDSA")

    private val owner = object : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this)
    }
    private val lifecycle get() = owner.lifecycle

    /** A shown prompt that the test answers through [callbacks]. */
    private var callbacks: StrongPromptCallbacks? = null
    private var shows = 0
    private var cancels = 0

    private fun show(callbacks: StrongPromptCallbacks): PromptHandle {
        shows++
        this.callbacks = callbacks
        return PromptHandle { cancels++ }
    }

    private fun test(block: suspend TestScope.() -> Unit) = runTest(main.dispatcher) { block() }

    /** A detached child, so an expected failure stays in the [kotlinx.coroutines.Deferred] for the test to check. */
    private fun TestScope.prompt() = async(SupervisorJob()) { awaitStrongBiometric(lifecycle, timeouts, ::show) }

    private fun resume() {
        lifecycle.currentState = Lifecycle.State.RESUMED
    }

    @Test
    fun `a resumed host shows the prompt and returns the authenticated signature`() = test {
        resume()
        val result = prompt()
        runCurrent()

        callbacks!!.onSucceeded(signature)

        assertSame(signature, result.await())
        assertEquals(1, shows)
        assertEquals(0, cancels)
    }

    @Test
    fun `the prompt waits for the host to resume`() = test {
        lifecycle.currentState = Lifecycle.State.CREATED
        val result = prompt()
        advanceTimeBy(2.seconds)
        runCurrent()
        assertEquals(0, shows)

        resume()
        runCurrent()
        assertEquals(1, shows)
        callbacks!!.onSucceeded(signature)
        assertSame(signature, result.await())
    }

    @Test
    fun `a host that does not resume in time fails fast as cancelled, without a prompt`() = test {
        lifecycle.currentState = Lifecycle.State.CREATED
        val result = prompt()
        advanceTimeBy(timeouts.resume + 1.seconds)
        runCurrent()

        val error = assertFailsWith<BiometricCancelledException> { result.await() }
        assertEquals(BiometricCancelledException.NOT_RESUMED, error.errorCode)
        assertEquals(0, shows)
    }

    @Test
    fun `a destroyed host is cancelled at once`() = test {
        lifecycle.currentState = Lifecycle.State.CREATED
        lifecycle.currentState = Lifecycle.State.DESTROYED

        val error = assertFailsWith<BiometricCancelledException> { awaitStrongBiometric(lifecycle, timeouts, ::show) }
        assertEquals(BiometricCancelledException.HOST_GONE, error.errorCode)
        assertEquals(0, shows)
    }

    @Test
    fun `a host destroyed while waiting to resume is cancelled`() = test {
        lifecycle.currentState = Lifecycle.State.CREATED
        val result = prompt()
        runCurrent()

        lifecycle.currentState = Lifecycle.State.DESTROYED
        runCurrent()

        val error = assertFailsWith<BiometricCancelledException> { result.await() }
        assertEquals(BiometricCancelledException.HOST_GONE, error.errorCode)
    }

    @Test
    fun `a rotation while the prompt is up cancels it instead of hanging`() = test {
        resume()
        val result = prompt()
        runCurrent()
        assertEquals(1, shows)

        lifecycle.currentState = Lifecycle.State.DESTROYED
        runCurrent()

        val error = assertFailsWith<BiometricCancelledException> { result.await() }
        assertEquals(BiometricCancelledException.HOST_GONE, error.errorCode)
        assertEquals(1, cancels)
        // A late answer from the dead prompt is ignored.
        callbacks!!.onSucceeded(signature)
    }

    @Test
    fun `an unanswered prompt times out, is dismissed, and reports a cancel`() = test {
        resume()
        val result = prompt()
        runCurrent()

        advanceTimeBy(timeouts.overall + 1.seconds)
        runCurrent()

        val error = assertFailsWith<BiometricCancelledException> { result.await() }
        assertEquals(BiometricCancelledException.TIMED_OUT, error.errorCode)
        assertEquals(1, cancels)
        assertFalse(lifecycle.observerCount > 0)
    }

    @Test
    fun `cancel codes and other errors map to their exceptions`() = test {
        resume()
        val cancelled = prompt()
        runCurrent()
        callbacks!!.onError(BiometricPrompt.ERROR_NEGATIVE_BUTTON)
        assertEquals(
            BiometricPrompt.ERROR_NEGATIVE_BUTTON,
            assertFailsWith<BiometricCancelledException> { cancelled.await() }.errorCode,
        )

        val failed = prompt()
        runCurrent()
        callbacks!!.onError(BiometricPrompt.ERROR_LOCKOUT)
        assertEquals(
            BiometricPrompt.ERROR_LOCKOUT,
            assertFailsWith<BiometricErrorException> {
                failed.await()
            }.errorCode,
        )

        val noCrypto = prompt()
        runCurrent()
        callbacks!!.onSucceeded(null)
        assertFailsWith<BiometricErrorException> { noCrypto.await() }

        // Answered prompts are never cancelled, and no observer is left behind.
        assertEquals(0, cancels)
        assertEquals(0, lifecycle.observerCount)
    }

    @Test
    fun `cancelling the caller dismisses the prompt`() = test {
        resume()
        val result = prompt()
        runCurrent()

        result.cancel()
        runCurrent()

        assertEquals(1, cancels)
        assertTrue(result.isCancelled)
        assertNull(result.getCompletionExceptionOrNull()?.takeIf { it !is kotlinx.coroutines.CancellationException })
    }
}
