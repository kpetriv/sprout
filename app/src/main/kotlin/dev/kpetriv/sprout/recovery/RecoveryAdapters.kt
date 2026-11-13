package dev.kpetriv.sprout.recovery

import dev.kpetriv.sprout.core.crypto.RecoveryPinVerifier
import dev.kpetriv.sprout.core.database.LocalPreferenceStore
import dev.kpetriv.sprout.core.database.Session
import dev.kpetriv.sprout.core.database.SessionStore
import dev.kpetriv.sprout.core.database.entity.RegistrationStatus
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.sync.RecoveredParentDevice
import dev.kpetriv.sprout.core.sync.RecoveryCallables
import dev.kpetriv.sprout.core.sync.RecoveryDeviceStatus
import dev.kpetriv.sprout.core.sync.RecoveryMethod
import dev.kpetriv.sprout.core.sync.RecoveryRefusal
import dev.kpetriv.sprout.core.sync.RecoveryRefusedException
import dev.kpetriv.sprout.core.sync.RecoveryRequest
import dev.kpetriv.sprout.core.sync.RecoveryStatus
import dev.kpetriv.sprout.core.sync.StoredRecoveryPin
import dev.kpetriv.sprout.core.sync.SyncError
import dev.kpetriv.sprout.core.sync.SyncException
import dev.kpetriv.sprout.core.syncfirebase.FunctionsError
import dev.kpetriv.sprout.core.syncfirebase.FunctionsException
import dev.kpetriv.sprout.feature.lock.ParentSession
import dev.kpetriv.sprout.feature.lock.RecoveredPinResult
import dev.kpetriv.sprout.feature.lock.RecoveryCheckUploader
import dev.kpetriv.sprout.feature.lock.RecoveryPinRegistration
import dev.kpetriv.sprout.feature.lock.RecoveryPinSetupResult
import dev.kpetriv.sprout.feature.lock.RecoveryUploadResult
import dev.kpetriv.sprout.feature.onboarding.AccountRecovery
import dev.kpetriv.sprout.feature.onboarding.OnboardingException
import dev.kpetriv.sprout.feature.onboarding.OnboardingFailure
import dev.kpetriv.sprout.feature.onboarding.RecoveredDevice
import dev.kpetriv.sprout.feature.onboarding.RecoveredPinKeeper
import dev.kpetriv.sprout.feature.onboarding.RecoveryChoice
import dev.kpetriv.sprout.feature.onboarding.RecoveryException
import dev.kpetriv.sprout.feature.onboarding.RecoveryOptions
import dev.kpetriv.sprout.feature.onboarding.RecoveryPinParameters
import dev.kpetriv.sprout.feature.onboarding.RecoveryProblem
import dev.kpetriv.sprout.feature.settings.AccountRecoverySetup
import dev.kpetriv.sprout.feature.settings.RecoverySetupOutcome
import dev.kpetriv.sprout.feature.settings.RecoverySetupState
import dev.kpetriv.sprout.household.HouseholdAdminFunctions
import dev.kpetriv.sprout.sync.CredentialGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import java.security.GeneralSecurityException
import javax.inject.Inject

/*
 * Account recovery (ADR-0013) between the features and the backend: core/sync's [RecoveryCallables] over the device-
 * signed callables, the waiting phone's [AccountRecovery] (onboarding), the check value upload (lock), the PIN a
 * restore keeps (lock), the Settings row, and the registration mark's place in the wipe. Nothing here logs; no PIN,
 * verifier or salt leaves these functions except to the server.
 */

/** [RecoveryCallables] over [HouseholdAdminFunctions] (device proofs bound to the ID token). */
class FunctionsRecoveryCallables @Inject constructor(private val admin: HouseholdAdminFunctions) : RecoveryCallables {
    override suspend fun setRecoveryPin(
        salt: ByteArray,
        kdf: String,
        verifier: ByteArray,
        newPin: Boolean,
    ): Result<StoredRecoveryPin> = admin.setRecoveryPin(salt, kdf, verifier, newPin).mapCallFailure()

    override suspend fun recoveryStatus(): Result<RecoveryStatus> = admin.recoveryStatus().mapCallFailure()

    override suspend fun recoverParentDevice(request: RecoveryRequest): Result<RecoveredParentDevice> {
        val verifier = (request as? RecoveryRequest.Pin)?.verifier()
        return try {
            admin.recoverParentDevice(verifier).mapCallFailure()
        } finally {
            verifier?.fill(0)
        }
    }
}

/** A callable's failure as [RecoveryCallables] reports it: typed refusals stay, the rest becomes a [SyncException]. */
internal fun <T> Result<T>.mapCallFailure(): Result<T> = recoverCatching { failure ->
    throw when (failure) {
        is RecoveryRefusedException -> failure

        is FunctionsException -> SyncException(
            when (failure.error) {
                FunctionsError.Unavailable -> SyncError.Transient()
                FunctionsError.Unauthenticated -> SyncError.Unauthenticated
                else -> SyncError.Permanent(failure.error.code)
            },
        )

        else -> failure
    }
}

/**
 * The app's own path for a phone whose session was revoked: the "Sign in again" gate ([CredentialGate], audit H2).
 * A restore revokes every session of the account, this phone's too (ADR-0013 §2), so the waiting phone raises the gate
 * once it is ACTIVE and waits until it is down again: signed in with the same account and synced once, or erased
 * from the gate instead (then false).
 */
fun interface RecoverySignIn {
    suspend fun signInAgain(): Boolean
}

class CredentialGateSignIn @Inject constructor(private val gate: CredentialGate, private val sessions: SessionStore) :
    RecoverySignIn {
    override suspend fun signInAgain(): Boolean {
        gate.credentialsLost()
        // Down again (signed in as the same account and synced), or this phone's copy erased from the gate instead.
        combine(gate.state, sessions.observe()) { state, session -> state == null || session == null }.first { it }
        return sessions.get()?.takeUnless { it.isLocalTrial } != null
    }
}

/**
 * The waiting phone's [AccountRecovery]: `recoveryStatus` and `recoverParentDevice` signed by its own (PENDING) key,
 * the check value derived with [RecoveryPinVerifier] for the session's household, and the second sign-in through
 * [RecoverySignIn]. An `internal` answer of `recoverParentDevice` is sent once more: the server then finishes the
 * session revocation of the now ACTIVE phone and answers as the first call would have.
 */
class SyncAccountRecovery @Inject constructor(
    private val callables: RecoveryCallables,
    private val verifier: RecoveryPinVerifier,
    private val sessions: SessionStore,
    private val signIn: RecoverySignIn,
) : AccountRecovery {
    override suspend fun status(): Result<RecoveryOptions> = callables.recoveryStatus().map { status ->
        RecoveryOptions(
            pin = status.pin?.let { RecoveryPinParameters(it.salt, it.kdf, verifier.supports(it.kdf)) },
            attemptsLeft = status.attemptsLeft,
            waitEndsAt = status.waitEndsAt,
        )
    }.mapForOnboarding()

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // A KDF or storage failure: shown as UNKNOWN only.
    override suspend fun verifierFor(pin: CharArray, parameters: RecoveryPinParameters): Result<ByteArray> = try {
        val session = sessions.get()?.takeUnless { it.isLocalTrial }
            ?: throw OnboardingException(OnboardingFailure.UNKNOWN)
        Result.success(verifier.derive(pin, session.householdId, parameters.salt, parameters.kdf))
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: OnboardingException) {
        Result.failure(failure)
    } catch (failure: Exception) {
        Result.failure(OnboardingException(OnboardingFailure.UNKNOWN))
    }

    override suspend fun recover(verifier: ByteArray?): Result<RecoveredDevice> {
        val request = verifier?.let(RecoveryRequest::Pin) ?: RecoveryRequest.Wait
        return try {
            var answer = callables.recoverParentDevice(request)
            if (answer.isUnfinished()) answer = callables.recoverParentDevice(request)
            answer.map { recovered ->
                val method = when (recovered.method) {
                    RecoveryMethod.PIN -> RecoveryChoice.PIN
                    RecoveryMethod.WAIT -> RecoveryChoice.WAIT
                }
                RecoveredDevice(recovered.attemptsLeft, recovered.signInAgain, method)
            }.mapForOnboarding()
        } finally {
            (request as? RecoveryRequest.Pin)?.wipe()
        }
    }

    override suspend fun signInAgain(): Boolean = signIn.signInAgain()

    private fun Result<*>.isUnfinished(): Boolean =
        ((exceptionOrNull() as? SyncException)?.error as? SyncError.Permanent)?.code == INTERNAL

    private companion object {
        const val INTERNAL = "internal"
    }
}

/** A recovery failure as onboarding shows it. */
internal fun <T> Result<T>.mapForOnboarding(): Result<T> = recoverCatching { failure ->
    throw when (failure) {
        is RecoveryRefusedException -> failure.refusal.toProblem()?.let(::RecoveryException)
            ?: OnboardingException(OnboardingFailure.UNKNOWN)

        is SyncException -> OnboardingException(
            when (val error = failure.error) {
                is SyncError.Transient -> OnboardingFailure.NETWORK

                is SyncError.Permanent -> if (error.code == RESOURCE_EXHAUSTED) {
                    OnboardingFailure.TOO_MANY_ATTEMPTS
                } else {
                    OnboardingFailure.UNKNOWN
                }

                SyncError.Unauthenticated, SyncError.HouseholdGone -> OnboardingFailure.UNKNOWN
            },
        )

        is OnboardingException -> failure

        else -> OnboardingException(OnboardingFailure.UNKNOWN)
    }
}

private const val RESOURCE_EXHAUSTED = "resource-exhausted"

/** Null for a `not-pending` status that is neither ACTIVE nor REVOKED: that is not acted on. */
private fun RecoveryRefusal.toProblem(): RecoveryProblem? = when (this) {
    RecoveryRefusal.RecentSignIn -> RecoveryProblem.RecentSignIn

    is RecoveryRefusal.WrongPin -> RecoveryProblem.WrongPin(attemptsLeft)

    RecoveryRefusal.PinUnavailable -> RecoveryProblem.PinUnavailable

    is RecoveryRefusal.Wait -> RecoveryProblem.Wait(waitEndsAt)

    RecoveryRefusal.OtherParent -> RecoveryProblem.OtherParent

    is RecoveryRefusal.NotPending -> when (status) {
        RecoveryDeviceStatus.ACTIVE -> RecoveryProblem.NotPending(active = true)
        RecoveryDeviceStatus.REVOKED -> RecoveryProblem.NotPending(active = false)
        RecoveryDeviceStatus.OTHER -> null
    }
}

/** True for a signed-in parent's ACTIVE device: where a check value can be registered. */
internal fun Session?.registersRecoveryPin(): Boolean =
    this != null && !isLocalTrial && role == Role.PARENT && deviceStatus == RegistrationStatus.ACTIVE

/**
 * `feature/lock`'s [RecoveryCheckUploader]: derives a new check value for the session's household and stores it with
 * `setRecoveryPin`. Only for a signed-in parent's ACTIVE device; anything else is [RecoveryUploadResult.NotApplicable].
 */
class ServerRecoveryCheckUploader @Inject constructor(
    private val callables: RecoveryCallables,
    private val verifier: RecoveryPinVerifier,
    private val sessions: SessionStore,
) : RecoveryCheckUploader {
    @Suppress("ReturnCount") // Not applicable, the derivation, then the call.
    override suspend fun upload(pin: CharArray, newPin: Boolean): RecoveryUploadResult {
        val session = sessions.get()?.takeIf { it.registersRecoveryPin() } ?: return RecoveryUploadResult.NotApplicable
        val value = try {
            verifier.create(pin, session.householdId)
        } catch (expected: GeneralSecurityException) {
            return RecoveryUploadResult.Failed
        } catch (expected: IllegalArgumentException) {
            return RecoveryUploadResult.Failed
        }
        return value.use { checkValue ->
            val bytes = checkValue.verifierBytes()
            try {
                callables.setRecoveryPin(checkValue.salt, checkValue.kdf.descriptor, bytes, newPin).fold(
                    { RecoveryUploadResult.Uploaded(it.stored, it.attemptsLeft) },
                    { failure ->
                        // Offline, or a session that must sign in again (after a restore): not a refusal.
                        val error = (failure as? SyncException)?.error
                        val retryable = error is SyncError.Transient || error == SyncError.Unauthenticated
                        if (retryable) RecoveryUploadResult.Failed else RecoveryUploadResult.Refused
                    },
                )
            } finally {
                bytes.fill(0)
            }
        }
    }
}

/** Onboarding's [RecoveredPinKeeper] over [ParentSession]'s `adoptRecoveredPin` and `forgetPinForNewDevice`. */
class LockRecoveredPinKeeper @Inject constructor(private val session: ParentSession) : RecoveredPinKeeper {
    override suspend fun keep(pin: CharArray): Boolean = session.adoptRecoveredPin(pin) == RecoveredPinResult.Adopted

    override suspend fun forgetForNewDevice() = session.forgetPinForNewDevice()
}

/** Settings' [AccountRecoverySetup]: the registration mark for a parent's ACTIVE device, and "finish setting up". */
class LockAccountRecoverySetup @Inject constructor(
    sessions: SessionStore,
    registration: RecoveryPinRegistration,
    private val session: ParentSession,
) : AccountRecoverySetup {
    override val state: Flow<RecoverySetupState?> =
        combine(sessions.observe(), registration.marks) { current, marks ->
            if (!current.registersRecoveryPin()) {
                null
            } else {
                val registered = marks.registered
                val kept = marks.keptOther.takeIf { registered == null }
                RecoverySetupState(
                    ready = registered != null,
                    attemptsLeft = (registered ?: kept)?.attemptsLeft,
                    keptOther = kept != null,
                )
            }
        }

    override suspend fun finish(pin: CharArray): RecoverySetupOutcome =
        when (val result = session.registerRecoveryPin(pin)) {
            RecoveryPinSetupResult.Registered -> RecoverySetupOutcome.Registered
            is RecoveryPinSetupResult.WrongPin -> RecoverySetupOutcome.WrongPin(result.next)
            is RecoveryPinSetupResult.LockedOut -> RecoverySetupOutcome.LockedOut(result.decision)
            RecoveryPinSetupResult.Failed -> RecoverySetupOutcome.Offline
            RecoveryPinSetupResult.Refused -> RecoverySetupOutcome.Refused
            RecoveryPinSetupResult.KeptOther -> RecoverySetupOutcome.KeptOther
            RecoveryPinSetupResult.NotAllowed, RecoveryPinSetupResult.Unavailable -> RecoverySetupOutcome.NotAvailable
        }
}

/**
 * The recovery marks are wiped with the household's local data, as every [LocalPreferenceStore] is, through
 * [RecoveryPinRegistration.forget], so an upload still running cannot write a mark back afterwards.
 */
class RecoveryPinFlagStore @Inject constructor(private val registration: RecoveryPinRegistration) :
    LocalPreferenceStore {
    override suspend fun clear() = registration.forget()
}
