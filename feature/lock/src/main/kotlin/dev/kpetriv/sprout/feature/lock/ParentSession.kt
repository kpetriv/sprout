package dev.kpetriv.sprout.feature.lock

import dev.kpetriv.sprout.core.common.Dispatcher
import dev.kpetriv.sprout.core.common.SproutDispatchers
import dev.kpetriv.sprout.core.crypto.BootClock
import dev.kpetriv.sprout.core.crypto.LockoutDecision
import dev.kpetriv.sprout.core.crypto.ParentAuthAvailability
import dev.kpetriv.sprout.core.crypto.ParentAuthorizer
import dev.kpetriv.sprout.core.crypto.ParentKeyInvalidatedException
import dev.kpetriv.sprout.core.crypto.ParentSigner
import dev.kpetriv.sprout.core.crypto.PinHasher
import dev.kpetriv.sprout.core.crypto.PinLockoutPolicy
import dev.kpetriv.sprout.core.crypto.verifyParentSignature
import dev.kpetriv.sprout.core.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.security.Signature
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** The long-lived scope [ParentSession] runs its lifecycle observation and auto-lock timer in. */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class LockScope

/**
 * The app-level parent gate (M2 "feature/lock"): whether the parent area is open, and the only way to open it.
 *
 * - **PIN** ([unlockWithPin]): the [PinLockoutPolicy] decision is checked first and the PIN is only verified on
 *   [LockoutDecision.Allowed]; [PinHasher.verify] compares in constant time. Success clears the failure count and
 *   re-hashes a hash with weaker parameters; failure is recorded and the next decision returned. Attempts are
 *   serialised, so two quick submissions cannot both pass one "allowed" check. A retired `v1` hash is never
 *   verified ([dev.kpetriv.sprout.core.crypto.UnsupportedPinHashException]) and reads as
 *   [PinUnlockResult.Unavailable].
 * - **Biometric** ([unlockWithBiometric] then [completeBiometricUnlock]): offered only when a parent key is
 *   registered for the household ([ParentKeyRegistry]) and this device's key byte-equals it; the challenge
 *   signature is verified against the **registered** key. Anything else fails closed to the PIN. This session
 *   never creates a parent key: only the PIN-gated [ParentKeyEnrolment] and [ParentKeyRenewal] do. A biometric
 *   unlock also clears the lockout, including the [LockoutDecision.RequireBiometric] cap (ADR-0004 section 3).
 * - **Auto-lock**: when the app leaves the foreground the session locks at once if [LockSettings.lockWhenLeaving]
 *   is on (the default), otherwise after [AUTO_LOCK_AFTER] in the background, measured with [BootClock] so moving
 *   the wall clock changes nothing. The background timer locks even if the app never returns; coming back after
 *   the limit (or after a reboot) locks too, in case the timer did not run.
 *
 * - **Account recovery** (ADR-0013 §1): the PIN set up, changed or entered correctly goes (as a copy) to
 *   [RecoveryPinRegistration], which registers the server's check value in the background; nothing waits for it.
 *   [adoptRecoveredPin] makes a PIN restored from the server this phone's PIN; [registerRecoveryPin] is Settings'
 *   "finish setting up".
 *
 * Any storage or Keystore failure keeps the session locked. The session is only the UX gate: money events still
 * need their own per-event parent authorisation (`ParentAuthorizationRequester`).
 *
 * PINs are passed as [CharArray] and zeroed by the session before it returns.
 */
@Singleton
@Suppress("LongParameterList", "TooManyFunctions") // Collaborators, all injected; one function per lock action.
class ParentSession @Inject constructor(
    private val pinStore: ParentPinStore,
    private val hasher: PinHasher,
    private val lockout: PinLockoutPolicy,
    private val authorizer: ParentAuthorizer,
    private val registry: ParentKeyRegistry,
    private val keyStatus: ParentKeyStatusCheck,
    private val signer: ParentSigner,
    private val settings: LockSettings,
    private val clock: Clock,
    private val bootClock: BootClock,
    private val random: SecureRandom,
    private val recoveryPin: RecoveryPinRegistration,
    appForeground: AppForegroundSource,
    @LockScope private val scope: CoroutineScope,
    @Dispatcher(SproutDispatchers.IO) private val ioDispatcher: CoroutineDispatcher,
) {
    private val mutableState = MutableStateFlow<ParentSessionState>(ParentSessionState.Initializing)

    /** The current session state; starts at [ParentSessionState.Initializing]. */
    val state: StateFlow<ParentSessionState> = mutableState.asStateFlow()

    /** Serialises everything that reads or changes the PIN, the lockout or the state. */
    private val mutex = Mutex()

    /** Guards [backgroundedAt] and [autoLockJob]; foreground transitions arrive on the [scope]'s dispatcher. */
    private val lifecycleLock = Any()
    private var backgroundedAt: BackgroundMark? = null
    private var autoLockJob: Job? = null

    init {
        scope.launch { mutex.withLock { ensureInitialized() } }
        scope.launch {
            appForeground.isInForeground.collect { inForeground ->
                if (inForeground) onForeground() else onBackground()
            }
        }
    }

    /** Whether a strong biometric is usable at all right now (for the D-M2-1 signature banner). */
    fun biometricAvailability(): ParentAuthAvailability = authorizer.availability()

    /**
     * Whether this device's parent key can sign for the household: [ParentKeyStatus.READY] means "offer the
     * fingerprint", [ParentKeyStatus.NEEDS_RENEWAL] means "offer renewal". Null when the registry cannot be read
     * (offer neither; the PIN still works).
     */
    @Suppress("TooGenericExceptionCaught") // Any registry failure means "no biometric offer", never a crash.
    suspend fun parentKeyStatus(): ParentKeyStatus? = try {
        keyStatus.status()
    } catch (e: CancellationException) {
        throw e
    } catch (expected: Exception) {
        null
    }

    /** The lockout decision for the PIN screen, or null when the lockout state cannot be read (stay locked). */
    suspend fun lockoutDecision(): LockoutDecision? = try {
        lockout.decision()
    } catch (expected: GeneralSecurityException) {
        null
    } catch (expected: IOException) {
        null
    }

    /**
     * Unlocks with [pin] if the lockout allows an attempt and the PIN matches. Zeroes [pin]. See the class comment.
     */
    suspend fun unlockWithPin(pin: CharArray): PinUnlockResult = try {
        mutex.withLock {
            ensureInitialized()
            if (mutableState.value == ParentSessionState.SetupRequired) {
                PinUnlockResult.SetupRequired
            } else {
                attemptPin(pin)
            }
        }
    } catch (expected: GeneralSecurityException) {
        PinUnlockResult.Unavailable
    } catch (expected: IOException) {
        PinUnlockResult.Unavailable
    } catch (expected: IllegalArgumentException) {
        // PinHasher.verify: the stored hash is malformed or uses an unknown algorithm. Corrupt storage, not a guess.
        PinUnlockResult.Unavailable
    } finally {
        pin.fill(ZERO_CHAR)
    }

    /** Caller holds [mutex]. */
    private suspend fun attemptPin(pin: CharArray): PinUnlockResult = when (val check = checkPin(pin)) {
        is PinCheck.Verified -> {
            rehashIfNeeded(pin, check.storedHash)
            unlock(UnlockMethod.PIN)
            // ADR-0013 §1: a correct PIN registers the server's check value if it is not known to match yet.
            recoveryPin.pinVerified(pin.copyOf())
            PinUnlockResult.Unlocked
        }

        is PinCheck.Wrong -> PinUnlockResult.WrongPin(check.next)

        is PinCheck.LockedOut -> PinUnlockResult.LockedOut(check.decision)

        PinCheck.NoPin -> {
            mutableState.value = ParentSessionState.SetupRequired
            PinUnlockResult.SetupRequired
        }
    }

    /**
     * Checks [pin] against the stored hash under the [PinLockoutPolicy], recording the success or failure. The one
     * place a PIN is compared. Caller holds [mutex]. Throws on storage, Keystore or hash-format failures.
     */
    private suspend fun checkPin(pin: CharArray): PinCheck {
        val decision = lockout.decision()
        val stored = if (decision == LockoutDecision.Allowed) pinStore.storedHash() else null
        return when {
            decision != LockoutDecision.Allowed -> PinCheck.LockedOut(decision)

            stored == null -> PinCheck.NoPin

            hasher.verify(pin, stored) -> {
                lockout.registerSuccess()
                PinCheck.Verified(stored)
            }

            else -> PinCheck.Wrong(lockout.registerFailure())
        }
    }

    /**
     * True while the parent area is unlocked. Reads the state only: no PIN is checked and no attempt is counted. A key
     * change asks it before its network work (D-REL-15, audit L3), so a locked session is refused at once.
     */
    internal suspend fun isUnlocked(): Boolean = mutex.withLock {
        ensureInitialized()
        mutableState.value is ParentSessionState.Unlocked
    }

    /**
     * A fresh PIN verification for a parent-key change ([ParentKeyEnrolment], [ParentKeyRenewal]): only inside an
     * unlocked session, under the same [PinLockoutPolicy] as an unlock (a wrong PIN counts as a failed attempt).
     * The session state does not change. Zeroes [pin].
     */
    internal suspend fun verifyPinForKeyChange(pin: CharArray): KeyChangePinCheck = try {
        mutex.withLock {
            ensureInitialized()
            if (mutableState.value !is ParentSessionState.Unlocked) {
                KeyChangePinCheck.NotAllowed
            } else {
                when (val check = checkPin(pin)) {
                    is PinCheck.Verified -> KeyChangePinCheck.Verified.also { recoveryPin.pinVerified(pin.copyOf()) }
                    is PinCheck.Wrong -> KeyChangePinCheck.Wrong(check.next)
                    is PinCheck.LockedOut -> KeyChangePinCheck.LockedOut(check.decision)
                    PinCheck.NoPin -> KeyChangePinCheck.NotAllowed
                }
            }
        }
    } catch (expected: GeneralSecurityException) {
        KeyChangePinCheck.Unavailable
    } catch (expected: IOException) {
        KeyChangePinCheck.Unavailable
    } catch (expected: IllegalArgumentException) {
        // Malformed, unknown or retired (v1) stored hash.
        KeyChangePinCheck.Unavailable
    } finally {
        pin.fill(ZERO_CHAR)
    }

    /** Upgrades a hash with weaker parameters after a successful verify. Best effort: the old hash keeps working. */
    private suspend fun rehashIfNeeded(pin: CharArray, stored: String) {
        if (!hasher.needsRehash(stored)) return
        try {
            pinStore.save(hasher.hash(pin))
        } catch (expected: GeneralSecurityException) {
            // Keep the old hash; the next unlock tries again.
        } catch (expected: IOException) {
            // Same.
        }
    }

    /**
     * Sets the first parent PIN and unlocks. Only allowed while [ParentSessionState.SetupRequired]. Zeroes [pin]
     * and [confirmation].
     */
    suspend fun setupPin(pin: CharArray, confirmation: CharArray): PinSetupResult = try {
        val violation = PinRules.validate(pin)
        when {
            violation != null -> PinSetupResult.Invalid(violation)
            !pin.contentEquals(confirmation) -> PinSetupResult.Mismatch
            else -> mutex.withLock { savePin(pin) }
        }
    } catch (expected: GeneralSecurityException) {
        PinSetupResult.Failed
    } catch (expected: IOException) {
        PinSetupResult.Failed
    } finally {
        pin.fill(ZERO_CHAR)
        confirmation.fill(ZERO_CHAR)
    }

    /** Caller holds [mutex]. */
    private suspend fun savePin(pin: CharArray): PinSetupResult {
        ensureInitialized()
        return when {
            mutableState.value != ParentSessionState.SetupRequired -> PinSetupResult.NotAllowed

            pinStore.hasPin() -> {
                mutableState.value = ParentSessionState.Locked
                PinSetupResult.NotAllowed
            }

            else -> {
                val token = recoveryPin.beforePinChange(samePin = false)
                pinStore.save(hasher.hash(pin))
                lockout.registerSuccess()
                unlock(UnlockMethod.PIN_SETUP)
                recoveryPin.afterPinChange(pin.copyOf(), token)
                PinSetupResult.Saved
            }
        }
    }

    /**
     * Changes the parent PIN from inside an unlocked session. [new] must pass [PinRules] and equal [confirm]
     * (checked first, so a typo never costs a lockout attempt); then [current] is verified under the same
     * [PinLockoutPolicy] as an unlock, because checking it is a PIN guess like any other. A wrong [current] counts
     * as a failed attempt; a right one clears the failure count, and the new PIN's hash replaces the old one. The
     * session stays unlocked whatever the outcome. Zeroes all three arrays.
     */
    suspend fun changePin(current: CharArray, new: CharArray, confirm: CharArray): ChangePinResult = try {
        val violation = PinRules.validate(new)
        when {
            violation != null -> ChangePinResult.Invalid(violation)
            !new.contentEquals(confirm) -> ChangePinResult.Mismatch
            else -> mutex.withLock { replacePin(current, new) }
        }
    } catch (expected: GeneralSecurityException) {
        ChangePinResult.Failed
    } catch (expected: IOException) {
        ChangePinResult.Failed
    } catch (expected: IllegalArgumentException) {
        // PinHasher.verify: the stored hash is malformed or uses an unknown algorithm.
        ChangePinResult.Failed
    } finally {
        current.fill(ZERO_CHAR)
        new.fill(ZERO_CHAR)
        confirm.fill(ZERO_CHAR)
    }

    /** Caller holds [mutex]. */
    private suspend fun replacePin(current: CharArray, new: CharArray): ChangePinResult {
        ensureInitialized()
        if (mutableState.value !is ParentSessionState.Unlocked) return ChangePinResult.NotAllowed
        return when (val check = checkPin(current)) {
            is PinCheck.Verified -> {
                // "Changing" the PIN to the PIN it is must not hand a guesser ten new attempts (ADR-0013 §1).
                val token = recoveryPin.beforePinChange(samePin = sameDigits(current, new))
                pinStore.save(hasher.hash(new))
                recoveryPin.afterPinChange(new.copyOf(), token)
                ChangePinResult.Changed
            }

            is PinCheck.Wrong -> ChangePinResult.WrongCurrentPin(check.next)

            is PinCheck.LockedOut -> ChangePinResult.LockedOut(check.decision)

            // Unlocked without a stored PIN cannot happen; refuse rather than set a PIN without the old one.
            PinCheck.NoPin -> ChangePinResult.Failed
        }
    }

    /**
     * ADR-0013 §3: after a restore whose answer says `method: "PIN"`, the PIN the parent just typed becomes this
     * phone's PIN, and the session unlocks. The phone has no PIN of its own by then: registering as a PENDING device
     * removed any ([forgetPinForNewDevice]); a PIN found anyway, a state other than
     * [ParentSessionState.SetupRequired], or a PIN that breaks [PinRules] is [RecoveredPinResult.NotAllowed], and the
     * lock step asks for a PIN. The recovery marks stay cleared (no "registered", no "new PIN not uploaded"): the next
     * correct PIN entry registers the PIN with `newPin: false`, which the server stores, so even an answer that
     * compared no PIN ends with the phone's PIN and the server's value the same. Zeroes [pin].
     */
    suspend fun adoptRecoveredPin(pin: CharArray): RecoveredPinResult = try {
        if (PinRules.validate(pin) != null) {
            RecoveredPinResult.NotAllowed
        } else {
            mutex.withLock {
                ensureInitialized()
                if (mutableState.value != ParentSessionState.SetupRequired || pinStore.hasPin()) {
                    RecoveredPinResult.NotAllowed
                } else {
                    recoveryPin.forget()
                    pinStore.save(hasher.hash(pin))
                    lockout.registerSuccess()
                    unlock(UnlockMethod.PIN_SETUP)
                    RecoveredPinResult.Adopted
                }
            }
        }
    } catch (expected: GeneralSecurityException) {
        RecoveredPinResult.Failed
    } catch (expected: IOException) {
        RecoveredPinResult.Failed
    } finally {
        pin.fill(ZERO_CHAR)
    }

    /**
     * A phone about to register as a new PENDING parent device (ADR-0013 §1, app audit L1): any parent PIN already here
     * (only a debug or benchmark build's local trial can have one), its lockout state and the recovery marks are
     * removed, so a restore with the PIN adopts the PIN the server accepted and every other admission chooses its PIN
     * after it. Throws when the PIN cannot be removed: the registration must not go on then.
     */
    suspend fun forgetPinForNewDevice() {
        mutex.withLock {
            pinStore.clear()
            // A delete that failed quietly (AtomicFile) must not let the registration go on with the PIN still here.
            if (pinStore.hasPin()) throw IOException("The parent PIN could not be removed")
            lockout.registerSuccess()
            recoveryPin.forget()
            mutableState.value = ParentSessionState.SetupRequired
        }
    }

    /**
     * Settings' "finish setting up account recovery" (ADR-0013 §1): only inside an unlocked session, [pin] is checked
     * under the same [PinLockoutPolicy] as an unlock (a wrong PIN counts), then its check value is uploaded and the
     * result returned. The session state does not change. Zeroes [pin].
     */
    suspend fun registerRecoveryPin(pin: CharArray): RecoveryPinSetupResult = try {
        val check = mutex.withLock {
            ensureInitialized()
            if (mutableState.value !is ParentSessionState.Unlocked) null else checkPin(pin)
        }
        when (check) {
            null, PinCheck.NoPin -> RecoveryPinSetupResult.NotAllowed
            is PinCheck.Wrong -> RecoveryPinSetupResult.WrongPin(check.next)
            is PinCheck.LockedOut -> RecoveryPinSetupResult.LockedOut(check.decision)
            is PinCheck.Verified -> recoveryPin.registerNow(pin.copyOf()).toSetupResult()
        }
    } catch (expected: GeneralSecurityException) {
        RecoveryPinSetupResult.Unavailable
    } catch (expected: IOException) {
        RecoveryPinSetupResult.Unavailable
    } catch (expected: IllegalArgumentException) {
        RecoveryPinSetupResult.Unavailable
    } finally {
        pin.fill(ZERO_CHAR)
    }

    /**
     * Prepares a biometric unlock bound to the **registered** parent key. Never creates a key. The UI shows the
     * prompt for a [BiometricUnlockRequest.Ready] and passes its result to [completeBiometricUnlock].
     */
    suspend fun unlockWithBiometric(): BiometricUnlockRequest {
        val availability = authorizer.availability()
        val setupRequired = mutex.withLock {
            ensureInitialized()
            mutableState.value == ParentSessionState.SetupRequired
        }
        return when {
            setupRequired -> BiometricUnlockRequest.SetupRequired

            availability != ParentAuthAvailability.STRONG_BIOMETRIC_AVAILABLE ->
                BiometricUnlockRequest.Unavailable(availability)

            else -> prepareBiometric()
        }
    }

    /**
     * Fails closed: no registered key means no biometric unlock ([BiometricUnlockRequest.NotEnrolled]); a local key
     * that is missing, different or invalidated means renewal ([BiometricUnlockRequest.KeyInvalidated]).
     */
    @Suppress("TooGenericExceptionCaught") // Registry read failures of any kind fall back to the PIN.
    private suspend fun prepareBiometric(): BiometricUnlockRequest = try {
        val registered = registry.registeredParentKey()
        when {
            registered == null -> BiometricUnlockRequest.NotEnrolled

            authorizer.existingParentPublicKey() != registered -> BiometricUnlockRequest.KeyInvalidated

            else -> {
                val nonce = ByteArray(CHALLENGE_SIZE).also(random::nextBytes)
                BiometricUnlockRequest.Ready(authorizer.initSignature(), UNLOCK_CHALLENGE_PREFIX + nonce, registered)
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (expected: ParentKeyInvalidatedException) {
        BiometricUnlockRequest.KeyInvalidated
    } catch (expected: IllegalStateException) {
        // initSignature: the key disappeared between the two calls.
        BiometricUnlockRequest.KeyInvalidated
    } catch (expected: Exception) {
        // Keystore refused for another reason, or the registry cannot be read.
        BiometricUnlockRequest.Failed
    }

    /**
     * Finishes [request] with the signature from the successful prompt (`result.cryptoObject?.signature`). Signs
     * the request's challenge with it and verifies the signature against the registered parent key; only then
     * unlocks and clears the lockout. A missing, foreign or reused signature is [BiometricUnlockResult.REJECTED].
     */
    suspend fun completeBiometricUnlock(
        request: BiometricUnlockRequest.Ready,
        authenticated: Signature?,
    ): BiometricUnlockResult {
        val verified = authenticated != null &&
            authenticated === request.signature &&
            request.markUsed() &&
            provesParentPresence(request, authenticated)
        if (!verified) return BiometricUnlockResult.REJECTED
        return mutex.withLock {
            ensureInitialized()
            if (mutableState.value == ParentSessionState.SetupRequired) {
                BiometricUnlockResult.REJECTED
            } else {
                clearLockoutBestEffort()
                unlock(UnlockMethod.BIOMETRIC)
                BiometricUnlockResult.UNLOCKED
            }
        }
    }

    private suspend fun provesParentPresence(request: BiometricUnlockRequest.Ready, authenticated: Signature) = try {
        // Keystore signing is blocking IPC.
        val signed = withContext(ioDispatcher) { signer.sign(authenticated, request.challenge) }
        verifyParentSignature(request.publicKey, request.challenge, signed)
    } catch (e: CancellationException) {
        throw e
    } catch (expected: GeneralSecurityException) {
        false
    } catch (expected: IllegalArgumentException) {
        false
    }

    /** The parent proved presence with a strong biometric, so unlock even if the lockout state cannot be written. */
    private suspend fun clearLockoutBestEffort() {
        try {
            lockout.registerBiometricSuccess()
        } catch (expected: GeneralSecurityException) {
            // The next PIN attempt still sees the old count; biometric unlock keeps working.
        } catch (expected: IOException) {
            // Same.
        }
    }

    /**
     * Forgets what the session knew about the stored PIN and reads it again, locked: the app calls it after deleting a
     * previous parent's PIN and lockout state, when another account's household replaced the local rows (audit L10),
     * so the next parent sets their own PIN instead of meeting the old one.
     */
    suspend fun reload() {
        mutex.withLock {
            mutableState.value = ParentSessionState.Initializing
            ensureInitialized()
        }
    }

    /** Locks the parent area now. No effect unless unlocked. */
    fun lock() {
        mutableState.update { if (it is ParentSessionState.Unlocked) ParentSessionState.Locked else it }
    }

    private fun unlock(method: UnlockMethod) {
        mutableState.value = ParentSessionState.Unlocked(clock.now(), method)
    }

    /** Caller holds [mutex]. Corrupt or unreadable storage counts as "a PIN exists", so it never offers setup. */
    private suspend fun ensureInitialized() {
        if (mutableState.value != ParentSessionState.Initializing) return
        val hasPin = try {
            pinStore.hasPin()
        } catch (expected: IOException) {
            true
        }
        mutableState.value = if (hasPin) ParentSessionState.Locked else ParentSessionState.SetupRequired
    }

    private fun onBackground() = synchronized(lifecycleLock) {
        backgroundedAt = BackgroundMark(bootClock.bootId(), bootClock.elapsedRealtime())
        autoLockJob?.cancel()
        autoLockJob = when {
            mutableState.value !is ParentSessionState.Unlocked -> null

            settings.lockWhenLeaving.value -> {
                lock()
                null
            }

            else -> scope.launch {
                delay(AUTO_LOCK_AFTER)
                lock()
            }
        }
    }

    private fun onForeground() = synchronized(lifecycleLock) {
        autoLockJob?.cancel()
        autoLockJob = null
        val mark = backgroundedAt
        backgroundedAt = null
        if (mark != null) {
            val now = bootClock.elapsedRealtime()
            val expired = mark.bootId != bootClock.bootId() || now < mark.at || now - mark.at >= AUTO_LOCK_AFTER
            if (expired) lock()
        }
    }

    private data class BackgroundMark(val bootId: String, val at: Duration)

    private fun RecoveryUploadResult.toSetupResult(): RecoveryPinSetupResult = when (this) {
        is RecoveryUploadResult.Uploaded ->
            if (stored) RecoveryPinSetupResult.Registered else RecoveryPinSetupResult.KeptOther

        RecoveryUploadResult.NotApplicable -> RecoveryPinSetupResult.NotAllowed

        RecoveryUploadResult.Failed -> RecoveryPinSetupResult.Failed

        RecoveryUploadResult.Refused -> RecoveryPinSetupResult.Refused
    }

    /** Constant-time comparison of two PINs held in memory (no early exit on the first different digit). */
    private fun sameDigits(a: CharArray, b: CharArray): Boolean {
        var difference = a.size xor b.size
        for (i in 0 until maxOf(a.size, b.size)) {
            difference = difference or (a.getOrElse(i) { ZERO_CHAR }.code xor b.getOrElse(i) { ZERO_CHAR }.code)
        }
        return difference == 0
    }

    /** Outcome of [checkPin]. */
    private sealed interface PinCheck {
        class Verified(val storedHash: String) : PinCheck

        data class Wrong(val next: LockoutDecision) : PinCheck

        data class LockedOut(val decision: LockoutDecision) : PinCheck

        data object NoPin : PinCheck
    }

    companion object {
        /** Time in the background after which the session locks when [LockSettings.lockWhenLeaving] is off. */
        val AUTO_LOCK_AFTER: Duration = 2.minutes

        private const val ZERO_CHAR = '\u0000'
        private const val CHALLENGE_SIZE = 32

        /** Domain separation: an unlock signature can never pass as a ledger-event parent signature. */
        private val UNLOCK_CHALLENGE_PREFIX = "sprout/parent-unlock/v1".encodeToByteArray() + 0.toByte()
    }
}
