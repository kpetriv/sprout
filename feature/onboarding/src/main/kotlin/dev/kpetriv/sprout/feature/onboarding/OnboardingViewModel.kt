package dev.kpetriv.sprout.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kpetriv.sprout.core.crypto.DeviceKeyStore
import dev.kpetriv.sprout.core.crypto.DeviceRegistrationMaterial
import dev.kpetriv.sprout.core.crypto.PublicKeyBytes
import dev.kpetriv.sprout.core.database.Session
import dev.kpetriv.sprout.core.database.SessionStore
import dev.kpetriv.sprout.core.database.entity.RegistrationStatus
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.toHex
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.model.UserId
import dev.kpetriv.sprout.core.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Onboarding (M3 §1, §6), one step at a time:
 * - **Parent**: sign in, refresh the claims. Without a household: pick the currency, then `beginDeviceRegistration`
 *   → a fresh attested **pending** device key ([DeviceKeyStore.regenerateForRegistration]) → `createHousehold` → the
 *   returned device id must be this key's fingerprint → promote the key → save the ACTIVE PARENT session at once →
 *   create the household policy → claims refresh (a failure is retried without a new key) → the app's PIN and signing
 *   step (a policy lost before it was stored or pushed is recovered after the first pull, with the currency the server
 *   stored at creation, D-REL-14). With a household: a phone the registry already knows is restored, never
 *   re-registered (the local mirror, else the token-less `deviceStatus` for the pending and then the current key:
 *   ACTIVE restores the session, PENDING goes back to waiting); otherwise this device registers PENDING with a
 *   pending key, saves a PENDING session and waits (one sync every [POLL_INTERVAL_MS]; the sync mirrors the registry
 *   into the session) until a parent approves.
 *   Every way into the PIN and signing step from an existing household (restored, approved, resumed) first pulls the
 *   household ([OnboardingStep.FirstSync]), so enrolment finds the household's policy (D-REL-11), and recovers a
 *   policy that exists nowhere through the same checks as Settings (D-REL-24).
 * - **Kid device**: the 8-character code → anonymous sign-in → (an anonymous user already linked whose key the
 *   registry has ACTIVE is restored) → pending key → `redeemLinkCode` → fingerprint check → promote → save the KID
 *   session → claims refresh (best effort) → first sync → kid mode.
 *
 * A registration key is promoted only after the server accepted it, and kept pending when a call fails without an
 * answer, so a registration the server did accept is found again on the next attempt (audit M1). A status that cannot
 * be read stops the flow with a network failure rather than registering over it.
 *
 * When the pre-linking trial household has data, the parent or kid confirms first that it will be deleted
 * ([OnboardingStep.ConfirmPurge]); that screen comes before anything is created on the server, so "Cancel" leaves
 * everything as it was. The purge itself runs once the server side succeeded and before the new session is saved,
 * so the trial outbox is never pushed into the real household.
 *
 * The parent's PIN and signing step is recorded as started ([OnboardingHousehold.startParentSetup]) before its
 * session is saved, so a process death on that step comes back to it. Back is ignored while a call runs. Every exit
 * from a PENDING session (back while waiting, a rejection) erases this phone's copy through the app's removal owner
 * whenever household rows may be on it ([OnboardingHousehold.hasHouseholdData], audit L3); otherwise it drops the
 * session and signs out.
 *
 * Nothing is logged; failures are [OnboardingFailure] codes only.
 */
@HiltViewModel
@Suppress("TooManyFunctions", "LongParameterList") // One function per step; one port per concern.
class OnboardingViewModel @Inject constructor(
    private val auth: OnboardingAuth,
    private val backend: RegistrationBackend,
    private val household: OnboardingHousehold,
    private val keys: DeviceKeyStore,
    private val sessions: SessionStore,
    private val clock: Clock,
) : ViewModel() {
    private val mutableState = MutableStateFlow(OnboardingUiState())
    val state: StateFlow<OnboardingUiState> = mutableState.asStateFlow()

    private var claims: AccountClaims? = null
    private var purgeConfirmed = false
    private var afterPurge: AfterPurge? = null
    private var pendingCurrency: Currency.Fiat? = null
    private var running: Job? = null
    private var waiting: Job? = null

    /** A household created here whose claims refresh has not succeeded yet: "Create" retries only the refresh. */
    private var awaitingClaims: HouseholdId? = null

    /** A sync of the waiting phone succeeded, so household data may be on it. */
    private var pulledWhileWaiting = false

    init {
        viewModelScope.launch { resume() }
    }

    @Suppress("CyclomaticComplexMethod") // One branch per event.
    fun onEvent(event: OnboardingEvent) {
        when (event) {
            OnboardingEvent.ChooseParent -> show(OnboardingStep.ParentSignIn(auth.usesEmailPassword))

            OnboardingEvent.ChooseKidDevice -> run { chooseKidDevice() }

            OnboardingEvent.Back -> back()

            is OnboardingEvent.EmailChanged -> updateSignIn { it.copy(email = event.email.trim()) }

            is OnboardingEvent.PasswordChanged -> updateSignIn { it.copy(password = event.password) }

            OnboardingEvent.SignInWithEmail -> signInWithEmail()

            is OnboardingEvent.SignInWithGoogle -> run {
                auth.signInWithGoogle(event.activityContext).orFail()
                afterSignIn()
            }

            is OnboardingEvent.CurrencyChosen -> mutableState.update { state ->
                val step = state.step as? OnboardingStep.CreateHousehold ?: return@update state
                state.copy(step = step.copy(currency = event.currency))
            }

            OnboardingEvent.CreateHousehold -> createHousehold()

            OnboardingEvent.ConfirmPurge -> confirmPurge()

            is OnboardingEvent.CodeChanged -> mutableState.update { state ->
                if (state.step !is OnboardingStep.KidCode) return@update state
                state.copy(step = OnboardingStep.KidCode(LinkCodeInput.normalise(event.typed)), failure = null)
            }

            OnboardingEvent.SubmitCode -> submitCode()

            OnboardingEvent.ParentLockDone -> parentLockDone()

            OnboardingEvent.DismissFailure -> mutableState.update { it.copy(failure = null) }
        }
    }

    // ---- resuming after a process death ----

    /** A phone that waited for approval goes back to waiting; one on the parent setup step goes back to it. */
    private suspend fun resume() {
        val session = attemptOrNull { sessions.get() }
        if (session == null || session.isLocalTrial || session.role != Role.PARENT) return
        claims = AccountClaims(session.uid, session.householdId, Role.PARENT, null)
        when (session.deviceStatus) {
            RegistrationStatus.PENDING -> waitForApproval(session.householdId, displayFingerprint(session.deviceId))

            RegistrationStatus.ACTIVE -> {
                val pending = attemptOrNull { household.parentSetupPending(session.householdId) } == true
                if (pending) {
                    // The ID token may predate the household's claims (the process died before the refresh).
                    attemptOrNull { auth.refreshClaims() }
                    parentLockAfterPull()
                }
            }

            RegistrationStatus.REVOKED -> Unit
        }
    }

    // ---- parent ----

    private fun signInWithEmail() {
        val step = mutableState.value.step as? OnboardingStep.ParentSignIn ?: return
        if (!step.canSubmit) return
        run {
            auth.signInWithEmailPassword(step.email, step.password).orFail()
            updateSignIn { it.copy(password = "") }
            afterSignIn()
        }
    }

    private suspend fun afterSignIn() {
        val account = auth.refreshClaims().orFail()
        claims = account
        when {
            account.householdId == null -> show(createStep())

            account.role != Role.PARENT -> {
                auth.signOut()
                claims = null
                throw OnboardingException(OnboardingFailure.NOT_ALLOWED)
            }

            needsPurgeConfirmation() -> askToPurge(AfterPurge.SECOND_DEVICE, forKidDevice = false)

            else -> restoreOrRegister()
        }
    }

    private fun createStep(): OnboardingStep.CreateHousehold {
        val default = household.defaultCurrency()
        val options = (listOf(default) + COMMON_CURRENCIES.map(Currency::Fiat)).distinct()
        return OnboardingStep.CreateHousehold(default, options)
    }

    private fun createHousehold() {
        val step = mutableState.value.step as? OnboardingStep.CreateHousehold ?: return
        run {
            when {
                awaitingClaims != null -> finishCreate()

                needsPurgeConfirmation() ->
                    askToPurge(AfterPurge.CREATE, forKidDevice = false, currency = step.currency)

                else -> create(step.currency)
            }
        }
    }

    private suspend fun create(currency: Currency.Fiat) {
        // A household this account already has (an earlier attempt the server completed) is restored, not re-created.
        val account = auth.refreshClaims().orFail()
        claims = account
        if (account.householdId != null) {
            if (account.role != Role.PARENT) throw OnboardingException(OnboardingFailure.NOT_ALLOWED)
            restoreOrRegister()
            return
        }
        val key = pendingKey()
        val created = backend.createHousehold(currency, key).orFail()
        val deviceId = promote(created.deviceId, key)
        purgeTrialDataIfAny()
        // If the policy below is never written (a process death, a failure), a resume or a retried "Create" on this
        // phone recovers it after its first pull, with the currency the server stored at creation (D-REL-14).
        household.startParentSetup(created.householdId)
        // The session first: the policy is signed and published as this session's device, and saving a session of
        // another account clears the local rows (D-M3-47), so the policy cannot come before it.
        household.saveSession(
            Session(account.uid, created.householdId, Role.PARENT, null, deviceId, RegistrationStatus.ACTIVE, now()),
        )
        household.ensureHouseholdPolicy(currency)
        awaitingClaims = created.householdId
        finishCreate()
    }

    /** The claims refresh after `createHousehold`; on failure "Create" runs only this again. */
    private suspend fun finishCreate() {
        val householdId = awaitingClaims ?: return
        val refreshed = auth.refreshClaims().orFail()
        if (refreshed.householdId != householdId) throw OnboardingException(OnboardingFailure.UNKNOWN)
        awaitingClaims = null
        show(OnboardingStep.ParentLock)
    }

    /** A phone the registry already knows is restored; anything else registers as a second parent device. */
    private suspend fun restoreOrRegister() {
        val account = claims ?: throw OnboardingException(OnboardingFailure.UNKNOWN)
        val householdId = account.householdId ?: throw OnboardingException(OnboardingFailure.UNKNOWN)
        if (!restoreParent(account.uid, householdId)) registerSecondDevice(account.uid, householdId)
    }

    /**
     * True when this phone is already registered in [householdId]: its ACTIVE key restores the session, a PENDING one
     * goes back to waiting. A pending key the registry does not have is dropped.
     */
    @Suppress("ReturnCount") // The local mirror, then each key: one return per way it ends.
    private suspend fun restoreParent(uid: UserId, householdId: HouseholdId): Boolean {
        household.activeDeviceOf(uid, householdId)?.let { active ->
            purgeTrialDataIfAny()
            saveActiveParent(uid, householdId, active)
            parentLockAfterPull()
            return true
        }
        for (key in listOf(RegistrationKey.PENDING, RegistrationKey.CURRENT)) {
            val status = backend.deviceStatus(householdId, key).orFail()
            if (status != RegistryStatus.ACTIVE && status != RegistryStatus.PENDING) {
                if (key == RegistrationKey.PENDING) keys.discardPendingKey()
                continue
            }
            val deviceId = if (key == RegistrationKey.PENDING) deviceIdOf(keys.promotePendingKey()) else currentDevice()
            purgeTrialDataIfAny()
            if (status == RegistryStatus.ACTIVE) {
                saveActiveParent(uid, householdId, deviceId)
                // The first pull runs before the PIN and signing step (D-REL-11): see parentLockAfterPull.
                show(OnboardingStep.FirstSync)
                val pulled = confirmRestored(uid, householdId)
                recoverPolicy(pulled)
                show(OnboardingStep.ParentLock)
            } else {
                household.saveSession(
                    Session(uid, householdId, Role.PARENT, null, deviceId, RegistrationStatus.PENDING, now()),
                )
                waitForApproval(householdId, displayFingerprint(deviceId))
            }
            return true
        }
        return false
    }

    private suspend fun saveActiveParent(uid: UserId, householdId: HouseholdId, deviceId: DeviceId) {
        household.startParentSetup(householdId)
        household.saveSession(
            Session(uid, householdId, Role.PARENT, null, deviceId, RegistrationStatus.ACTIVE, now()),
        )
    }

    /**
     * One pull ([OnboardingStep.FirstSync]), then the PIN and signing step (D-REL-11): fingerprint enrolment registers
     * the parent key in the household policy, and a phone that joined or came back to an existing household must have
     * that policy (its currency, its signature setting) before it does, or the enrolment is refused rather than
     * publishing this phone's defaults over it. Offline the pull fails quietly and the PIN can still be set; signing
     * then stays off ("Fingerprint signing is off" on the dashboard once the policy arrives) until the parent turns it
     * on in Settings.
     */
    private suspend fun parentLockAfterPull() {
        show(OnboardingStep.FirstSync)
        val pulled = attemptOrNull { household.syncNow() } == true
        recoverPolicy(pulled)
        show(OnboardingStep.ParentLock)
    }

    /**
     * A household policy that exists nowhere is written again (D-REL-14, audit L1): `createHousehold` stores no policy
     * on the server, so one lost before the creating phone stored or pushed it (a process death between the session
     * and the policy, a write that failed) would be lost for good. Only after a pull that [pulled] the household
     * without an error, and then only through the same checks as every other recovery
     * ([OnboardingHousehold.recoverHouseholdPolicy]: nothing held, nothing on the server, the server's currency).
     * Best effort: the dashboard's "Restore household settings" and the recovery at enrolment cover the rest.
     */
    private suspend fun recoverPolicy(pulled: Boolean) {
        if (pulled) attemptOrNull { household.recoverHouseholdPolicy() }
    }

    /**
     * `deviceStatus` proves the key, not the account: once a sync mirrored the registry, a device that belongs to
     * another parent account is erased again (the rules would refuse its writes anyway). Offline, it is kept. True when
     * the sync completed without an error.
     */
    private suspend fun confirmRestored(uid: UserId, householdId: HouseholdId): Boolean {
        val pulled = attemptOrNull { household.syncNow() } == true
        if (pulled && household.activeDeviceOf(uid, householdId) == null) {
            household.eraseLocalHousehold()
            claims = null
            show(OnboardingStep.Welcome)
            throw OnboardingException(OnboardingFailure.NOT_ALLOWED)
        }
        return pulled
    }

    private suspend fun registerSecondDevice(uid: UserId, householdId: HouseholdId) {
        val key = pendingKey()
        val pending = backend.registerParentDevice(key).orFail()
        val deviceId = promote(pending.deviceId, key)
        purgeTrialDataIfAny()
        household.saveSession(
            Session(uid, householdId, Role.PARENT, null, deviceId, RegistrationStatus.PENDING, now()),
        )
        // The fingerprint both phones compare is computed here from the key, never taken from the server.
        waitForApproval(householdId, displayFingerprint(deviceId))
    }

    private fun waitForApproval(householdId: HouseholdId, fingerprint: String) {
        show(OnboardingStep.WaitingForApproval(fingerprint))
        waiting?.cancel()
        waiting = viewModelScope.launch {
            val poll = launch {
                while (isActive) {
                    if (attemptOrNull { household.syncNow() } == true) pulledWhileWaiting = true
                    delay(POLL_INTERVAL_MS)
                }
            }
            val status = sessions.observe().map { it?.deviceStatus }.first { it != RegistrationStatus.PENDING }
            poll.cancel()
            if (status == RegistrationStatus.ACTIVE) {
                attemptOrNull { household.startParentSetup(householdId) }
                // A PENDING phone cannot read the household's documents: pull them now that it is ACTIVE.
                parentLockAfterPull()
            } else {
                // Revoked while waiting (or the session went): whatever the sync pulled is erased, not kept.
                leavePending(householdId)
                claims = null
                pulledWhileWaiting = false
                mutableState.value = OnboardingUiState(failure = OnboardingFailure.DEVICE_REJECTED)
            }
        }
    }

    private fun parentLockDone() {
        if (mutableState.value.step != OnboardingStep.ParentLock) return
        viewModelScope.launch {
            attemptOrNull { household.finishParentSetup() }
            mutableState.update { if (it.step == OnboardingStep.ParentLock) it.copy(finished = true) else it }
        }
    }

    // ---- kid device ----

    private suspend fun chooseKidDevice() {
        if (needsPurgeConfirmation()) {
            askToPurge(
                AfterPurge.KID_CODE,
                forKidDevice = true,
            )
        } else {
            show(OnboardingStep.KidCode())
        }
    }

    private fun submitCode() {
        val step = mutableState.value.step as? OnboardingStep.KidCode ?: return
        val code = LinkCodeInput.parse(step.code)
        if (code == null) {
            mutableState.update { it.copy(failure = OnboardingFailure.CODE_NOT_ACCEPTED) }
            return
        }
        run { redeem(code) }
    }

    private suspend fun redeem(code: LinkCodeInput) {
        auth.signInAnonymously().orFail()
        var account = auth.refreshClaims().orFail()
        if (account.householdId != null) {
            // An earlier attempt the server completed: restore it if this phone's key is the linked device.
            if (account.role == Role.KID && restoreKid(account)) return
            // Otherwise this anonymous user is spent (one household per user): start again with a fresh one.
            auth.signOut()
            auth.signInAnonymously().orFail()
            account = auth.refreshClaims().orFail()
        }
        val key = pendingKey()
        val linked = backend.redeemLinkCode(code, key).orFail()
        val deviceId = promote(linked.deviceId, key)
        purgeTrialDataIfAny()
        val session =
            Session(account.uid, linked.householdId, Role.KID, linked.kidId, deviceId, RegistrationStatus.ACTIVE, now())
        household.saveSession(session)
        // Best effort: the first sync forces a token refresh anyway.
        attemptOrNull { auth.refreshClaims() }
        firstSync()
    }

    @Suppress("ReturnCount") // No household or kid in the claims, then each key: one return per way it ends.
    private suspend fun restoreKid(account: AccountClaims): Boolean {
        val householdId = account.householdId ?: return false
        val kidId = account.kidId ?: return false
        for (key in listOf(RegistrationKey.PENDING, RegistrationKey.CURRENT)) {
            if (backend.deviceStatus(householdId, key).orFail() != RegistryStatus.ACTIVE) continue
            val deviceId = if (key == RegistrationKey.PENDING) deviceIdOf(keys.promotePendingKey()) else currentDevice()
            purgeTrialDataIfAny()
            household.saveSession(
                Session(account.uid, householdId, Role.KID, kidId, deviceId, RegistrationStatus.ACTIVE, now()),
            )
            firstSync()
            return true
        }
        return false
    }

    private suspend fun firstSync() {
        show(OnboardingStep.FirstSync)
        // A failed first sync is not fatal: the foreground loop and the worker keep trying.
        attemptOrNull { household.syncNow() }
        mutableState.update { it.copy(finished = true) }
    }

    // ---- trial data ----

    private suspend fun needsPurgeConfirmation(): Boolean = !purgeConfirmed && household.hasTrialData()

    private fun askToPurge(next: AfterPurge, forKidDevice: Boolean, currency: Currency.Fiat? = null) {
        afterPurge = next
        pendingCurrency = currency
        show(OnboardingStep.ConfirmPurge(forKidDevice))
    }

    private fun confirmPurge() {
        if (mutableState.value.step !is OnboardingStep.ConfirmPurge) return
        purgeConfirmed = true
        when (afterPurge) {
            AfterPurge.CREATE -> run { create(pendingCurrency ?: household.defaultCurrency()) }
            AfterPurge.SECOND_DEVICE -> run { restoreOrRegister() }
            AfterPurge.KID_CODE -> show(OnboardingStep.KidCode())
            null -> show(OnboardingStep.Welcome)
        }
    }

    private suspend fun purgeTrialDataIfAny() {
        if (household.hasTrialData()) household.purgeTrialData()
    }

    // ---- shared ----

    private fun back() {
        val current = mutableState.value
        // A call in flight (a key being registered) is never abandoned half-way.
        if (current.busy) return
        val step = current.step
        // Once the session is saved the device is set up; only the remaining local step is left.
        if (step == OnboardingStep.ParentLock || step == OnboardingStep.FirstSync) return
        running?.cancel()
        waiting?.cancel()
        val pendingHousehold = claims?.householdId.takeIf { step is OnboardingStep.WaitingForApproval }
        viewModelScope.launch {
            when {
                pendingHousehold != null -> leavePending(pendingHousehold)
                claims != null || step is OnboardingStep.KidCode -> attemptOrNull { auth.signOut() }
            }
            claims = null
        }
        purgeConfirmed = false
        afterPurge = null
        awaitingClaims = null
        pulledWhileWaiting = false
        mutableState.value = OnboardingUiState()
    }

    /**
     * Leaves a PENDING session of [householdId]: when household rows may be on this phone (the polling sync pulled,
     * or it cannot be ruled out), the app's removal owner erases them and signs out; otherwise the session is dropped
     * and the account signed out.
     */
    private suspend fun leavePending(householdId: HouseholdId) {
        val rows = pulledWhileWaiting || attemptOrNull { household.hasHouseholdData(householdId) } != false
        if (rows) {
            attemptOrNull { household.eraseLocalHousehold() }
        } else {
            attemptOrNull { sessions.clear() }
            attemptOrNull { auth.signOut() }
        }
    }

    /** A fresh pending registration key bound to a new server challenge. */
    private suspend fun pendingKey(): DeviceRegistrationMaterial {
        val challenge = backend.beginDeviceRegistration().orFail()
        return keys.regenerateForRegistration(challenge)
    }

    /**
     * The server accepted [key] as [returned]: that must be the key's own fingerprint (audit Low 4), then the key
     * becomes this device's signing key. A mismatch drops the pending key and fails.
     */
    private suspend fun promote(returned: DeviceId, key: DeviceRegistrationMaterial): DeviceId {
        val local = deviceIdOf(key.publicKey)
        if (returned != local) {
            keys.discardPendingKey()
            throw OnboardingException(OnboardingFailure.UNKNOWN)
        }
        // The key promoted must be the one the server accepted (a pending key replaced in between is not).
        if (deviceIdOf(keys.promotePendingKey()) != local) throw OnboardingException(OnboardingFailure.UNKNOWN)
        return local
    }

    private suspend fun currentDevice(): DeviceId = deviceIdOf(keys.deviceSigningKey().publicKey)

    private fun now() = clock.now()

    private fun show(step: OnboardingStep) = mutableState.update { it.copy(step = step, failure = null) }

    private fun updateSignIn(change: (OnboardingStep.ParentSignIn) -> OnboardingStep.ParentSignIn) =
        mutableState.update { state ->
            val step = state.step as? OnboardingStep.ParentSignIn ?: return@update state
            state.copy(step = change(step), failure = null)
        }

    /** Runs one step unless one is running: busy while it runs, any failure becomes [OnboardingUiState.failure]. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // Keystore, storage: shown as UNKNOWN, never logged.
    private fun run(block: suspend () -> Unit) {
        if (running?.isActive == true) return
        mutableState.update { it.copy(busy = true, failure = null) }
        running = viewModelScope.launch {
            val failure = try {
                block()
                null
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failed: OnboardingException) {
                failed.failure
            } catch (unexpected: Exception) {
                OnboardingFailure.UNKNOWN
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
            if (failure != null) mutableState.update { it.copy(failure = failure) }
        }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private suspend fun <T> attemptOrNull(block: suspend () -> T): T? = try {
        block()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failed: Exception) {
        null
    }

    private fun <T> Result<T>.orFail(): T =
        getOrElse { throw it as? OnboardingException ?: OnboardingException(OnboardingFailure.UNKNOWN) }

    private enum class AfterPurge { CREATE, SECOND_DEVICE, KID_CODE }

    private fun deviceIdOf(publicKey: PublicKeyBytes): DeviceId = DeviceId(publicKey.fingerprint().toHex())

    companion object {
        /** How often a phone waiting for approval syncs (the sync mirrors its registry status into the session). */
        const val POLL_INTERVAL_MS: Long = 5_000

        /** Offered in the picker after the locale's currency. */
        val COMMON_CURRENCIES: List<String> = listOf(
            "EUR", "GBP", "USD", "CHF", "SEK", "NOK", "DKK", "PLN", "CZK", "CAD", "AUD", "NZD", "JPY",
        )
    }
}
