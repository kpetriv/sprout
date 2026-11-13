package dev.kpetriv.sprout.feature.lock

import dev.kpetriv.sprout.core.crypto.LockoutDecision
import dev.kpetriv.sprout.core.crypto.ParentAuthAvailability
import dev.kpetriv.sprout.core.crypto.PublicKeyBytes
import java.security.Signature
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Instant

/** Whether the parent area is open. See [ParentSession]. */
sealed interface ParentSessionState {
    /** The stored PIN has not been checked yet (a few milliseconds after start). Treat as locked. */
    data object Initializing : ParentSessionState

    /** No parent PIN exists yet: first run. */
    data object SetupRequired : ParentSessionState

    /** A PIN exists and the parent has not unlocked. */
    data object Locked : ParentSessionState

    /** The parent unlocked at [since] (wall clock, for display only) with [method]. */
    data class Unlocked(val since: Instant, val method: UnlockMethod) : ParentSessionState
}

/** How the current [ParentSessionState.Unlocked] session was opened. */
enum class UnlockMethod {
    PIN,
    BIOMETRIC,

    /** The parent just chose the PIN on first run. */
    PIN_SETUP,
}

/** Outcome of [ParentSession.unlockWithPin]. */
sealed interface PinUnlockResult {
    data object Unlocked : PinUnlockResult

    /** Wrong PIN. The failure was recorded; [next] is the lockout decision that now applies. */
    data class WrongPin(val next: LockoutDecision) : PinUnlockResult

    /** The PIN was not checked: [decision] is [LockoutDecision.Wait] or [LockoutDecision.RequireBiometric]. */
    data class LockedOut(val decision: LockoutDecision) : PinUnlockResult

    /** No PIN exists yet; set one up first. */
    data object SetupRequired : PinUnlockResult

    /** The stored PIN hash, the pepper key or the lockout state cannot be read. Nothing was unlocked. */
    data object Unavailable : PinUnlockResult
}

/** Outcome of [ParentSession.setupPin]. */
sealed interface PinSetupResult {
    /** Saved; the session is now unlocked. */
    data object Saved : PinSetupResult

    data class Invalid(val violation: PinRuleViolation) : PinSetupResult

    /** The confirmation differs from the PIN. */
    data object Mismatch : PinSetupResult

    /** A PIN already exists. Changing it is a separate, unlocked flow. */
    data object NotAllowed : PinSetupResult

    /** Hashing or storage failed; nothing was saved. */
    data object Failed : PinSetupResult
}

/** What [ParentSession.unlockWithBiometric] hands the UI. */
sealed interface BiometricUnlockRequest {
    /**
     * Show a `BiometricPrompt` (`BIOMETRIC_STRONG` only, no device credential) with a `CryptoObject` wrapping
     * [signature], then pass the result's signature to [ParentSession.completeBiometricUnlock]. Single use.
     *
     * The unlock is bound to the registered parent key: the session signs a fresh challenge with the authenticated
     * [signature] and verifies it against [publicKey], the key registered for the household (this device's key
     * equalled it when the request was made). Only a prompt the Keystore actually accepted opens the session, and a
     * fingerprint enrolled after the key was made (which invalidates the key) never does.
     */
    class Ready internal constructor(
        val signature: Signature,
        internal val challenge: ByteArray,
        internal val publicKey: PublicKeyBytes,
    ) : BiometricUnlockRequest {
        private val used = AtomicBoolean(false)

        internal fun markUsed(): Boolean = used.compareAndSet(false, true)
    }

    /** No strong biometric right now ([availability] says why). Use the PIN. */
    data class Unavailable(val availability: ParentAuthAvailability) : BiometricUnlockRequest

    /**
     * This device's parent key cannot sign for the household: biometric enrolment changed since it was made (the
     * key is gone for good), or it is missing or differs from the registered key. The parent unlocks with the PIN
     * and renews the key ([ParentKeyRenewal]).
     */
    data object KeyInvalidated : BiometricUnlockRequest

    /**
     * No parent key is registered for the household, so there is nothing a biometric unlock could be verified
     * against. Use the PIN; fingerprint signing (and with it fingerprint unlock) is turned on in Settings.
     */
    data object NotEnrolled : BiometricUnlockRequest

    /** No PIN exists yet; biometric unlock only replaces the PIN once one is set. */
    data object SetupRequired : BiometricUnlockRequest

    /** Keystore refused for another reason. Use the PIN. */
    data object Failed : BiometricUnlockRequest
}

/** Outcome of [ParentSession.completeBiometricUnlock]. */
enum class BiometricUnlockResult {
    UNLOCKED,

    /** The signature was missing, already used, or did not verify. Nothing was unlocked. */
    REJECTED,
}

/** Outcome of [ParentSession.verifyPinForKeyChange]. */
internal sealed interface KeyChangePinCheck {
    data object Verified : KeyChangePinCheck

    data class Wrong(val next: LockoutDecision) : KeyChangePinCheck

    data class LockedOut(val decision: LockoutDecision) : KeyChangePinCheck

    /** The session is not unlocked (or no PIN exists). */
    data object NotAllowed : KeyChangePinCheck

    /** The stored hash, the pepper key or the lockout state cannot be read. */
    data object Unavailable : KeyChangePinCheck
}

/** Outcome of [ParentSession.changePin]. */
sealed interface ChangePinResult {
    /** The new PIN is saved; the session stays unlocked. */
    data object Changed : ChangePinResult

    /** The current PIN did not match. The failure was recorded; [next] is the lockout decision that now applies. */
    data class WrongCurrentPin(val next: LockoutDecision) : ChangePinResult

    /** The current PIN was not checked: [decision] is [LockoutDecision.Wait] or [LockoutDecision.RequireBiometric]. */
    data class LockedOut(val decision: LockoutDecision) : ChangePinResult

    /** The new PIN breaks a [PinRules] rule. Nothing was checked or saved. */
    data class Invalid(val violation: PinRuleViolation) : ChangePinResult

    /** The confirmation differs from the new PIN. Nothing was checked or saved. */
    data object Mismatch : ChangePinResult

    /** The session is not unlocked; changing the PIN needs an unlocked parent session. */
    data object NotAllowed : ChangePinResult

    /** The stored hash, the pepper key or the lockout state cannot be read or written. The old PIN still works. */
    data object Failed : ChangePinResult
}

/** Outcome of [ParentSession.adoptRecoveredPin]. */
sealed interface RecoveredPinResult {
    /** The recovered PIN is this phone's PIN now, and the session is unlocked. */
    data object Adopted : RecoveredPinResult

    /** A PIN exists already (it is kept), or the PIN breaks [PinRules]: the parent unlocks or chooses as usual. */
    data object NotAllowed : RecoveredPinResult

    /** Storage or Keystore trouble: nothing was stored. */
    data object Failed : RecoveredPinResult
}

/** Outcome of [ParentSession.registerRecoveryPin]. */
sealed interface RecoveryPinSetupResult {
    /** The server stores the check value of this phone's PIN. */
    data object Registered : RecoveryPinSetupResult

    /** Wrong PIN; [next] is the lockout decision that now applies. */
    data class WrongPin(val next: LockoutDecision) : RecoveryPinSetupResult

    data class LockedOut(val decision: LockoutDecision) : RecoveryPinSetupResult

    /** Locked, no PIN, or nothing to register on this phone (not a signed-in parent with an ACTIVE device). */
    data object NotAllowed : RecoveryPinSetupResult

    /** The stored PIN or lockout state cannot be read. */
    data object Unavailable : RecoveryPinSetupResult

    /** The PIN was right, but the upload failed (offline): try again later. */
    data object Failed : RecoveryPinSetupResult

    /** The PIN was right, but the server refused the upload: try again later. */
    data object Refused : RecoveryPinSetupResult

    /** The PIN was right, and the server kept the value of the PIN set or changed last on another working phone. */
    data object KeptOther : RecoveryPinSetupResult
}
