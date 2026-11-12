package dev.kpetriv.sprout.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kpetriv.sprout.core.crypto.LockoutDecision
import dev.kpetriv.sprout.core.crypto.ParentAuthAvailability
import dev.kpetriv.sprout.core.crypto.ParentAuthorizer
import dev.kpetriv.sprout.core.database.HouseholdPolicy
import dev.kpetriv.sprout.core.database.HouseholdPolicyRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.time.Duration

/**
 * The parent security section. PIN digits never enter this state: they are typed on a `PinPad` and kept in the
 * ViewModel's [PinBuffer]s ([CharArray]s, zeroed after use); the state only says how many were typed.
 *
 * @property policy the household policy, or null before one exists (treated as "no parent key").
 * @property keyNeedsRenewal a key is registered but this device's key can no longer sign for it.
 * @property keyNotRegisteredWithHousehold the key is registered on this device but the household's server never
 *   recorded it (D-M3-39), so other devices refuse its signatures; "Retry" renews the key.
 * @property signingPin the PIN entry for turning signing on or renewing the key, or null when closed.
 * @property changePin the change-PIN form.
 * @property restoreNeeded this ACTIVE parent phone of a real household has no household policy: on a phone without a
 *   strong biometric, "Restore household settings" recovers it with the PIN (D-REL-22).
 * @property isRestoring the restore is checking, waiting for the confirmation or writing.
 */
data class ParentSecurityUiState(
    val availability: ParentAuthAvailability = ParentAuthAvailability.UNAVAILABLE_OTHER,
    val policy: SignaturePolicyUi? = null,
    val keyNeedsRenewal: Boolean = false,
    val keyNotRegisteredWithHousehold: Boolean = false,
    val lockWhenLeaving: Boolean = true,
    val changePin: ChangePinUi = ChangePinUi(),
    val isChangingPin: Boolean = false,
    val pinError: PinChangeError? = null,
    val signingPin: SigningPinUi? = null,
    val isRegistering: Boolean = false,
    val registerError: RegisterError? = null,
    val message: SettingsMessage? = null,
    val restoreNeeded: Boolean = false,
    val isRestoring: Boolean = false,
) {
    /** What the fingerprint signing card shows. */
    val signing: SigningStatus
        get() = when {
            availability != ParentAuthAvailability.STRONG_BIOMETRIC_AVAILABLE -> SigningStatus.UNAVAILABLE
            policy?.hasParentKey == true && keyNeedsRenewal -> SigningStatus.KEY_INVALIDATED
            policy?.hasParentKey == true && keyNotRegisteredWithHousehold -> SigningStatus.NOT_REGISTERED_WITH_HOUSEHOLD
            policy?.hasParentKey == true && !policy.requireParentSignature -> SigningStatus.SIGNATURES_NOT_REQUIRED
            policy?.hasParentKey == true -> SigningStatus.ON
            else -> SigningStatus.NOT_SET_UP
        }

    /** "Restore household settings" is offered: no strong biometric to recover the policy with (D-REL-22). */
    val offerRestore: Boolean get() = restoreNeeded && signing == SigningStatus.UNAVAILABLE
}

/** The household policy as settings shows it (D-M2-1). */
data class SignaturePolicyUi(val hasParentKey: Boolean, val requireParentSignature: Boolean)

enum class SigningStatus {
    /** The parent key is registered and usable: every parent money change is signed. */
    ON,

    /** A strong biometric is usable but no key is registered yet: "Turn on" asks for the PIN, then enrols. */
    NOT_SET_UP,

    /** No usable strong biometric: money changes from this device are not signed (D-M2-1 warning). */
    UNAVAILABLE,

    /** Registered, but a fingerprint change invalidated this device's key: "Renew" asks for the PIN, then renews. */
    KEY_INVALIDATED,

    /**
     * Registered and usable on this device, but the household's server has no record of it (the registration after
     * enrolment failed, D-M3-39): other devices refuse its signatures. "Retry" asks for the PIN and renews the key
     * (new challenge, new key, fingerprint, server registration), since the old key's attestation cannot be reused.
     */
    NOT_REGISTERED_WITH_HOUSEHOLD,

    /**
     * This device's key is registered, but the household policy accepts unsigned parent changes (a policy written
     * without signatures before they became one-way, D-REL-23): "Renew" asks for the PIN and renews the key, which
     * turns required signatures on again.
     */
    SIGNATURES_NOT_REQUIRED,
}

/** Which signing-key action the PIN entry is for. */
enum class SigningPinPurpose {
    ENROL,
    RENEW,
}

/** The PIN entry for a signing-key action: how many digits are typed, and a shake counter for a wrong PIN. */
data class SigningPinUi(val purpose: SigningPinPurpose, val enteredDigits: Int = 0, val rejections: Int = 0)

/** The change-PIN form's step. */
enum class ChangePinStep {
    CURRENT,
    NEW,
    CONFIRM,
}

/** The change-PIN form: the step, how many digits of it are typed, and a shake counter for rejections. */
data class ChangePinUi(
    val step: ChangePinStep = ChangePinStep.CURRENT,
    val enteredDigits: Int = 0,
    val rejections: Int = 0,
)

/** Why a PIN change failed, as the form shows it. */
sealed interface PinChangeError {
    /** Wrong current PIN; [wait] is set when the next attempt must wait. */
    data class WrongCurrent(val wait: Duration?) : PinChangeError

    data class LockedOut(val wait: Duration) : PinChangeError

    /** Too many wrong PINs: unlock with the fingerprint before trying again. */
    data object RequireBiometric : PinChangeError

    data class Invalid(val problem: NewPinProblem) : PinChangeError

    data object Mismatch : PinChangeError

    data object NotAllowed : PinChangeError

    data object Failed : PinChangeError
}

/** Why turning signing on or renewing the key failed. */
sealed interface RegisterError {
    /** The device has no usable strong biometric after all. */
    data object Unavailable : RegisterError

    /** Wrong PIN; [wait] is set when the next attempt must wait. */
    data class WrongPin(val wait: Duration?) : RegisterError

    data class LockedOut(val wait: Duration) : RegisterError

    /** Too many wrong PINs: unlock with the fingerprint first. */
    data object RequireBiometric : RegisterError

    /** The fingerprint prompt was dismissed; nothing changed. */
    data object Cancelled : RegisterError

    /** This phone has not synced the household policy yet (D-REL-15); no prompt was shown, nothing changed. */
    data object PolicyNotSynced : RegisterError

    data object Failed : RegisterError
}

sealed interface ParentSecurityEvent {
    /** Re-read the biometric availability and the key health (the screen sends this on resume). */
    data object Refresh : ParentSecurityEvent

    /** "Turn on fingerprint signing" or "Renew signing key": opens the PIN entry for it. */
    data object StartSigningKeyChange : ParentSecurityEvent

    data object CancelSigningKeyChange : ParentSecurityEvent

    data class SigningPinDigit(val digit: Char) : ParentSecurityEvent

    data object SigningPinBackspace : ParentSecurityEvent

    data class ChangePinDigit(val digit: Char) : ParentSecurityEvent

    data object ChangePinBackspace : ParentSecurityEvent

    /** Back to the current-PIN step, forgetting everything typed. */
    data object ChangePinStartOver : ParentSecurityEvent

    data class LockWhenLeavingChanged(val enabled: Boolean) : ParentSecurityEvent

    data object MessageShown : ParentSecurityEvent

    /** "Restore household settings" (D-REL-22): check, confirm with the PIN, then write the policy. */
    data object RestoreHouseholdSettings : ParentSecurityEvent
}

/**
 * PIN change ([PinChanger]) on a three-step `PinPad` form, fingerprint signing status ([ParentAuthorizer.availability],
 * the household policy from [HouseholdPolicyRepository.observe], the server registration from
 * [HouseholdPolicyRepository.observeServerRegistrationPending] and [ParentKeyRegistrar.needsRenewal]) with PIN-gated
 * enrolment and renewal ([ParentKeyRegistrar]), the "lock when leaving the app" preference ([LockPreferences]), and
 * "Restore household settings" ([HouseholdSettingsRestorer] behind [ParentConfirmation], D-REL-22).
 */
@HiltViewModel
@Suppress("LongParameterList") // One port per part of the screen.
class ParentSecurityViewModel @Inject constructor(
    private val pinChanger: PinChanger,
    private val registrar: ParentKeyRegistrar,
    private val lockPreferences: LockPreferences,
    private val authorizer: ParentAuthorizer,
    private val policies: HouseholdPolicyRepository,
    private val restorer: HouseholdSettingsRestorer,
    private val confirmation: ParentConfirmation,
) : ViewModel() {
    private val local = MutableStateFlow(ParentSecurityUiState(availability = authorizer.availability()))

    private val currentPin = PinBuffer()
    private val newPin = PinBuffer()
    private val confirmPin = PinBuffer()
    private val signingPin = PinBuffer()

    private val policy = policies.observe()
        .map<HouseholdPolicy?, Load<SignaturePolicyUi?>> { policy ->
            Load.Ready(policy?.let { SignaturePolicyUi(it.hasParentKey, it.requireParentSignature) })
        }
        .onStart { emit(Load.Loading) }
        .catch { emit(Load.Failed) }

    private val serverPending = policies.observeServerRegistrationPending().catch { emit(false) }

    private val restoreNeeded = restorer.observeNeeded().catch { emit(false) }

    val state: StateFlow<ParentSecurityUiState> = combine(
        local,
        policy,
        serverPending,
        lockPreferences.lockWhenLeaving,
        restoreNeeded,
    ) { localState, loaded, pending, lock, needed ->
        localState.copy(
            policy = (loaded as? Load.Ready)?.value,
            keyNotRegisteredWithHousehold = pending,
            lockWhenLeaving = lock,
            restoreNeeded = needed,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), local.value)

    init {
        refresh()
    }

    fun onEvent(event: ParentSecurityEvent) {
        when (event) {
            ParentSecurityEvent.Refresh -> refresh()

            ParentSecurityEvent.StartSigningKeyChange -> startSigningKeyChange()

            ParentSecurityEvent.CancelSigningKeyChange -> closeSigningPin(error = null)

            is ParentSecurityEvent.SigningPinDigit -> signingDigit(event.digit)

            ParentSecurityEvent.SigningPinBackspace -> if (!local.value.isRegistering && signingPin.removeLast()) {
                local.update { it.copy(signingPin = it.signingPin?.copy(enteredDigits = signingPin.count)) }
            }

            is ParentSecurityEvent.ChangePinDigit -> changePinDigit(event.digit)

            ParentSecurityEvent.ChangePinBackspace -> changePinBackspace()

            ParentSecurityEvent.ChangePinStartOver -> resetChangePin(error = null, rejected = false)

            is ParentSecurityEvent.LockWhenLeavingChanged -> lockPreferences.setLockWhenLeaving(event.enabled)

            ParentSecurityEvent.MessageShown -> local.update { it.copy(message = null) }

            ParentSecurityEvent.RestoreHouseholdSettings -> restoreHouseholdSettings()
        }
    }

    private fun refresh() {
        local.update { it.copy(availability = authorizer.availability()) }
        viewModelScope.launch {
            val needsRenewal = try {
                registrar.needsRenewal()
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
                false
            }
            local.update { it.copy(keyNeedsRenewal = needsRenewal) }
        }
    }

    // region Signing key

    private fun startSigningKeyChange() {
        val current = state.value
        val purpose = when (current.signing) {
            SigningStatus.NOT_SET_UP -> SigningPinPurpose.ENROL

            SigningStatus.KEY_INVALIDATED, SigningStatus.NOT_REGISTERED_WITH_HOUSEHOLD,
            SigningStatus.SIGNATURES_NOT_REQUIRED,
            -> SigningPinPurpose.RENEW

            SigningStatus.ON, SigningStatus.UNAVAILABLE -> return
        }
        if (current.isRegistering) return
        signingPin.clear()
        local.update { it.copy(signingPin = SigningPinUi(purpose), registerError = null) }
    }

    private fun signingDigit(digit: Char) {
        val entry = local.value.signingPin ?: return
        if (local.value.isRegistering || !signingPin.add(digit)) return
        local.update { it.copy(signingPin = entry.copy(enteredDigits = signingPin.count), registerError = null) }
        if (signingPin.isFull) submitSigningPin(entry.purpose)
    }

    private fun submitSigningPin(purpose: SigningPinPurpose) {
        val pin = signingPin.take()
        local.update { it.copy(isRegistering = true, signingPin = it.signingPin?.copy(enteredDigits = 0)) }
        viewModelScope.launch {
            val outcome = try {
                when (purpose) {
                    SigningPinPurpose.ENROL -> registrar.enrol(pin)
                    SigningPinPurpose.RENEW -> registrar.renew(pin)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
                SigningKeyOutcome.Failed
            } finally {
                pin.fill(ZERO_CHAR)
            }
            local.update { it.copy(isRegistering = false, availability = authorizer.availability()) }
            when (outcome) {
                SigningKeyOutcome.Registered -> {
                    closeSigningPin(error = null)
                    // The key is registered here; whether the household's server took it too decides the message.
                    val shared = !serverRegistrationPending()
                    val message = if (shared) SettingsMessage.KEY_REGISTERED else SettingsMessage.KEY_NOT_SHARED
                    local.update { it.copy(keyNeedsRenewal = false, message = message) }
                }

                // A wrong PIN keeps the entry open (and shakes it) so the parent can retry.
                is SigningKeyOutcome.WrongPin -> local.update {
                    it.copy(
                        registerError = outcome.next.toRegisterError(wrongPin = true),
                        signingPin = it.signingPin?.let { entry -> entry.copy(rejections = entry.rejections + 1) },
                    )
                }

                else -> closeSigningPin(error = outcome.toRegisterError())
            }
            refresh()
        }
    }

    private suspend fun serverRegistrationPending(): Boolean = try {
        policies.observeServerRegistrationPending().first()
    } catch (e: CancellationException) {
        throw e
    } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
        false
    }

    private fun closeSigningPin(error: RegisterError?) {
        signingPin.clear()
        local.update { it.copy(signingPin = null, registerError = error) }
    }

    // endregion

    // region Restore household settings

    /**
     * D-REL-22: the check first (it may sync, bounded), so a restore bound to be refused never asks for the PIN; then
     * [ParentConfirmation] (the PIN on a phone without a strong biometric), then the write. Nothing is written unless
     * the check found the policy restorable and the parent confirmed.
     */
    private fun restoreHouseholdSettings() {
        if (local.value.isRestoring || !state.value.offerRestore) return
        local.update { it.copy(isRestoring = true) }
        viewModelScope.launch {
            val message = try {
                when (restorer.check()) {
                    RestoreCheck.PRESENT -> null
                    RestoreCheck.NOT_SYNCED -> SettingsMessage.POLICY_NOT_SYNCED
                    RestoreCheck.READY -> confirmAndRestore()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
                SettingsMessage.SAVE_FAILED
            } finally {
                local.update { it.copy(isRestoring = false) }
            }
            if (message != null) local.update { it.copy(message = message) }
        }
    }

    private suspend fun confirmAndRestore(): SettingsMessage? =
        when (confirmation.confirm(ConfirmationPurpose.RESTORE_HOUSEHOLD_SETTINGS)) {
            ConfirmationOutcome.CONFIRMED ->
                if (restorer.restore()) SettingsMessage.SETTINGS_RESTORED else SettingsMessage.POLICY_NOT_SYNCED

            ConfirmationOutcome.CANCELLED -> null

            ConfirmationOutcome.FAILED -> SettingsMessage.NOT_CONFIRMED
        }

    // endregion

    // region Change PIN

    private fun bufferFor(step: ChangePinStep) = when (step) {
        ChangePinStep.CURRENT -> currentPin
        ChangePinStep.NEW -> newPin
        ChangePinStep.CONFIRM -> confirmPin
    }

    private fun changePinDigit(digit: Char) {
        val form = local.value.changePin
        val buffer = bufferFor(form.step)
        if (local.value.isChangingPin || !buffer.add(digit)) return
        val next = when {
            !buffer.isFull -> form.copy(enteredDigits = buffer.count)
            form.step == ChangePinStep.CURRENT -> form.copy(step = ChangePinStep.NEW, enteredDigits = 0)
            form.step == ChangePinStep.NEW -> form.copy(step = ChangePinStep.CONFIRM, enteredDigits = 0)
            else -> null
        }
        if (next != null) {
            local.update { it.copy(changePin = next, pinError = null) }
        } else {
            submitChangePin()
        }
    }

    private fun changePinBackspace() {
        if (local.value.isChangingPin) return
        val form = local.value.changePin
        val buffer = bufferFor(form.step)
        if (buffer.removeLast()) local.update { it.copy(changePin = form.copy(enteredDigits = buffer.count)) }
    }

    private fun submitChangePin() {
        val current = currentPin.take()
        val new = newPin.take()
        val confirm = confirmPin.take()
        local.update {
            it.copy(isChangingPin = true, pinError = null, changePin = it.changePin.copy(enteredDigits = 0))
        }
        viewModelScope.launch {
            val outcome = try {
                pinChanger.changePin(current, new, confirm)
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
                PinChangeOutcome.Failed
            } finally {
                current.fill(ZERO_CHAR)
                new.fill(ZERO_CHAR)
                confirm.fill(ZERO_CHAR)
            }
            local.update { it.copy(isChangingPin = false) }
            if (outcome == PinChangeOutcome.Changed) {
                resetChangePin(error = null, rejected = false)
                local.update { it.copy(message = SettingsMessage.PIN_CHANGED) }
            } else {
                resetChangePin(error = outcome.toError(), rejected = true)
            }
        }
    }

    /** Clears every PIN buffer of the form and goes back to the current-PIN step. */
    private fun resetChangePin(error: PinChangeError?, rejected: Boolean) {
        currentPin.clear()
        newPin.clear()
        confirmPin.clear()
        local.update {
            val rejections = it.changePin.rejections + if (rejected) 1 else 0
            it.copy(changePin = ChangePinUi(rejections = rejections), pinError = error)
        }
    }

    // endregion

    override fun onCleared() {
        currentPin.clear()
        newPin.clear()
        confirmPin.clear()
        signingPin.clear()
    }

    private companion object {
        const val ZERO_CHAR = '\u0000'
    }
}

internal fun PinChangeOutcome.toError(): PinChangeError? = when (this) {
    PinChangeOutcome.Changed -> null

    is PinChangeOutcome.WrongCurrentPin -> when (val next = next) {
        is LockoutDecision.Wait -> PinChangeError.WrongCurrent(next.remaining)
        LockoutDecision.RequireBiometric -> PinChangeError.RequireBiometric
        LockoutDecision.Allowed -> PinChangeError.WrongCurrent(null)
    }

    is PinChangeOutcome.LockedOut -> when (val decision = decision) {
        is LockoutDecision.Wait -> PinChangeError.LockedOut(decision.remaining)
        LockoutDecision.RequireBiometric, LockoutDecision.Allowed -> PinChangeError.RequireBiometric
    }

    is PinChangeOutcome.Invalid -> PinChangeError.Invalid(problem)

    PinChangeOutcome.Mismatch -> PinChangeError.Mismatch

    PinChangeOutcome.NotAllowed -> PinChangeError.NotAllowed

    PinChangeOutcome.Failed -> PinChangeError.Failed
}

internal fun SigningKeyOutcome.toRegisterError(): RegisterError? = when (this) {
    SigningKeyOutcome.Registered -> null

    is SigningKeyOutcome.Unavailable -> RegisterError.Unavailable

    is SigningKeyOutcome.WrongPin -> next.toRegisterError(wrongPin = true)

    is SigningKeyOutcome.LockedOut -> decision.toRegisterError(wrongPin = false)

    SigningKeyOutcome.Cancelled -> RegisterError.Cancelled

    SigningKeyOutcome.PolicyNotSynced -> RegisterError.PolicyNotSynced

    // The card's state was stale; the refresh after the call shows the right action.
    SigningKeyOutcome.AlreadyOn, SigningKeyOutcome.NotRegistered, SigningKeyOutcome.Failed -> RegisterError.Failed
}

private fun LockoutDecision.toRegisterError(wrongPin: Boolean): RegisterError = when (this) {
    is LockoutDecision.Wait -> if (wrongPin) RegisterError.WrongPin(remaining) else RegisterError.LockedOut(remaining)
    LockoutDecision.RequireBiometric -> RegisterError.RequireBiometric
    LockoutDecision.Allowed -> if (wrongPin) RegisterError.WrongPin(null) else RegisterError.Failed
}
