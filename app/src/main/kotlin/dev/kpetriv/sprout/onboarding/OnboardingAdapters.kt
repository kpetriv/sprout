package dev.kpetriv.sprout.onboarding

import android.content.Context
import dev.kpetriv.sprout.core.common.Dispatcher
import dev.kpetriv.sprout.core.common.SproutDispatchers
import dev.kpetriv.sprout.core.crypto.DeviceKeyStore
import dev.kpetriv.sprout.core.crypto.DeviceRegistrationMaterial
import dev.kpetriv.sprout.core.crypto.DeviceSigner
import dev.kpetriv.sprout.core.crypto.EncryptionKeyStore
import dev.kpetriv.sprout.core.crypto.JceEcdsaSigner
import dev.kpetriv.sprout.core.crypto.chat.EncryptionKeyBinding
import dev.kpetriv.sprout.core.database.FiatCurrencyProvider
import dev.kpetriv.sprout.core.database.HouseholdPolicyRepository
import dev.kpetriv.sprout.core.database.LocalTrial
import dev.kpetriv.sprout.core.database.Session
import dev.kpetriv.sprout.core.database.SessionStore
import dev.kpetriv.sprout.core.database.SproutDatabase
import dev.kpetriv.sprout.core.database.deviceId
import dev.kpetriv.sprout.core.database.entity.RegistrationStatus
import dev.kpetriv.sprout.core.database.usecase.PurgeLocalHouseholdUseCase
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.toHex
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.UserId
import dev.kpetriv.sprout.core.syncfirebase.AuthError
import dev.kpetriv.sprout.core.syncfirebase.AuthException
import dev.kpetriv.sprout.core.syncfirebase.AuthState
import dev.kpetriv.sprout.core.syncfirebase.DeviceKeyRegistration
import dev.kpetriv.sprout.core.syncfirebase.DeviceProof
import dev.kpetriv.sprout.core.syncfirebase.DeviceStatusValue
import dev.kpetriv.sprout.core.syncfirebase.EncryptionKeyUpload
import dev.kpetriv.sprout.core.syncfirebase.FirebaseAuthSession
import dev.kpetriv.sprout.core.syncfirebase.FirebaseConfig
import dev.kpetriv.sprout.core.syncfirebase.FunctionsError
import dev.kpetriv.sprout.core.syncfirebase.FunctionsException
import dev.kpetriv.sprout.core.syncfirebase.HouseholdFunctions
import dev.kpetriv.sprout.feature.lock.ParentKeyChangeResult
import dev.kpetriv.sprout.feature.lock.ParentKeyEnrolment
import dev.kpetriv.sprout.feature.lock.ParentKeyStatus
import dev.kpetriv.sprout.feature.lock.ParentKeyStatusCheck
import dev.kpetriv.sprout.feature.onboarding.AccountClaims
import dev.kpetriv.sprout.feature.onboarding.LinkCodeInput
import dev.kpetriv.sprout.feature.onboarding.LinkedKidDevice
import dev.kpetriv.sprout.feature.onboarding.OnboardingAuth
import dev.kpetriv.sprout.feature.onboarding.OnboardingException
import dev.kpetriv.sprout.feature.onboarding.OnboardingFailure
import dev.kpetriv.sprout.feature.onboarding.OnboardingHousehold
import dev.kpetriv.sprout.feature.onboarding.ParentKeyRegistrar
import dev.kpetriv.sprout.feature.onboarding.ParentSigningSetup
import dev.kpetriv.sprout.feature.onboarding.PendingParentDevice
import dev.kpetriv.sprout.feature.onboarding.RegisteredHousehold
import dev.kpetriv.sprout.feature.onboarding.RegistrationBackend
import dev.kpetriv.sprout.feature.onboarding.RegistrationKey
import dev.kpetriv.sprout.feature.onboarding.RegistryStatus
import dev.kpetriv.sprout.feature.onboarding.SigningSetupResult
import dev.kpetriv.sprout.household.HouseholdAdminFunctions
import dev.kpetriv.sprout.sync.HouseholdRemovals
import dev.kpetriv.sprout.sync.LocalHouseholdRows
import dev.kpetriv.sprout.sync.ParentSetupTracker
import dev.kpetriv.sprout.sync.RemovalReason
import dev.kpetriv.sprout.sync.ShellPreferences
import dev.kpetriv.sprout.sync.SyncController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Onboarding's sign-in over [FirebaseAuthSession]; e-mail/password only against the Auth emulator (D-M3-2). */
class FirebaseOnboardingAuth @Inject constructor(private val auth: FirebaseAuthSession, config: FirebaseConfig) :
    OnboardingAuth {
    override val usesEmailPassword: Boolean = config.emulatorHost != null

    override suspend fun signInWithGoogle(activityContext: Context): Result<Unit> =
        auth.signInWithGoogle(activityContext).mapAuthFailure()

    override suspend fun signInWithEmailPassword(email: String, password: String): Result<Unit> =
        auth.signInWithEmailPassword(email, password).mapAuthFailure()

    override suspend fun signInAnonymously(): Result<Unit> = auth.signInAnonymously().mapAuthFailure()

    override suspend fun refreshClaims(): Result<AccountClaims> = auth.refreshClaims().mapAuthFailure().mapCatching {
        val state = auth.current() as? AuthState.SignedIn ?: throw OnboardingException(OnboardingFailure.UNKNOWN)
        AccountClaims(state.uid, it.hid, it.role, it.kidId)
    }

    override suspend fun signOut() = auth.signOut()
}

/**
 * Onboarding's callables over [HouseholdFunctions]. `deviceStatus` is signed here with the key it asks about (the
 * active or the pending registration key, [DeviceKeyStore]), since onboarding has no session yet.
 *
 * A second parent phone ([registerParentDevice]) also sends its X25519 encryption key ([EncryptionKeyStore], created
 * here), bound to the household of the signed-in parent's claims by the **pending** registration key
 * ([EncryptionKeyBinding.signWith], M4 §1.1). `createHousehold` and `redeemLinkCode` cannot: the household id the
 * binding must name is only known from their answer, so those devices register the key right after joining
 * (`EncryptionKeyRegistrar`, D-M4-13). If the key cannot be prepared, the phone registers without it and does the same.
 */
@Suppress("LongParameterList") // The callables, the device keys and the encryption key with its binding.
class FunctionsRegistrationBackend @Inject constructor(
    private val functions: HouseholdFunctions,
    private val keys: DeviceKeyStore,
    @param:Dispatcher(SproutDispatchers.IO) private val dispatcher: CoroutineDispatcher,
    private val auth: FirebaseAuthSession,
    private val encryptionKeys: EncryptionKeyStore,
    private val binding: EncryptionKeyBinding,
) : RegistrationBackend {
    override suspend fun beginDeviceRegistration(): Result<ByteArray> =
        functions.beginDeviceRegistration().mapFunctionsFailure()

    override suspend fun deviceStatus(householdId: HouseholdId, key: RegistrationKey): Result<RegistryStatus> {
        val handle = when (key) {
            RegistrationKey.CURRENT -> keys.deviceSigningKey()
            RegistrationKey.PENDING -> keys.pendingSigningKey() ?: return Result.success(RegistryStatus.UNKNOWN)
        }
        val deviceId = DeviceId(handle.publicKey.fingerprint().toHex())
        val payload = DeviceProof.deviceStatusPayload(householdId, deviceId)
        val signature = withContext(dispatcher) { JceEcdsaSigner(handle).sign(payload) }
        return functions.deviceStatus(householdId, deviceId, signature)
            .map { answer ->
                when (answer.status) {
                    DeviceStatusValue.ACTIVE -> RegistryStatus.ACTIVE
                    DeviceStatusValue.PENDING -> RegistryStatus.PENDING
                    DeviceStatusValue.REVOKED -> RegistryStatus.REVOKED
                    DeviceStatusValue.UNKNOWN -> RegistryStatus.UNKNOWN
                }
            }
            // A key the registry does not hold fails its proof: for this lookup that simply means "not registered".
            .recoverCatching { failure ->
                if ((failure as? FunctionsException)?.error == FunctionsError.PermissionDenied) {
                    RegistryStatus.UNKNOWN
                } else {
                    throw failure
                }
            }
            .mapFunctionsFailure()
    }

    override suspend fun createHousehold(
        fiatCurrency: Currency.Fiat,
        key: DeviceRegistrationMaterial,
    ): Result<RegisteredHousehold> = functions.createHousehold(fiatCurrency.isoCode, key.toRegistration())
        .mapFunctionsFailure()
        .map { RegisteredHousehold(it.hid, it.deviceId) }

    override suspend fun registerParentDevice(key: DeviceRegistrationMaterial): Result<PendingParentDevice> =
        functions.registerParentDevice(key.toRegistration(), encryptionKeyFor(key))
            .mapFunctionsFailure()
            .map { PendingParentDevice(it.deviceId, it.fingerprint) }

    /**
     * This device's encryption key bound by the pending registration key [key] to the household of the signed-in
     * parent; null when any part is missing (no claims, no pending key, a key store failure).
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException", "ReturnCount") // Optional: registered later instead.
    private suspend fun encryptionKeyFor(key: DeviceRegistrationMaterial): EncryptionKeyUpload? = try {
        val householdId = (auth.current() as? AuthState.SignedIn)?.claims?.hid ?: return null
        val pending = keys.pendingSigningKey()?.takeIf { it.publicKey == key.publicKey } ?: return null
        val deviceId = DeviceId(pending.publicKey.fingerprint().toHex())
        val publicKey = encryptionKeys.publicKey()
        val signature = withContext(dispatcher) { binding.signWith(pending, householdId, deviceId, publicKey) }
        EncryptionKeyUpload(publicKey.bytes, signature)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Exception) {
        null
    }

    override suspend fun redeemLinkCode(code: LinkCodeInput, key: DeviceRegistrationMaterial): Result<LinkedKidDevice> =
        functions.redeemLinkCode(code.value, key.toRegistration())
            .mapFunctionsFailure(codeRefusal = true)
            .map { LinkedKidDevice(it.hid, it.kidId, it.deviceId) }

    private fun DeviceRegistrationMaterial.toRegistration() =
        DeviceKeyRegistration(publicKey.encoded, attestationChain.takeIf { it.isNotEmpty() })
}

/**
 * Onboarding's view of the local household: the trial household (its session or any of its kids), the purge, the
 * household policy, sync through [SyncController], this device's registry entry, the session (guarded by the account
 * the local rows belong to, [ShellPreferences.linkedAccount]), the parent setup marker and the erase.
 */
@Suppress("LongParameterList", "TooManyFunctions") // One collaborator per question onboarding asks.
class RoomOnboardingHousehold @Inject constructor(
    private val database: SproutDatabase,
    private val sessions: SessionStore,
    private val purge: PurgeLocalHouseholdUseCase,
    private val policies: HouseholdPolicyRepository,
    private val currencies: FiatCurrencyProvider,
    private val sync: SyncController,
    private val signer: DeviceSigner,
    private val preferences: ShellPreferences,
    private val parentSetup: ParentSetupTracker,
    private val removals: HouseholdRemovals,
    private val localRows: LocalHouseholdRows,
) : OnboardingHousehold {
    override fun defaultCurrency(): Currency.Fiat = currencies.fiatCurrency()

    override suspend fun hasTrialData(): Boolean =
        sessions.get()?.isLocalTrial == true || database.purgeDao().kidsOf(LocalTrial.HOUSEHOLD).isNotEmpty()

    override suspend fun purgeTrialData() {
        purge()
    }

    override suspend fun ensureHouseholdPolicy(fiatCurrency: Currency.Fiat) {
        policies.ensure(fiatCurrency)
    }

    override suspend fun syncNow(): Boolean = sync.syncNow()?.error == null

    /** Only a mirror that belongs to [uid] in [householdId] answers (audit: the mirror has no household column). */
    override suspend fun activeDeviceOf(uid: UserId, householdId: HouseholdId): DeviceId? {
        if (preferences.linkedAccount() != preferences.digestOf(householdId, uid)) return null
        val own = signer.deviceId()
        val entry = database.deviceDao().get(own)
        return own.takeIf { entry != null && entry.uid == uid && entry.status == RegistrationStatus.ACTIVE }
    }

    override suspend fun saveSession(session: Session) {
        if (!session.isLocalTrial) {
            val digest = preferences.digestOf(session.householdId, session.uid)
            val linked = preferences.linkedAccount()
            // Rows of another account or household must never mix with this one's (D-M3-47).
            if (linked != null && linked != digest) localRows.clear()
            sessions.save(session)
            preferences.setLinkedAccount(digest)
        } else {
            sessions.save(session)
        }
    }

    override suspend fun startParentSetup(householdId: HouseholdId) = parentSetup.start(householdId)

    override suspend fun finishParentSetup() = parentSetup.finish()

    override suspend fun parentSetupPending(householdId: HouseholdId): Boolean = parentSetup.isPending(householdId)

    /**
     * Any registry mirror row (every pull starts with the registry, which holds at least this device), any pull
     * cursor of [householdId], or an unsynced change: then a sync ran or data was written.
     */
    override suspend fun hasHouseholdData(householdId: HouseholdId): Boolean = database.deviceDao().count() > 0 ||
        database.syncCursorDao().all(householdId).isNotEmpty() ||
        database.outboxDao().count() > 0

    override suspend fun eraseLocalHousehold() = removals.remove(RemovalReason.SIGNED_OUT)
}

/** Onboarding's signing step over `feature/lock`'s status check and PIN-gated enrolment. */
class LockParentSigningSetup @Inject constructor(
    private val status: ParentKeyStatusCheck,
    private val enrolment: ParentKeyEnrolment,
) : ParentSigningSetup {
    override suspend fun needsEnrolment(): Boolean = status.status() == ParentKeyStatus.NOT_REGISTERED

    override suspend fun enrol(pin: CharArray): SigningSetupResult = when (enrolment.enrol(pin)) {
        ParentKeyChangeResult.Registered, ParentKeyChangeResult.AlreadyRegistered -> SigningSetupResult.REGISTERED

        is ParentKeyChangeResult.Unavailable -> SigningSetupResult.UNAVAILABLE

        is ParentKeyChangeResult.WrongPin -> SigningSetupResult.WRONG_PIN

        is ParentKeyChangeResult.LockedOut -> SigningSetupResult.LOCKED_OUT

        ParentKeyChangeResult.Cancelled -> SigningSetupResult.CANCELLED

        ParentKeyChangeResult.NotRegistered, ParentKeyChangeResult.NotAllowed, ParentKeyChangeResult.Failed ->
            SigningSetupResult.FAILED
    }
}

/**
 * [ParentKeyRegistrar] over the `registerParentKey` callable ([HouseholdAdminFunctions], device-signed), which also
 * records the server's registration locally.
 */
class FunctionsParentKeyRegistrar @Inject constructor(private val functions: HouseholdAdminFunctions) :
    ParentKeyRegistrar {
    override suspend fun register(publicKey: ByteArray, attestationChain: List<ByteArray>): Result<Unit> =
        functions.registerParentKey(publicKey, attestationChain).map { }.mapFunctionsFailure()
}

private fun <T> Result<T>.mapAuthFailure(): Result<T> = recoverCatching { failure ->
    val error = (failure as? AuthException)?.error
    throw OnboardingException(
        when (error) {
            AuthError.Cancelled -> OnboardingFailure.CANCELLED
            AuthError.NoCredential -> OnboardingFailure.NO_GOOGLE_ACCOUNT
            AuthError.GoogleNotConfigured -> OnboardingFailure.GOOGLE_NOT_CONFIGURED
            AuthError.NotAvailableInThisBuild -> OnboardingFailure.NOT_AVAILABLE
            AuthError.InvalidCredential -> OnboardingFailure.INVALID_CREDENTIAL
            AuthError.UserDisabled -> OnboardingFailure.ACCOUNT_DISABLED
            AuthError.Network -> OnboardingFailure.NETWORK
            AuthError.TooManyRequests -> OnboardingFailure.TOO_MANY_ATTEMPTS
            AuthError.SignedOut, AuthError.UserMismatch, AuthError.Unknown, null -> OnboardingFailure.UNKNOWN
        },
    )
}

/**
 * Maps a callable's [FunctionsError]. With [codeRefusal] (redeeming a link code), every refusal of the request
 * itself reads as "code not accepted": the server does not say whether a code is wrong, expired or used (T6).
 */
internal fun <T> Result<T>.mapFunctionsFailure(codeRefusal: Boolean = false): Result<T> = recoverCatching { failure ->
    if (failure is OnboardingException) throw failure
    val error = (failure as? FunctionsException)?.error
    throw OnboardingException(
        when (error) {
            FunctionsError.Unavailable -> OnboardingFailure.NETWORK

            FunctionsError.ResourceExhausted -> OnboardingFailure.TOO_MANY_ATTEMPTS

            FunctionsError.NotFound, FunctionsError.InvalidArgument, FunctionsError.FailedPrecondition ->
                if (codeRefusal) OnboardingFailure.CODE_NOT_ACCEPTED else OnboardingFailure.UNKNOWN

            FunctionsError.PermissionDenied, FunctionsError.AlreadyExists, FunctionsError.Unauthenticated ->
                if (codeRefusal) OnboardingFailure.CODE_NOT_ACCEPTED else OnboardingFailure.NOT_ALLOWED

            else -> OnboardingFailure.UNKNOWN
        },
    )
}
