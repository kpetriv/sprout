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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.time.Instant

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

    /** What a re-authentication answers (another account: WRONG_ACCOUNT). */
    var reauthResult: Result<Unit> = Result.success(Unit)

    override suspend fun reauthenticateWithGoogle(activityContext: Context): Result<Unit> {
        log += "reauthenticateWithGoogle"
        return reauthResult
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

    /** The claims the last refresh returned: the signed-in account. */
    var lastClaims: AccountClaims? = null

    override suspend fun refreshClaims(): Result<AccountClaims> {
        log += "refreshClaims"
        val next = if (claims.size > 1) claims.removeAt(0) else claims.first()
        lastClaims = next
        return Result.success(next)
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

    /** What [hasHouseholdData] answers: rows a sync (here or before a restart) may have left. */
    var householdData = false

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
        ensureFailure?.let { throw it }
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

    /**
     * The currency `createHousehold` stored on the server's household root, and whether the server holds a policy
     * document: what the app's recovery reads (D-REL-14). Null: the household has no readable root.
     */
    var serverCurrency: Currency.Fiat? = null
    var serverPolicyStored = false

    /** True while a pulled policy is held for its author on this phone. */
    var policyHeld = false

    /** How many times [recoverHouseholdPolicy] was asked (the app's recovery syncs again, then reads the server). */
    var recoveryChecks = 0

    /** When set, [ensureHouseholdPolicy] throws it (a write that fails, or the process dying there). */
    var ensureFailure: Exception? = null

    /**
     * The app's recovery: no row, nothing held, nothing on the server and a currency there; then the policy with the
     * server's currency. Logged as "recoverPolicy" only when it writes.
     */
    override suspend fun recoverHouseholdPolicy(): Boolean {
        recoveryChecks += 1
        val currency = serverCurrency?.takeUnless { serverPolicyStored || policyHeld || policyCurrency != null }
            ?: return false
        log += "recoverPolicy"
        ensureFailure?.let { throw it }
        policyCurrency = currency
        return true
    }

    override suspend fun startParentSetup(householdId: HouseholdId) {
        log += "setupStarted"
        setupPending = householdId
    }

    override suspend fun finishParentSetup() {
        log += "setupFinished"
        setupPending = null
    }

    override suspend fun parentSetupPending(householdId: HouseholdId): Boolean = setupPending == householdId

    override suspend fun hasHouseholdData(householdId: HouseholdId): Boolean = householdData

    override suspend fun eraseLocalHousehold() {
        log += "erase"
        sessions.session.value = null
    }

    override suspend fun eraseForAnotherAccount() {
        log += "eraseAnotherAccount"
        sessions.session.value = null
    }

    /** Who each registry row belongs to ([registeredOwner]); null: no row. Tests set it to the signed-in account. */
    var ownerOf: (DeviceId) -> RegisteredOwner? = { null }

    /** What [registeredOwner] fails with, if anything (offline). */
    var ownerFailure: Throwable? = null

    /** The rows read, with the calls logged before each read (kept out of the log, whose order other tests check). */
    val ownerReads = mutableListOf<Pair<DeviceId, List<String>>>()

    override suspend fun registeredOwner(householdId: HouseholdId, deviceId: DeviceId): Result<RegisteredOwner?> {
        ownerReads += deviceId to log.calls.toList()
        ownerFailure?.let { return Result.failure(it) }
        return Result.success(ownerOf(deviceId))
    }

    /** What [forgetChatKey] throws, if anything. */
    var forgetFailure: Throwable? = null

    /** The calls logged before each [forgetChatKey] (kept out of the log, whose exact order other tests check). */
    val chatKeyForgotten = mutableListOf<List<String>>()

    override suspend fun forgetChatKey() {
        forgetFailure?.let { throw it }
        chatKeyForgotten += log.calls.toList()
    }

    /** The device ids whose stashed chat key was put back ([restoreChatKey]). */
    val chatKeysRestored = mutableListOf<DeviceId>()

    override suspend fun restoreChatKey(deviceId: DeviceId) {
        chatKeysRestored += deviceId
    }

    /** How often the stash was dropped after a promotion. */
    var stashesDropped = 0

    override suspend fun dropStashedChatKey() {
        stashesDropped += 1
    }
}

class FakeSessionStore(private val log: CallLog, initial: Session? = null) : SessionStore {
    val session = MutableStateFlow(initial)

    override fun observe(): Flow<Session?> = session

    /** When set, [get] throws it (storage trouble); [observe] goes on. */
    var getFailure: Exception? = null

    override suspend fun get(): Session? {
        getFailure?.let { throw it }
        return session.value
    }

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

/** The end of a recovery wait started at [FakeClock]'s start (the epoch): 7 days later. */
val WAIT_ENDS: Instant = Instant.fromEpochMilliseconds(7L * 24 * 60 * 60 * 1000)

/** Recorded check value parameters this phone can compute. */
fun pinParameters(supported: Boolean = true) =
    RecoveryPinParameters(ByteArray(16) { 1 }, "argon2id\$v=19\$m=65536,t=3,p=1", supported)

/** A restore's answer: [attemptsLeft] of the PIN's budget, sessions revoked, admitted by [method]. */
fun restored(attemptsLeft: Int = 10, method: RecoveryChoice = RecoveryChoice.PIN): Result<RecoveredDevice> =
    Result.success(RecoveredDevice(attemptsLeft, signInAgain = true, method = method))

/**
 * The recovery callables of the waiting phone. [options] answers `recoveryStatus` (no wait asked for by default);
 * [answers] answer successive `recoverParentDevice` calls (the last one repeats, a restore when empty). The "verifier"
 * is the PIN's bytes, so tests see which PIN a call carried; [verifiers] are what each call received, [pins] the arrays
 * derived from. [signInAgain] is the app's "Sign in again" path: [signInGate], when set, holds it (the parent has not
 * signed in yet), and [signInResult] is what it returns.
 */
class FakeAccountRecovery(private val log: CallLog? = null) : AccountRecovery {
    var options: Result<RecoveryOptions> = Result.success(RecoveryOptions(pinParameters(), 10, waitEndsAt = null))
    val answers = ArrayDeque<Result<RecoveredDevice>>()
    val statusCalls = mutableListOf<Unit>()
    val pins = mutableListOf<CharArray>()
    val verifiers = mutableListOf<String?>()
    var signInGate: CompletableDeferred<Unit>? = null
    var signInResult = true

    override suspend fun status(): Result<RecoveryOptions> {
        statusCalls += Unit
        return options
    }

    override suspend fun verifierFor(pin: CharArray, parameters: RecoveryPinParameters): Result<ByteArray> {
        pins += pin
        return Result.success(String(pin).encodeToByteArray())
    }

    /** Runs inside `recoverParentDevice` before it answers (the server admitted the phone, say). */
    var onRecover: () -> Unit = {}

    /** When set, `recoverParentDevice` waits for it before it answers. */
    var recoverGate: CompletableDeferred<Unit>? = null

    override suspend fun recover(verifier: ByteArray?): Result<RecoveredDevice> {
        verifiers += verifier?.decodeToString()
        onRecover()
        recoverGate?.await()
        return if (answers.size > 1) answers.removeFirst() else answers.firstOrNull() ?: restored()
    }

    override suspend fun signInAgain(): Boolean {
        log?.plusAssign("signInAgain")
        signInGate?.await()
        return signInResult
    }
}

/** The lock feature's PIN adoption: records each PIN it was handed (zeroing the array as the real one does). */
class FakeRecoveredPinKeeper(private val log: CallLog) : RecoveredPinKeeper {
    val kept = mutableListOf<String>()
    var keeps = true

    /** A local PIN on this phone (a trial's), until [forgetForNewDevice] removes it. */
    var localPin: String? = null

    /** How often the PIN was removed for a new PENDING registration (not logged: other tests check the log). */
    var forgotten = 0

    /** When set, [forgetForNewDevice] throws it. */
    var forgetFailure: Exception? = null

    override suspend fun forgetForNewDevice() {
        forgetFailure?.let { throw it }
        forgotten += 1
        localPin = null
    }

    override suspend fun keep(pin: CharArray): Boolean {
        log += "keepPin"
        kept += String(pin)
        localPin = String(pin)
        pin.fill('\u0000')
        return keeps
    }
}

fun refused(problem: RecoveryProblem): Result<RecoveredDevice> = Result.failure(RecoveryException(problem))
