package dev.kpetriv.sprout.feature.onboarding

import android.content.Context
import dev.kpetriv.sprout.core.crypto.DeviceRegistrationMaterial
import dev.kpetriv.sprout.core.crypto.PublicKeyBytes
import dev.kpetriv.sprout.core.database.Session
import dev.kpetriv.sprout.core.database.SessionStore
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.toHex
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.model.UserId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** Everything the fakes did, in order, so tests can assert the sequence of a flow. */
class CallLog {
    val calls = mutableListOf<String>()

    operator fun plusAssign(call: String) {
        calls += call
    }
}

val PARENT_UID = UserId("parent-uid")
val KID_UID = UserId("kid-uid")
val HOUSEHOLD = HouseholdId("hh-1")
val LEO = KidId("kid-leo")
val NEW_DEVICE = DeviceId("ab12cd34ef567890" + "0".repeat(48))
val NEW_KID_CLAIMS = AccountClaims(KID_UID, null, null, null)

class FakeOnboardingAuth(private val log: CallLog, override val usesEmailPassword: Boolean = true) : OnboardingAuth {
    var signInResult: Result<Unit> = Result.success(Unit)

    /** Claims returned by successive refreshes; the last one repeats. */
    val claims = mutableListOf(AccountClaims(PARENT_UID, null, null, null))
    var lastEmail: String? = null

    override suspend fun signInWithGoogle(activityContext: Context): Result<Unit> {
        log += "signInWithGoogle"
        return signInResult
    }

    override suspend fun signInWithEmailPassword(email: String, password: String): Result<Unit> {
        log += "signInWithEmail"
        lastEmail = email
        return signInResult
    }

    override suspend fun signInAnonymously(): Result<Unit> {
        log += "signInAnonymously"
        return Result.success(Unit)
    }

    override suspend fun refreshClaims(): Result<AccountClaims> {
        log += "refreshClaims"
        return Result.success(if (claims.size > 1) claims.removeAt(0) else claims.first())
    }

    override suspend fun signOut() {
        log += "signOut"
    }
}

/**
 * The callables. By default every registration call answers with the registered key's own device id (as the server
 * does); [deviceIdOverride] answers with another id instead. [statuses] is what `deviceStatus` reports per key
 * (UNKNOWN when absent); [statusFailure] makes it fail.
 */
class FakeRegistrationBackend(private val log: CallLog) : RegistrationBackend {
    var createFailure: Throwable? = null
    var redeemFailure: Throwable? = null
    var deviceIdOverride: DeviceId? = null
    var householdOf: HouseholdId = HOUSEHOLD
    var createdWith: Currency.Fiat? = null
    var redeemedCode: LinkCodeInput? = null
    val registeredKeys = mutableListOf<DeviceRegistrationMaterial>()
    val statuses = mutableMapOf<RegistrationKey, RegistryStatus>()
    var statusFailure: Throwable? = null

    /** Runs inside `createHousehold` before it answers, for example to hold it while a test presses back. */
    var beforeCreate: suspend () -> Unit = {}

    override suspend fun beginDeviceRegistration(): Result<ByteArray> {
        log += "begin"
        return Result.success(ByteArray(32) { 7 })
    }

    override suspend fun deviceStatus(householdId: HouseholdId, key: RegistrationKey): Result<RegistryStatus> {
        log += "status:$key"
        statusFailure?.let { return Result.failure(it) }
        return Result.success(statuses[key] ?: RegistryStatus.UNKNOWN)
    }

    override suspend fun createHousehold(
        fiatCurrency: Currency.Fiat,
        key: DeviceRegistrationMaterial,
    ): Result<RegisteredHousehold> {
        log += "createHousehold"
        beforeCreate()
        createdWith = fiatCurrency
        registeredKeys += key
        createFailure?.let { return Result.failure(it) }
        return Result.success(RegisteredHousehold(householdOf, idFor(key)))
    }

    override suspend fun registerParentDevice(key: DeviceRegistrationMaterial): Result<PendingParentDevice> {
        log += "registerParentDevice"
        registeredKeys += key
        // The server's fingerprint is deliberately wrong: the phone shows its own.
        return Result.success(PendingParentDevice(idFor(key), "0000 0000 0000 0000"))
    }

    override suspend fun redeemLinkCode(code: LinkCodeInput, key: DeviceRegistrationMaterial): Result<LinkedKidDevice> {
        log += "redeemLinkCode"
        redeemedCode = code
        registeredKeys += key
        redeemFailure?.let { return Result.failure(it) }
        return Result.success(LinkedKidDevice(householdOf, LEO, idFor(key)))
    }

    private fun idFor(key: DeviceRegistrationMaterial): DeviceId = deviceIdOverride ?: key.publicKey.deviceId()
}

/** The device id of a public key, as the server and the app compute it. */
fun PublicKeyBytes.deviceId(): DeviceId = DeviceId(fingerprint().toHex())

class FakeOnboardingHousehold(
    private val log: CallLog,
    private val sessions: FakeSessionStore,
    var trial: Boolean = false,
) : OnboardingHousehold {
    var policyCurrency: Currency.Fiat? = null
    var activeDevice: DeviceId? = null
    var syncs = 0
    var syncSucceeds = true
    var setupPending: HouseholdId? = null

    /** Runs on every sync, for example to mirror an approval into the session. */
    var onSync: suspend () -> Unit = {}

    override fun defaultCurrency(): Currency.Fiat = Currency.Fiat("EUR")

    override suspend fun hasTrialData(): Boolean = trial

    override suspend fun purgeTrialData() {
        log += "purge"
        trial = false
    }

    override suspend fun ensureHouseholdPolicy(fiatCurrency: Currency.Fiat) {
        log += "ensurePolicy"
        policyCurrency = fiatCurrency
    }

    override suspend fun syncNow(): Boolean {
        log += "sync"
        syncs += 1
        onSync()
        return syncSucceeds
    }

    override suspend fun activeDeviceOf(uid: UserId, householdId: HouseholdId): DeviceId? = activeDevice

    override suspend fun saveSession(session: Session) = sessions.save(session)

    override suspend fun startParentSetup(householdId: HouseholdId) {
        log += "setupStarted"
        setupPending = householdId
    }

    override suspend fun finishParentSetup() {
        log += "setupFinished"
        setupPending = null
    }

    override suspend fun parentSetupPending(householdId: HouseholdId): Boolean = setupPending == householdId

    override suspend fun eraseLocalHousehold() {
        log += "erase"
        sessions.session.value = null
    }
}

class FakeSessionStore(private val log: CallLog, initial: Session? = null) : SessionStore {
    val session = MutableStateFlow(initial)

    override fun observe(): Flow<Session?> = session

    override suspend fun get(): Session? = session.value

    override suspend fun save(session: Session) {
        log += "saveSession:${session.role}:${session.deviceStatus}"
        this.session.value = session
    }

    override suspend fun clear() {
        log += "clearSession"
        session.value = null
    }

    override suspend fun ensureLocalTrialSession(deviceId: DeviceId): Session = error("not used")
}

fun parentClaims(householdId: HouseholdId? = HOUSEHOLD) =
    AccountClaims(PARENT_UID, householdId, if (householdId != null) Role.PARENT else null, null)
