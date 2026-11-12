package dev.kpetriv.sprout.feature.settings

import dev.kpetriv.sprout.core.crypto.FakeParentAuthorizer
import dev.kpetriv.sprout.core.crypto.LockoutDecision
import dev.kpetriv.sprout.core.crypto.ParentAuthAvailability
import dev.kpetriv.sprout.core.database.HouseholdPolicy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ParentSecurityViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val authorizer = FakeParentAuthorizer()
    private val policies = FakeHouseholdPolicyRepository()
    private val pinChanger = FakePinChanger()
    private val lockPreferences = FakeLockPreferences()
    private val registrar = FakeRegistrar { purpose ->
        if (purpose == "enrol") policies.registerParentKey(byteArrayOf(1))
        SigningKeyOutcome.Registered
    }
    private val restorer = FakeHouseholdSettingsRestorer()
    private val confirmation = FakeParentConfirmation()

    private fun TestScope.viewModel(keys: ParentKeyRegistrar = registrar): ParentSecurityViewModel {
        val vm = ParentSecurityViewModel(
            pinChanger,
            keys,
            lockPreferences,
            authorizer,
            policies,
            restorer,
            confirmation,
        )
        backgroundScope.launch(main.dispatcher) { vm.state.collect {} }
        return vm
    }

    private fun test(block: suspend TestScope.() -> Unit) = runTest(main.dispatcher) { block() }

    private fun ParentSecurityViewModel.typeChange(pin: String) =
        pin.forEach { onEvent(ParentSecurityEvent.ChangePinDigit(it)) }

    private fun ParentSecurityViewModel.typeSigning(pin: String) =
        pin.forEach { onEvent(ParentSecurityEvent.SigningPinDigit(it)) }

    private fun ParentSecurityViewModel.changePin(current: String, new: String = "730514", confirm: String = new) {
        typeChange(current)
        typeChange(new)
        typeChange(confirm)
    }

    @Test
    fun `signing status combines availability, the household policy and the key health`() = test {
        val vm = viewModel()
        assertEquals(SigningStatus.NOT_SET_UP, vm.state.value.signing)
        assertEquals(false, vm.state.value.policy?.requireParentSignature)

        policies.policy.value = FakeHouseholdPolicyRepository.policy(hasKey = true)
        assertEquals(SigningStatus.ON, vm.state.value.signing)
        assertEquals(true, vm.state.value.policy?.requireParentSignature)

        registrar.needsRenewal = true
        vm.onEvent(ParentSecurityEvent.Refresh)
        assertEquals(SigningStatus.KEY_INVALIDATED, vm.state.value.signing)

        authorizer.availability = ParentAuthAvailability.NOT_ENROLLED
        vm.onEvent(ParentSecurityEvent.Refresh)
        assertEquals(SigningStatus.UNAVAILABLE, vm.state.value.signing)
        assertEquals(ParentAuthAvailability.NOT_ENROLLED, vm.state.value.availability)
    }

    @Test
    fun `turning signing on asks for the PIN and hands it over as a zeroed array`() = test {
        val vm = viewModel()
        vm.onEvent(ParentSecurityEvent.StartSigningKeyChange)
        assertEquals(SigningPinUi(SigningPinPurpose.ENROL), vm.state.value.signingPin)

        vm.typeSigning("48291")
        assertEquals(5, vm.state.value.signingPin?.enteredDigits)
        assertTrue(registrar.calls.isEmpty())
        vm.typeSigning("3")

        assertEquals(listOf("enrol" to "482913"), registrar.calls)
        assertTrue(registrar.received!!.all { it == '\u0000' })
        assertEquals(SigningStatus.ON, vm.state.value.signing)
        assertNull(vm.state.value.signingPin)
        assertEquals(SettingsMessage.KEY_REGISTERED, vm.state.value.message)
        assertFalse(vm.state.value.isRegistering)
    }

    @Test
    fun `a changed fingerprint offers renewal with the PIN`() = test {
        policies.policy.value = FakeHouseholdPolicyRepository.policy(hasKey = true)
        registrar.needsRenewal = true
        val vm = viewModel()
        assertEquals(SigningStatus.KEY_INVALIDATED, vm.state.value.signing)

        vm.onEvent(ParentSecurityEvent.StartSigningKeyChange)
        assertEquals(SigningPinPurpose.RENEW, vm.state.value.signingPin?.purpose)
        registrar.needsRenewal = false
        vm.typeSigning("482913")

        assertEquals(listOf("renew" to "482913"), registrar.calls)
        assertEquals(SigningStatus.ON, vm.state.value.signing)
    }

    @Test
    fun `a key the household never received offers a retry that renews it (D-M3-39)`() = test {
        policies.policy.value = FakeHouseholdPolicyRepository.policy(hasKey = true)
        policies.serverPending.value = true
        val retry = FakeRegistrar { purpose ->
            if (purpose == "renew") policies.serverPending.value = false
            SigningKeyOutcome.Registered
        }
        val vm = viewModel(retry)
        assertEquals(SigningStatus.NOT_REGISTERED_WITH_HOUSEHOLD, vm.state.value.signing)
        assertTrue(vm.state.value.keyNotRegisteredWithHousehold)

        vm.onEvent(ParentSecurityEvent.StartSigningKeyChange)
        assertEquals(SigningPinPurpose.RENEW, vm.state.value.signingPin?.purpose)
        vm.typeSigning("482913")

        assertEquals(listOf("renew" to "482913"), retry.calls)
        assertEquals(SigningStatus.ON, vm.state.value.signing)
        assertEquals(SettingsMessage.KEY_REGISTERED, vm.state.value.message)
    }

    @Test
    fun `a retry the household still did not receive says so and keeps the row`() = test {
        policies.policy.value = FakeHouseholdPolicyRepository.policy(hasKey = true)
        policies.serverPending.value = true
        val vm = viewModel()

        vm.onEvent(ParentSecurityEvent.StartSigningKeyChange)
        vm.typeSigning("482913")

        assertEquals(SettingsMessage.KEY_NOT_SHARED, vm.state.value.message)
        assertEquals(SigningStatus.NOT_REGISTERED_WITH_HOUSEHOLD, vm.state.value.signing)
    }

    // region Restore household settings (D-REL-22)

    @Test
    fun `without a strong biometric a missing policy is restored after the check and the PIN`() = test {
        authorizer.availability = ParentAuthAvailability.NO_HARDWARE
        restorer.needed.value = true
        restorer.onRestore = {
            policies.policy.value = FakeHouseholdPolicyRepository.policy(hasKey = false)
            true
        }
        val vm = viewModel()
        assertTrue(vm.state.value.offerRestore)

        vm.onEvent(ParentSecurityEvent.RestoreHouseholdSettings)

        assertEquals(1, restorer.checks)
        assertEquals(listOf(ConfirmationPurpose.RESTORE_HOUSEHOLD_SETTINGS), confirmation.asked)
        assertEquals(1, restorer.restores)
        assertEquals(SettingsMessage.SETTINGS_RESTORED, vm.state.value.message)
        assertFalse(vm.state.value.isRestoring)
    }

    @Test
    fun `the restore asks for no PIN when the check says the policy is on its way`() = test {
        authorizer.availability = ParentAuthAvailability.NOT_ENROLLED
        restorer.needed.value = true
        restorer.check = RestoreCheck.NOT_SYNCED
        val vm = viewModel()

        vm.onEvent(ParentSecurityEvent.RestoreHouseholdSettings)

        assertEquals(emptyList(), confirmation.asked, "a restore bound to be refused never asks for the PIN")
        assertEquals(0, restorer.restores)
        assertEquals(SettingsMessage.POLICY_NOT_SYNCED, vm.state.value.message)

        restorer.check = RestoreCheck.PRESENT
        vm.onEvent(ParentSecurityEvent.MessageShown)
        vm.onEvent(ParentSecurityEvent.RestoreHouseholdSettings)
        assertEquals(emptyList(), confirmation.asked)
        assertNull(vm.state.value.message, "it arrived meanwhile: nothing to say")
    }

    @Test
    fun `a dismissed or failed confirmation writes nothing`() = test {
        authorizer.availability = ParentAuthAvailability.NO_HARDWARE
        restorer.needed.value = true
        confirmation.outcome = ConfirmationOutcome.CANCELLED
        val vm = viewModel()

        vm.onEvent(ParentSecurityEvent.RestoreHouseholdSettings)
        assertEquals(0, restorer.restores)
        assertNull(vm.state.value.message)

        confirmation.outcome = ConfirmationOutcome.FAILED
        vm.onEvent(ParentSecurityEvent.RestoreHouseholdSettings)
        assertEquals(0, restorer.restores)
        assertEquals(SettingsMessage.NOT_CONFIRMED, vm.state.value.message)
    }

    @Test
    fun `a restore refused at the write says the policy is on its way`() = test {
        authorizer.availability = ParentAuthAvailability.NO_HARDWARE
        restorer.needed.value = true
        restorer.onRestore = { false }
        val vm = viewModel()

        vm.onEvent(ParentSecurityEvent.RestoreHouseholdSettings)

        assertEquals(1, restorer.restores)
        assertEquals(SettingsMessage.POLICY_NOT_SYNCED, vm.state.value.message)
    }

    @Test
    fun `the restore is offered only without a strong biometric and while the policy is missing`() = test {
        restorer.needed.value = true
        val vm = viewModel()
        assertFalse(vm.state.value.offerRestore, "fingerprint signing recovers it with the key")
        vm.onEvent(ParentSecurityEvent.RestoreHouseholdSettings)
        assertEquals(0, restorer.checks)

        authorizer.availability = ParentAuthAvailability.NO_HARDWARE
        vm.onEvent(ParentSecurityEvent.Refresh)
        assertTrue(vm.state.value.offerRestore)

        restorer.needed.value = false
        assertFalse(vm.state.value.offerRestore)
    }

    // endregion

    @Test
    fun `a key in a household that accepts unsigned changes offers a renewal (D-REL-26)`() = test {
        policies.policy.value = FakeHouseholdPolicyRepository.policy(hasKey = true).let {
            HouseholdPolicy(false, it.parentSignatureFromLamport, it.parentPublicKey, it.fiatCurrency, it.createdAt)
        }
        val vm = viewModel()
        assertEquals(SigningStatus.SIGNATURES_NOT_REQUIRED, vm.state.value.signing)

        vm.onEvent(ParentSecurityEvent.StartSigningKeyChange)
        assertEquals(SigningPinPurpose.RENEW, vm.state.value.signingPin?.purpose)
    }

    @Test
    fun `a fingerprint change wins over a missing server registration`() = test {
        policies.policy.value = FakeHouseholdPolicyRepository.policy(hasKey = true)
        policies.serverPending.value = true
        registrar.needsRenewal = true
        val vm = viewModel()

        assertEquals(SigningStatus.KEY_INVALIDATED, vm.state.value.signing)
    }

    @Test
    fun `nothing to start while signing is on or unavailable`() = test {
        policies.policy.value = FakeHouseholdPolicyRepository.policy(hasKey = true)
        val vm = viewModel()
        vm.onEvent(ParentSecurityEvent.StartSigningKeyChange)
        assertNull(vm.state.value.signingPin)

        authorizer.availability = ParentAuthAvailability.NO_HARDWARE
        vm.onEvent(ParentSecurityEvent.Refresh)
        vm.onEvent(ParentSecurityEvent.StartSigningKeyChange)
        assertNull(vm.state.value.signingPin)
    }

    @Test
    fun `a wrong PIN keeps the entry open, other failures close it with an error`() = test {
        val vm = viewModel()
        vm.onEvent(ParentSecurityEvent.StartSigningKeyChange)

        registrar.result = { SigningKeyOutcome.WrongPin(WAIT_30_SECONDS) }
        vm.typeSigning("482914")
        assertEquals(RegisterError.WrongPin(30.seconds), vm.state.value.registerError)
        assertEquals(1, vm.state.value.signingPin?.rejections)
        assertEquals(0, vm.state.value.signingPin?.enteredDigits)

        registrar.result = { SigningKeyOutcome.Cancelled }
        vm.typeSigning("482913")
        assertEquals(RegisterError.Cancelled, vm.state.value.registerError)
        assertNull(vm.state.value.signingPin)

        vm.onEvent(ParentSecurityEvent.StartSigningKeyChange)
        registrar.result = { throw IOException("keystore") }
        vm.typeSigning("482913")
        assertEquals(RegisterError.Failed, vm.state.value.registerError)
        assertEquals(SigningStatus.NOT_SET_UP, vm.state.value.signing)
    }

    @Test
    fun `signing outcomes map to card errors`() {
        assertEquals(
            RegisterError.Unavailable,
            SigningKeyOutcome.Unavailable(ParentAuthAvailability.NOT_ENROLLED).toRegisterError(),
        )
        assertEquals(
            RegisterError.WrongPin(null),
            SigningKeyOutcome.WrongPin(LockoutDecision.Allowed).toRegisterError(),
        )
        assertEquals(
            RegisterError.LockedOut(30.seconds),
            SigningKeyOutcome.LockedOut(WAIT_30_SECONDS).toRegisterError(),
        )
        assertEquals(
            RegisterError.RequireBiometric,
            SigningKeyOutcome.LockedOut(LockoutDecision.RequireBiometric).toRegisterError(),
        )
        assertEquals(RegisterError.Failed, SigningKeyOutcome.AlreadyOn.toRegisterError())
        assertNull(SigningKeyOutcome.Registered.toRegisterError())
    }

    @Test
    fun `cancelling the signing entry forgets the typed digits`() = test {
        val vm = viewModel()
        vm.onEvent(ParentSecurityEvent.StartSigningKeyChange)
        vm.typeSigning("482")
        vm.onEvent(ParentSecurityEvent.CancelSigningKeyChange)
        assertNull(vm.state.value.signingPin)

        vm.onEvent(ParentSecurityEvent.StartSigningKeyChange)
        vm.typeSigning("482913")
        assertEquals(listOf("enrol" to "482913"), registrar.calls)
    }

    @Test
    fun `the change-PIN form steps through current, new and confirm on the pad`() = test {
        val vm = viewModel()
        vm.typeChange("4829")
        assertEquals(ChangePinUi(ChangePinStep.CURRENT, enteredDigits = 4), vm.state.value.changePin)
        vm.onEvent(ParentSecurityEvent.ChangePinBackspace)
        vm.typeChange("13")
        assertEquals(ChangePinUi(ChangePinStep.CURRENT, enteredDigits = 5), vm.state.value.changePin)
        vm.typeChange("3")
        assertEquals(ChangePinStep.NEW, vm.state.value.changePin.step)
        vm.typeChange("730514")
        assertEquals(ChangePinStep.CONFIRM, vm.state.value.changePin.step)
        assertTrue(pinChanger.calls.isEmpty())

        vm.onEvent(ParentSecurityEvent.ChangePinStartOver)
        assertEquals(ChangePinUi(), vm.state.value.changePin)
        assertTrue(pinChanger.calls.isEmpty())
    }

    @Test
    fun `a successful PIN change resets the form, and every array is zeroed`() = test {
        val vm = viewModel()
        vm.changePin("482913", "730514", "730514")

        assertEquals(listOf(Triple("482913", "730514", "730514")), pinChanger.calls)
        assertTrue(pinChanger.received.all { array -> array.all { it == '\u0000' } })
        assertEquals(ChangePinUi(), vm.state.value.changePin)
        assertEquals(SettingsMessage.PIN_CHANGED, vm.state.value.message)
        assertNull(vm.state.value.pinError)
    }

    @Test
    fun `lock outcomes map to form errors and restart the form`() = test {
        val vm = viewModel()
        fun errorFor(outcome: PinChangeOutcome): PinChangeError? {
            pinChanger.outcome = outcome
            vm.changePin("482914")
            assertEquals(ChangePinStep.CURRENT, vm.state.value.changePin.step)
            return vm.state.value.pinError
        }

        assertEquals(
            PinChangeError.WrongCurrent(null),
            errorFor(PinChangeOutcome.WrongCurrentPin(LockoutDecision.Allowed)),
        )
        assertEquals(
            PinChangeError.WrongCurrent(30.seconds),
            errorFor(PinChangeOutcome.WrongCurrentPin(WAIT_30_SECONDS)),
        )
        assertEquals(
            PinChangeError.RequireBiometric,
            errorFor(PinChangeOutcome.WrongCurrentPin(LockoutDecision.RequireBiometric)),
        )
        assertEquals(PinChangeError.LockedOut(30.seconds), errorFor(PinChangeOutcome.LockedOut(WAIT_30_SECONDS)))
        assertEquals(
            PinChangeError.Invalid(NewPinProblem.SEQUENCE),
            errorFor(PinChangeOutcome.Invalid(NewPinProblem.SEQUENCE)),
        )
        assertEquals(PinChangeError.Mismatch, errorFor(PinChangeOutcome.Mismatch))
        assertEquals(PinChangeError.NotAllowed, errorFor(PinChangeOutcome.NotAllowed))
        assertEquals(PinChangeError.Failed, errorFor(PinChangeOutcome.Failed))
        assertEquals(8, vm.state.value.changePin.rejections)
    }

    @Test
    fun `the lock-when-leaving toggle writes through`() = test {
        val vm = viewModel()
        assertTrue(vm.state.value.lockWhenLeaving)

        vm.onEvent(ParentSecurityEvent.LockWhenLeavingChanged(false))

        assertFalse(lockPreferences.lockWhenLeaving.value)
        assertFalse(vm.state.value.lockWhenLeaving)
    }
}
