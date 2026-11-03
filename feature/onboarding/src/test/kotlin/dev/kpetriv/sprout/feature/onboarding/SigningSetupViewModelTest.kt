package dev.kpetriv.sprout.feature.onboarding

import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SigningSetupViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private class FakeSigning(var needed: Boolean = true) : ParentSigningSetup {
        val results = ArrayDeque<SigningSetupResult>()
        val pins = mutableListOf<String>()
        var failure: Exception? = null

        override suspend fun needsEnrolment(): Boolean = needed

        override suspend fun enrol(pin: CharArray): SigningSetupResult {
            pins += pin.concatToString()
            failure?.let { throw it }
            pin.fill('\u0000')
            return results.removeFirstOrNull() ?: SigningSetupResult.REGISTERED
        }
    }

    private fun SigningSetupViewModel.type(pin: String) = pin.forEach { onEvent(SigningSetupEvent.Digit(it)) }

    @Test
    fun `nothing to do finishes at once`() {
        val viewModel = SigningSetupViewModel(FakeSigning(needed = false))
        assertTrue(viewModel.state.value.done)
    }

    @Test
    fun `six digits enrol, a wrong PIN shakes and lets the parent retry, then it is done`() {
        val signing = FakeSigning()
        signing.results += SigningSetupResult.WRONG_PIN
        val viewModel = SigningSetupViewModel(signing)
        assertFalse(viewModel.state.value.done)
        viewModel.type("12345")
        assertEquals(5, viewModel.state.value.enteredDigits)
        assertEquals(emptyList(), signing.pins)
        viewModel.type("6")
        assertEquals(SigningSetupResult.WRONG_PIN, viewModel.state.value.error)
        assertEquals(1, viewModel.state.value.rejections)
        assertEquals(0, viewModel.state.value.enteredDigits)
        assertFalse(viewModel.state.value.done)

        viewModel.type("246813")
        assertEquals(listOf("123456", "246813"), signing.pins)
        assertTrue(viewModel.state.value.done)
        assertNull(viewModel.state.value.error)
    }

    @Test
    fun `no strong biometric finishes too (D-M2-1)`() {
        val signing = FakeSigning()
        signing.results += SigningSetupResult.UNAVAILABLE
        val viewModel = SigningSetupViewModel(signing)
        viewModel.type("246813")
        assertTrue(viewModel.state.value.done)
    }

    @Test
    fun `backspace removes a digit and later skips`() {
        val signing = FakeSigning()
        val viewModel = SigningSetupViewModel(signing)
        viewModel.type("12")
        viewModel.onEvent(SigningSetupEvent.Backspace)
        assertEquals(1, viewModel.state.value.enteredDigits)
        viewModel.onEvent(SigningSetupEvent.Skip)
        assertTrue(viewModel.state.value.done)
        assertEquals(emptyList(), signing.pins)
    }

    @Test
    fun `a cancelled fingerprint or a failure shows the error without counting a rejected PIN`() {
        val signing = FakeSigning()
        signing.results += SigningSetupResult.CANCELLED
        val viewModel = SigningSetupViewModel(signing)
        viewModel.type("246813")
        assertEquals(SigningSetupResult.CANCELLED, viewModel.state.value.error)
        assertEquals(0, viewModel.state.value.rejections)
        assertFalse(viewModel.state.value.busy)
        assertFalse(viewModel.state.value.done)
    }

    @Test
    fun `an exception from enrolment reads as failed and the step stays usable`() {
        val signing = FakeSigning()
        signing.failure = IllegalStateException("keystore")
        val viewModel = SigningSetupViewModel(signing)
        viewModel.type("246813")
        assertEquals(SigningSetupResult.FAILED, viewModel.state.value.error)
        assertEquals(0, viewModel.state.value.rejections)
        assertFalse(viewModel.state.value.busy)

        signing.failure = null
        viewModel.type("246813")
        assertTrue(viewModel.state.value.done)
    }
}
