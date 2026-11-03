package dev.kpetriv.sprout.feature.onboarding

import android.content.Context
import dev.kpetriv.sprout.core.crypto.DeviceRegistrationMaterial
import dev.kpetriv.sprout.core.database.Session
import dev.kpetriv.sprout.core.database.SessionStore
import dev.kpetriv.sprout.core.domainledger.Currency
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

class FakeRegistrationBackend(private val log: CallLog) : RegistrationBackend {
    var createResult: Result<RegisteredHousehold> = Result.success(RegisteredHousehold(HOUSEHOLD, NEW_DEVICE))
    var redeemResult: Result<LinkedKidDevice> = Result.success(LinkedKidDevice(HOUSEHOLD, LEO, NEW_DEVICE))
    var createdWith: Currency.Fiat? = null
    var redeemedCode: LinkCodeInput? = null
    val registeredKeys = mutableListOf<DeviceRegistrationMaterial>()

    override suspend fun beginDeviceRegistration(): Result<ByteArray> {
        log += "begin"
        return Result.success(ByteArray(32) { 7 })
    }

    override suspend fun createHousehold(
        fiatCurrency: Currency.Fiat,
        key: DeviceRegistrationMaterial,
    ): Result<RegisteredHousehold> {
        log += "createHousehold"
        createdWith = fiatCurrency
        registeredKeys += key
        return createResult
    }

    override suspend fun registerParentDevice(key: DeviceRegistrationMaterial): Result<PendingParentDevice> {
        log += "registerParentDevice"
        registeredKeys += key
        return Result.success(PendingParentDevice(NEW_DEVICE, displayFingerprint(NEW_DEVICE)))
    }

    override suspend fun redeemLinkCode(code: LinkCodeInput, key: DeviceRegistrationMaterial): Result<LinkedKidDevice> {
        log += "redeemLinkCode"
        redeemedCode = code
        registeredKeys += key
        return redeemResult
    }
}

class FakeOnboardingHousehold(private val log: CallLog, var trial: Boolean = false) : OnboardingHousehold {
    var policyCurrency: Currency.Fiat? = null
    var activeDevice: DeviceId? = null
    var syncs = 0

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
        return true
    }

    override suspend fun activeDeviceOf(uid: UserId, householdId: HouseholdId): DeviceId? = activeDevice
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
