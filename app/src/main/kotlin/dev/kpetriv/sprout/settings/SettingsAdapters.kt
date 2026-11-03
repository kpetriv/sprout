package dev.kpetriv.sprout.settings

import dev.kpetriv.sprout.core.crypto.PublicKeyBytes
import dev.kpetriv.sprout.core.database.FiatCurrencyProvider
import dev.kpetriv.sprout.core.database.HouseholdPolicyRepository
import dev.kpetriv.sprout.core.database.SessionStore
import dev.kpetriv.sprout.core.database.usecase.RegisterParentKeyUseCase
import dev.kpetriv.sprout.feature.lock.ChangePinResult
import dev.kpetriv.sprout.feature.lock.LockSettings
import dev.kpetriv.sprout.feature.lock.ParentKeyChangeResult
import dev.kpetriv.sprout.feature.lock.ParentKeyEnrolment
import dev.kpetriv.sprout.feature.lock.ParentKeyRegistry
import dev.kpetriv.sprout.feature.lock.ParentKeyRenewal
import dev.kpetriv.sprout.feature.lock.ParentKeyStatus
import dev.kpetriv.sprout.feature.lock.ParentSession
import dev.kpetriv.sprout.feature.lock.PinRuleViolation
import dev.kpetriv.sprout.feature.settings.LockPreferences
import dev.kpetriv.sprout.feature.settings.NewPinProblem
import dev.kpetriv.sprout.feature.settings.ParentKeyRegistrar
import dev.kpetriv.sprout.feature.settings.PinChangeOutcome
import dev.kpetriv.sprout.feature.settings.PinChanger
import dev.kpetriv.sprout.feature.settings.SigningKeyOutcome
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import dev.kpetriv.sprout.feature.onboarding.ParentKeyRegistrar as BackendParentKeyRegistrar

/** Settings' [PinChanger] over `feature/lock`'s [ParentSession.changePin] (which zeroes the arrays). */
class ParentSessionPinChanger @Inject constructor(private val session: ParentSession) : PinChanger {
    override suspend fun changePin(current: CharArray, new: CharArray, confirm: CharArray): PinChangeOutcome =
        session.changePin(current, new, confirm).toOutcome()
}

/** Maps `feature/lock`'s result onto the settings port's. */
internal fun ChangePinResult.toOutcome(): PinChangeOutcome = when (this) {
    ChangePinResult.Changed -> PinChangeOutcome.Changed
    is ChangePinResult.WrongCurrentPin -> PinChangeOutcome.WrongCurrentPin(next)
    is ChangePinResult.LockedOut -> PinChangeOutcome.LockedOut(decision)
    is ChangePinResult.Invalid -> PinChangeOutcome.Invalid(violation.toProblem())
    ChangePinResult.Mismatch -> PinChangeOutcome.Mismatch
    ChangePinResult.NotAllowed -> PinChangeOutcome.NotAllowed
    ChangePinResult.Failed -> PinChangeOutcome.Failed
}

private fun PinRuleViolation.toProblem(): NewPinProblem = when (this) {
    PinRuleViolation.WRONG_LENGTH -> NewPinProblem.WRONG_LENGTH
    PinRuleViolation.NOT_DIGITS -> NewPinProblem.NOT_DIGITS
    PinRuleViolation.ALL_SAME -> NewPinProblem.ALL_SAME
    PinRuleViolation.SEQUENCE -> NewPinProblem.SEQUENCE
}

/** Settings' [LockPreferences] over `feature/lock`'s [LockSettings]. */
class LockSettingsPreferences @Inject constructor(private val settings: LockSettings) : LockPreferences {
    override val lockWhenLeaving: StateFlow<Boolean> get() = settings.lockWhenLeaving

    override fun setLockWhenLeaving(enabled: Boolean) = settings.setLockWhenLeaving(enabled)
}

/**
 * Settings' [ParentKeyRegistrar] over `feature/lock`'s PIN-gated [ParentKeyEnrolment] and [ParentKeyRenewal]: they
 * verify the PIN under the lockout, create the parent key, prove it with one fingerprint prompt over a fresh,
 * domain-separated challenge, and only then register it. The proof logic lives in `feature/lock`; this adapter only
 * maps results. Both flows zero the PIN.
 */
class LockParentKeyRegistrar @Inject constructor(
    private val session: ParentSession,
    private val enrolment: ParentKeyEnrolment,
    private val renewal: ParentKeyRenewal,
) : ParentKeyRegistrar {
    override suspend fun needsRenewal(): Boolean = session.parentKeyStatus() == ParentKeyStatus.NEEDS_RENEWAL

    override suspend fun enrol(pin: CharArray): SigningKeyOutcome = enrolment.enrol(pin).toOutcome()

    override suspend fun renew(pin: CharArray): SigningKeyOutcome = renewal.renew(pin).toOutcome()
}

/** Maps `feature/lock`'s key-change result onto the settings port's. */
internal fun ParentKeyChangeResult.toOutcome(): SigningKeyOutcome = when (this) {
    ParentKeyChangeResult.Registered -> SigningKeyOutcome.Registered
    is ParentKeyChangeResult.Unavailable -> SigningKeyOutcome.Unavailable(availability)
    ParentKeyChangeResult.AlreadyRegistered -> SigningKeyOutcome.AlreadyOn
    ParentKeyChangeResult.NotRegistered -> SigningKeyOutcome.NotRegistered
    is ParentKeyChangeResult.WrongPin -> SigningKeyOutcome.WrongPin(next)
    is ParentKeyChangeResult.LockedOut -> SigningKeyOutcome.LockedOut(decision)
    ParentKeyChangeResult.Cancelled -> SigningKeyOutcome.Cancelled
    ParentKeyChangeResult.NotAllowed, ParentKeyChangeResult.Failed -> SigningKeyOutcome.Failed
}

/**
 * `feature/lock`'s [ParentKeyRegistry] over the household policy in `core/database`. Registration goes through
 * [RegisterParentKeyUseCase.register], which accepts only EC P-256 keys and turns required parent signatures on;
 * a replacement keeps the original `parentSignatureFromLamport` (the repository does that on re-registration).
 * Only `feature/lock`'s PIN-gated enrolment and renewal call the writing methods.
 *
 * In a real household (not the trial) the key is then also registered with the backend's `parentKeys` registry
 * ([BackendParentKeyRegistrar], M3 §1), best effort: the key reaches the other devices as a `parent_key` document
 * anyway, and a failure (offline) must not undo the local registration the PIN and fingerprint just proved.
 */
class HouseholdParentKeyRegistry @Inject constructor(
    private val policies: HouseholdPolicyRepository,
    private val useCase: RegisterParentKeyUseCase,
    private val currency: FiatCurrencyProvider,
    private val sessions: SessionStore,
    private val registrar: BackendParentKeyRegistrar,
) : ParentKeyRegistry {
    override suspend fun registeredParentKey(): PublicKeyBytes? = policies.get()?.parentPublicKey?.let(::PublicKeyBytes)

    override suspend fun registerParentKey(publicKey: PublicKeyBytes) {
        check(policies.get()?.hasParentKey != true) { "A parent key is already registered" }
        useCase.register(publicKey.encoded)
        publish(publicKey)
    }

    override suspend fun replaceParentKey(publicKey: PublicKeyBytes) {
        check(policies.get()?.hasParentKey == true) { "No parent key is registered" }
        useCase.register(publicKey.encoded)
        publish(publicKey)
    }

    private suspend fun publish(publicKey: PublicKeyBytes) {
        val session = sessions.get() ?: return
        if (!session.isLocalTrial) registrar.register(publicKey.encoded)
    }

    override suspend fun recordSigningUnavailable() {
        policies.ensure(currency.fiatCurrency())
    }
}
