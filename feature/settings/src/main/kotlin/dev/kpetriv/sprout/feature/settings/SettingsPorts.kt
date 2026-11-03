package dev.kpetriv.sprout.feature.settings

import dev.kpetriv.sprout.core.crypto.LockoutDecision
import dev.kpetriv.sprout.core.crypto.ParentAuthAvailability
import kotlinx.coroutines.flow.StateFlow

/*
 * What settings needs from other features. Features never depend on each other (CLAUDE.md), so the app adapts these
 * to `feature/lock` (ParentSession, ParentKeyEnrolment, ParentKeyRenewal, LockSettings) and binds them in Hilt;
 * tests use fakes.
 */

/**
 * Changes the parent PIN inside an unlocked parent session. The app adapts `ParentSession.changePin`, which checks
 * the new PIN's rules and confirmation, verifies [current] under the PIN lockout and saves the new hash.
 * Implementations zero all three arrays before returning.
 */
fun interface PinChanger {
    suspend fun changePin(current: CharArray, new: CharArray, confirm: CharArray): PinChangeOutcome
}

/** Outcome of [PinChanger.changePin]; the app maps `feature/lock`'s `ChangePinResult` onto it. */
sealed interface PinChangeOutcome {
    data object Changed : PinChangeOutcome

    /** The current PIN was wrong; [next] is the lockout decision that now applies. */
    data class WrongCurrentPin(val next: LockoutDecision) : PinChangeOutcome

    /** Too many wrong PINs: wait, or unlock with the fingerprint ([LockoutDecision.RequireBiometric]). */
    data class LockedOut(val decision: LockoutDecision) : PinChangeOutcome

    /** The new PIN is too easy to guess or not six digits. */
    data class Invalid(val problem: NewPinProblem) : PinChangeOutcome

    /** The confirmation differs from the new PIN. */
    data object Mismatch : PinChangeOutcome

    /** The parent session is not unlocked. */
    data object NotAllowed : PinChangeOutcome

    /** Storage or Keystore failure; the old PIN still works. */
    data object Failed : PinChangeOutcome
}

/** Why a new PIN was refused (mirrors `feature/lock`'s `PinRuleViolation`). */
enum class NewPinProblem {
    WRONG_LENGTH,
    NOT_DIGITS,
    ALL_SAME,
    SEQUENCE,
}

/**
 * Fingerprint signing for this device (audit H1, H2, M1). The app adapts `feature/lock`'s PIN-gated
 * `ParentKeyEnrolment` and `ParentKeyRenewal`: each call needs the parent PIN, verified under the PIN lockout, then
 * creates the parent key, proves it with one fingerprint prompt, and registers it with the household policy (which
 * makes parent signatures required). Implementations zero [pin] before returning.
 */
interface ParentKeyRegistrar {
    /**
     * True when a parent key is registered but this device's key can no longer sign for it (a fingerprint was added
     * or removed, or the key is missing or different): the card offers renewal.
     */
    suspend fun needsRenewal(): Boolean

    /** Turns fingerprint signing on. Refuses ([SigningKeyOutcome.AlreadyOn]) when a key is registered already. */
    suspend fun enrol(pin: CharArray): SigningKeyOutcome

    /** Replaces the registered key after a fingerprint change. */
    suspend fun renew(pin: CharArray): SigningKeyOutcome
}

/** Outcome of [ParentKeyRegistrar.enrol] and [ParentKeyRegistrar.renew]; the app maps `feature/lock`'s result. */
sealed interface SigningKeyOutcome {
    /** The new key is registered: every parent money change from now on needs the fingerprint. */
    data object Registered : SigningKeyOutcome

    /** No usable strong biometric on this device (D-M2-1). */
    data class Unavailable(val availability: ParentAuthAvailability) : SigningKeyOutcome

    /** Enrolment refused: a key is already registered. */
    data object AlreadyOn : SigningKeyOutcome

    /** Renewal refused: no key is registered yet. */
    data object NotRegistered : SigningKeyOutcome

    /** Wrong PIN; [next] is the lockout decision that now applies. */
    data class WrongPin(val next: LockoutDecision) : SigningKeyOutcome

    /** The PIN was not checked because of the lockout. */
    data class LockedOut(val decision: LockoutDecision) : SigningKeyOutcome

    /** The fingerprint prompt was dismissed. */
    data object Cancelled : SigningKeyOutcome

    /** The parent session is not unlocked, or storage, Keystore or the prompt failed. Nothing changed. */
    data object Failed : SigningKeyOutcome
}

/** The parent-lock preferences settings edits. The app adapts `feature/lock`'s `LockSettings`. */
interface LockPreferences {
    /** Lock the parent area as soon as the app leaves the foreground. */
    val lockWhenLeaving: StateFlow<Boolean>

    fun setLockWhenLeaving(enabled: Boolean)
}
