package dev.kpetriv.sprout.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kpetriv.sprout.core.crypto.DeviceKeyStore
import dev.kpetriv.sprout.core.crypto.DeviceRegistrationMaterial
import dev.kpetriv.sprout.core.database.Session
import dev.kpetriv.sprout.core.database.SessionStore
import dev.kpetriv.sprout.core.database.entity.RegistrationStatus
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.model.Role
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
 *   → a fresh attested device key ([DeviceKeyStore.regenerateForRegistration]) → `createHousehold` → claims refresh
 *   → save the ACTIVE PARENT session → create the household policy → the app's PIN and signing step. With a
 *   household (a second phone): register this device PENDING, save a PENDING session and wait (one sync every
 *   [POLL_INTERVAL_MS]; the sync mirrors the registry into the session) until a parent approves it. A phone whose
 *   current key is already ACTIVE for that parent (signed out and back in) just restores its session.
 * - **Kid device**: the 8-character code → anonymous sign-in → fresh key → `redeemLinkCode` → claims refresh → save
 *   the KID session → first sync → kid mode.
 *
 * When the pre-linking trial household has data, the parent or kid confirms first that it will be deleted
 * ([OnboardingStep.ConfirmPurge]); that screen comes before anything is created on the server, so "Cancel" leaves
 * everything as it was. The purge itself runs once the server side succeeded and before the new session is saved,
 * so the trial outbox is never pushed into the real household.
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
    private var running: Job? = null
    private var waiting: Job? = null

    init {
        viewModelScope.launch { resumeWaiting() }
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

            OnboardingEvent.ParentLockDone -> mutableState.update {
                if (it.step == OnboardingStep.ParentLock) it.copy(finished = true) else it
            }

            OnboardingEvent.DismissFailure -> mutableState.update { it.copy(failure = null) }
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
        val householdId = account.householdId
        when {
            householdId == null -> show(createStep())

            account.role != Role.PARENT -> {
                auth.signOut()
                claims = null
                throw OnboardingException(OnboardingFailure.NOT_ALLOWED)
            }

            else -> {
                val active = household.activeDeviceOf(account.uid, householdId)
                if (active != null) {
                    saveSession(
                        Session(account.uid, householdId, Role.PARENT, null, active, RegistrationStatus.ACTIVE, now()),
                    )
                    show(OnboardingStep.ParentLock)
                } else if (needsPurgeConfirmation()) {
                    askToPurge(AfterPurge.SECOND_DEVICE, forKidDevice = false)
                } else {
                    registerSecondDevice()
                }
            }
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
            if (needsPurgeConfirmation()) {
                askToPurge(AfterPurge.CREATE, forKidDevice = false, currency = step.currency)
            } else {
                create(step.currency)
            }
        }
    }

    private suspend fun create(currency: Currency.Fiat) {
        val account = claims ?: throw OnboardingException(OnboardingFailure.UNKNOWN)
        val key = freshKey()
        val created = backend.createHousehold(currency, key).orFail()
        val refreshed = auth.refreshClaims().orFail()
        if (refreshed.householdId != created.householdId) throw OnboardingException(OnboardingFailure.UNKNOWN)
        purgeTrialDataIfAny()
        saveSession(
            Session(
                account.uid,
                created.householdId,
                Role.PARENT,
                null,
                created.deviceId,
                RegistrationStatus.ACTIVE,
                now(),
            ),
        )
        household.ensureHouseholdPolicy(currency)
        show(OnboardingStep.ParentLock)
    }

    private suspend fun registerSecondDevice() {
        val account = claims ?: throw OnboardingException(OnboardingFailure.UNKNOWN)
        val householdId = account.householdId ?: throw OnboardingException(OnboardingFailure.UNKNOWN)
        val pending = backend.registerParentDevice(freshKey()).orFail()
        purgeTrialDataIfAny()
        saveSession(
            Session(account.uid, householdId, Role.PARENT, null, pending.deviceId, RegistrationStatus.PENDING, now()),
        )
        waitForApproval(pending.fingerprint)
    }

    /** A process restarted while this phone waited for approval goes straight back to waiting. */
    private suspend fun resumeWaiting() {
        val session = attemptOrNull { sessions.get() }
        if (session == null || !session.waitsForApproval()) return
        claims = AccountClaims(session.uid, session.householdId, Role.PARENT, null)
        waitForApproval(displayFingerprint(session.deviceId))
    }

    private fun Session.waitsForApproval(): Boolean =
        !isLocalTrial && role == Role.PARENT && deviceStatus == RegistrationStatus.PENDING

    private fun waitForApproval(fingerprint: String) {
        show(OnboardingStep.WaitingForApproval(fingerprint))
        waiting?.cancel()
        waiting = viewModelScope.launch {
            val poll = launch {
                while (isActive) {
                    attemptOrNull { household.syncNow() }
                    delay(POLL_INTERVAL_MS)
                }
            }
            val status = sessions.observe().map { it?.deviceStatus }.first { it != RegistrationStatus.PENDING }
            poll.cancel()
            if (status == RegistrationStatus.ACTIVE) {
                show(OnboardingStep.ParentLock)
            } else {
                attemptOrNull { sessions.clear() }
                attemptOrNull { auth.signOut() }
                claims = null
                mutableState.value = OnboardingUiState(failure = OnboardingFailure.DEVICE_REJECTED)
            }
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
        val linked = backend.redeemLinkCode(code, freshKey()).orFail()
        val account = auth.refreshClaims().orFail()
        if (account.householdId != linked.householdId) throw OnboardingException(OnboardingFailure.UNKNOWN)
        purgeTrialDataIfAny()
        saveSession(
            Session(
                account.uid,
                linked.householdId,
                Role.KID,
                linked.kidId,
                linked.deviceId,
                RegistrationStatus.ACTIVE,
                now(),
            ),
        )
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

    private var pendingCurrency: Currency.Fiat? = null

    private fun confirmPurge() {
        if (mutableState.value.step !is OnboardingStep.ConfirmPurge) return
        purgeConfirmed = true
        when (afterPurge) {
            AfterPurge.CREATE -> run { create(pendingCurrency ?: household.defaultCurrency()) }
            AfterPurge.SECOND_DEVICE -> run { registerSecondDevice() }
            AfterPurge.KID_CODE -> show(OnboardingStep.KidCode())
            null -> show(OnboardingStep.Welcome)
        }
    }

    private suspend fun purgeTrialDataIfAny() {
        if (household.hasTrialData()) household.purgeTrialData()
    }

    // ---- shared ----

    private fun back() {
        val step = mutableState.value.step
        // Once the session is saved the device is set up; only the remaining local step is left.
        if (step == OnboardingStep.ParentLock || step == OnboardingStep.FirstSync) return
        running?.cancel()
        waiting?.cancel()
        val waitingForApproval = step is OnboardingStep.WaitingForApproval
        viewModelScope.launch {
            if (waitingForApproval) attemptOrNull { sessions.clear() }
            if (claims != null || step is OnboardingStep.KidCode) attemptOrNull { auth.signOut() }
            claims = null
        }
        purgeConfirmed = false
        afterPurge = null
        mutableState.value = OnboardingUiState()
    }

    private suspend fun freshKey(): DeviceRegistrationMaterial {
        val challenge = backend.beginDeviceRegistration().orFail()
        return keys.regenerateForRegistration(challenge)
    }

    private suspend fun saveSession(session: Session) = sessions.save(session)

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

    companion object {
        /** How often a phone waiting for approval syncs (the sync mirrors its registry status into the session). */
        const val POLL_INTERVAL_MS: Long = 5_000

        /** Offered in the picker after the locale's currency. */
        val COMMON_CURRENCIES: List<String> = listOf(
            "EUR", "GBP", "USD", "CHF", "SEK", "NOK", "DKK", "PLN", "CZK", "CAD", "AUD", "NZD", "JPY",
        )
    }
}
