package dev.kpetriv.sprout.core.sync

import dev.kpetriv.sprout.core.model.DeviceId
import kotlin.time.Instant

/**
 * The account-recovery callables of ADR-0013 (§1, §2, §4), each carrying the calling device's proof. The Firebase
 * implementation signs with this device's key and the ID token the call carries (the app's adapter over
 * core/sync-firebase `HouseholdFunctions`); tests use `FakeRecoveryCallables` over `FakeBackend`.
 *
 * Failures: a refusal the server explains (`details.reason`) is a [RecoveryRefusedException]; anything else is a
 * [SyncException] ([SyncError.Transient] for network trouble and rate limits, [SyncError.Unauthenticated] with no
 * session, [SyncError.Permanent] with the callable's error code otherwise). Nothing here ever carries the verifier.
 */
interface RecoveryCallables {
    /**
     * `setRecoveryPin` from this ACTIVE parent device: replaces the account's check value ([salt] 16 bytes, [kdf]
     * descriptor, [verifier] 32 bytes). [newPin] is true when the PIN was just chosen or changed: the failed attempts
     * start again at 0. False registers an unchanged PIN again (a household from before 1.1.1, Settings' "finish
     * setting up", the 30-day refresh): the server keeps the count, so a closed PIN path stays closed, and keeps the
     * value another ACTIVE phone of the account set ([StoredRecoveryPin.stored] false).
     */
    suspend fun setRecoveryPin(
        salt: ByteArray,
        kdf: String,
        verifier: ByteArray,
        newPin: Boolean,
    ): Result<StoredRecoveryPin>

    /** `recoveryStatus` of this PENDING parent device. */
    suspend fun recoveryStatus(): Result<RecoveryStatus>

    /** `recoverParentDevice` of this PENDING parent device, with the PIN's verifier or by waiting. */
    suspend fun recoverParentDevice(request: RecoveryRequest): Result<RecoveredParentDevice>
}

/**
 * `setRecoveryPin`'s answer: [stored] false when the server kept another ACTIVE phone's value (a `newPin: false` call;
 * the account restores with the PIN set or changed last, on that phone); [attemptsLeft] (0..10) of the value the
 * server holds now.
 */
data class StoredRecoveryPin(val stored: Boolean, val attemptsLeft: Int)

/** The recorded check value's parameters: its 16-byte [salt] and [kdf] descriptor (never the value itself). */
class RecoveryPinRecord(salt: ByteArray, val kdf: String) {
    private val saltBytes = salt.copyOf()

    val salt: ByteArray get() = saltBytes.copyOf()

    override fun toString(): String = "RecoveryPinRecord(kdf=$kdf, <redacted>)"
}

/**
 * `recoveryStatus`: [pin] is null when no check value is stored or its attempts are used up; [attemptsLeft] 0..10
 * (0 without a check value); [waitEndsAt] null until the phone asked for the wait (the first `WAIT` call), then that
 * moment plus 7 days.
 */
data class RecoveryStatus(val pin: RecoveryPinRecord?, val attemptsLeft: Int, val waitEndsAt: Instant?)

/** How a recovery proves itself. */
enum class RecoveryMethod { PIN, WAIT }

/** A `recoverParentDevice` request. */
sealed interface RecoveryRequest {
    val method: RecoveryMethod

    /** With the PIN: [verifier] is the 32-byte check value (a copy; the caller zeroes its own). */
    class Pin(verifier: ByteArray) : RecoveryRequest {
        private val bytes = verifier.copyOf()

        override val method: RecoveryMethod get() = RecoveryMethod.PIN

        /** A copy for the caller to send and zero. */
        fun verifier(): ByteArray = bytes.copyOf()

        /** Zeroes the request's copy. */
        fun wipe() = bytes.fill(0)

        override fun toString(): String = "RecoveryRequest.Pin(<redacted>)"
    }

    /** Without the PIN, once the wait is over. */
    data object Wait : RecoveryRequest {
        override val method: RecoveryMethod get() = RecoveryMethod.WAIT
    }
}

/**
 * `recoverParentDevice` succeeded: [deviceId] is ACTIVE and [revokedDevices] other parent devices were removed;
 * [attemptsLeft] is what is left of the PIN's attempts (wrong guesses add up as long as the PIN stays the same); with
 * [signInAgain] every session of the account was revoked, this phone's too: it signs in once more before it syncs.
 */
data class RecoveredParentDevice(
    val deviceId: DeviceId,
    val revokedDevices: Int,
    val method: RecoveryMethod,
    val attemptsLeft: Int,
    val signInAgain: Boolean,
)

/** What a device's registry row says when it is not PENDING (`details.status` of `not-pending`). */
enum class RecoveryDeviceStatus { ACTIVE, REVOKED, OTHER }

/** A refusal of a recovery callable with its reason (ADR-0013 §4 "Errors"). */
sealed interface RecoveryRefusal {
    /** `failed-precondition`, `recent-sign-in`: sign in again, then retry. */
    data object RecentSignIn : RecoveryRefusal

    /** `permission-denied`, `wrong-pin`: [attemptsLeft] tries remain (0 closes the PIN path). */
    data class WrongPin(val attemptsLeft: Int) : RecoveryRefusal

    /** `failed-precondition`, `pin-unavailable`: no check value, or its attempts are used up. */
    data object PinUnavailable : RecoveryRefusal

    /**
     * `failed-precondition`, `wait`: the wait ends at [waitEndsAt]. The first `WAIT` call answers this too: it is how
     * the wait starts, not an error.
     */
    data class Wait(val waitEndsAt: Instant) : RecoveryRefusal

    /**
     * `failed-precondition`, `other-parent`: another parent account of the household has an ACTIVE device, which can
     * approve this phone; recovery is not offered here.
     */
    data object OtherParent : RecoveryRefusal

    /** `failed-precondition`, `not-pending`: the device is [status] (ACTIVE: carry on; REVOKED: start again). */
    data class NotPending(val status: RecoveryDeviceStatus) : RecoveryRefusal
}

/** A recovery callable refused with a [refusal] the client acts on. The message is a fixed code. */
class RecoveryRefusedException(val refusal: RecoveryRefusal) : Exception(refusal.javaClass.simpleName)
