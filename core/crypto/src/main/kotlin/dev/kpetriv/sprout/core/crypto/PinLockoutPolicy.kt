package dev.kpetriv.sprout.core.crypto

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.security.GeneralSecurityException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * Persisted PIN lockout state (through [LockoutStateStore]). Times are elapsed realtime of boot [bootId], never
 * wall-clock instants, so moving the clock changes nothing.
 */
data class PinLockoutState(
    val consecutiveFailures: Int = 0,
    /** [BootClock.elapsedRealtime] when the current lockout started, or null when no timed lockout is running. */
    val lockoutStartedAt: Duration? = null,
    /** [BootClock.bootId] of the boot [lockoutStartedAt] belongs to; null exactly when [lockoutStartedAt] is. */
    val bootId: String? = null,
) {
    init {
        require(consecutiveFailures >= 0) { "consecutiveFailures must not be negative" }
        require((lockoutStartedAt == null) == (bootId == null)) { "lockoutStartedAt and bootId go together" }
        require(lockoutStartedAt == null || !lockoutStartedAt.isNegative()) { "lockoutStartedAt is negative" }
    }

    companion object {
        val Initial = PinLockoutState()
    }
}

/** What the PIN screen may do right now. */
sealed interface LockoutDecision {
    /** A PIN attempt is allowed. */
    data object Allowed : LockoutDecision

    /** Wait [remaining] before the next attempt. */
    data class Wait(val remaining: Duration) : LockoutDecision

    /**
     * The lockout reached its cap ([PinLockoutPolicy.MAX_LOCKOUT]): the PIN path is closed until the parent
     * re-authenticates with a strong biometric, after which the lock feature calls
     * [PinLockoutPolicy.registerBiometricSuccess] (ADR-0004 section 3). Only returned while [ParentKeyHealth]
     * says that biometric unlock can actually succeed.
     */
    data object RequireBiometric : LockoutDecision
}

/**
 * Whether a strong-biometric unlock can open the parent session right now: a strong biometric is usable, a parent
 * key is registered for the household, this device's key byte-equals it, and the key is not invalidated. The lock
 * feature implements it (it knows the registered key); [PinLockoutPolicy] asks it before closing the PIN path.
 *
 * Implementations should answer false rather than throw; the policy treats a failure as false.
 */
fun interface ParentKeyHealth {
    suspend fun biometricUnlockUsable(): Boolean
}

/**
 * Exponential lockout for failed parent-PIN attempts (threat T7), persisted through [LockoutStateStore] so
 * neither process death nor a reboot resets it. The first [FREE_ATTEMPTS] failures cost nothing; each later
 * failure locks for [BASE_LOCKOUT] doubled per extra failure: 0, 0, 0, 30 s, 60 s, 120 s, ... When the next
 * lockout would reach [MAX_LOCKOUT] (1 h, from failure [CAP_FAILURES]) the decision becomes
 * [LockoutDecision.RequireBiometric] instead of a timed wait. When biometric unlock cannot succeed
 * ([ParentKeyHealth]: no strong biometric, no registered parent key, a key that differs from the registered one,
 * or a key invalidated by a fingerprint change) there is nothing to fall back to, so the cap stays a
 * [MAX_LOCKOUT] wait per failure instead of locking the parent out of the PIN forever.
 *
 * Time comes from [BootClock] (elapsed realtime plus boot id), never from the wall clock:
 * - wall-clock changes in either direction have no effect;
 * - after a reboot the served part of a running lockout cannot be known, so the whole lockout is due again from
 *   the first check after boot (conservative: rebooting never shortens a lockout, it can only lengthen it);
 * - elapsed realtime earlier than the stored start within the same boot id (impossible unless the boot id
 *   fallback missed a reboot or the state was edited) is treated the same way.
 *
 * Unreadable stored state (tampered, or its key is gone) is treated as the cap: [CAP_FAILURES] failures with a
 * lockout starting now. Deleting the state file on a rooted device does reset it; nothing short of a hardware
 * counter prevents that, which is why the PIN hash is also peppered (ADR-0004).
 *
 * Callers check [decision] before verifying a PIN and only verify on [LockoutDecision.Allowed]; the policy cannot
 * stop a caller from verifying anyway.
 */
@Singleton
class PinLockoutPolicy @Inject constructor(
    private val store: LockoutStateStore,
    private val bootClock: BootClock,
    private val keyHealth: ParentKeyHealth,
) {
    private val mutex = Mutex()

    /** The current decision. May persist a normalised state (served lockout cleared, reboot re-based). */
    suspend fun decision(): LockoutDecision = mutex.withLock { evaluate(load()) }

    /** Records a wrong PIN and returns the decision that now applies. */
    suspend fun registerFailure(): LockoutDecision = mutex.withLock {
        val failures = load().consecutiveFailures.let { if (it == Int.MAX_VALUE) it else it + 1 }
        val next = if (lockoutAfter(failures) == Duration.ZERO) {
            PinLockoutState(consecutiveFailures = failures)
        } else {
            PinLockoutState(failures, bootClock.elapsedRealtime(), bootClock.bootId())
        }
        store.write(next)
        evaluate(next)
    }

    /** A correct PIN clears the failure count. */
    suspend fun registerSuccess() = mutex.withLock { store.write(PinLockoutState.Initial) }

    /** A successful strong-biometric re-authentication clears any lockout, [LockoutDecision.RequireBiometric] too. */
    suspend fun registerBiometricSuccess() = registerSuccess()

    /** The stored state, re-based or cleared as the class comment describes. Caller holds [mutex]. */
    private suspend fun load(): PinLockoutState {
        val stored = try {
            store.read() ?: PinLockoutState.Initial
        } catch (expected: LockoutStateUnavailableException) {
            return capState().also { persistBestEffort(it) }
        }
        val normalised = normalise(stored)
        if (normalised != stored) store.write(normalised)
        return normalised
    }

    private fun normalise(state: PinLockoutState): PinLockoutState {
        val startedAt = state.lockoutStartedAt ?: return state
        val now = bootClock.elapsedRealtime()
        val bootId = bootClock.bootId()
        return when {
            // Rebooted, or time ran backwards within a boot: the served part is unknown, so all of it is due again.
            state.bootId != bootId || now < startedAt -> state.copy(lockoutStartedAt = now, bootId = bootId)

            now - startedAt >= lockoutAfter(state.consecutiveFailures) ->
                state.copy(lockoutStartedAt = null, bootId = null)

            else -> state
        }
    }

    private suspend fun evaluate(state: PinLockoutState): LockoutDecision {
        val lockout = lockoutAfter(state.consecutiveFailures)
        val capped = lockout >= MAX_LOCKOUT && biometricUnlockUsable()
        val served = state.lockoutStartedAt?.let { bootClock.elapsedRealtime() - it } ?: lockout
        val remaining = (lockout - served).coerceIn(Duration.ZERO, lockout)
        return when {
            capped -> LockoutDecision.RequireBiometric
            remaining > Duration.ZERO -> LockoutDecision.Wait(remaining)
            else -> LockoutDecision.Allowed
        }
    }

    /** A failing health check means "no biometric to fall back to": a timed wait, never a permanent closure. */
    @Suppress("TooGenericExceptionCaught") // Any failure of the check must degrade to the timed wait.
    private suspend fun biometricUnlockUsable(): Boolean = try {
        keyHealth.biometricUnlockUsable()
    } catch (e: CancellationException) {
        throw e
    } catch (expected: Exception) {
        false
    }

    private fun capState() = PinLockoutState(CAP_FAILURES, bootClock.elapsedRealtime(), bootClock.bootId())

    /** Replaces unreadable state. If even that fails, the in-memory cap decision still applies to this call. */
    private suspend fun persistBestEffort(state: PinLockoutState) {
        try {
            store.write(state)
        } catch (expected: GeneralSecurityException) {
            // Nothing safer to do: the decision returned for this call is already the cap.
        } catch (expected: IOException) {
            // Same.
        }
    }

    companion object {
        const val FREE_ATTEMPTS = 3
        val BASE_LOCKOUT: Duration = 30.seconds
        val MAX_LOCKOUT: Duration = 1.hours

        /** Doubling stops well before overflow; 30 s * 2^7 already exceeds the 1 h cap. */
        private const val MAX_DOUBLINGS = 16

        /** The first failure count whose lockout reaches [MAX_LOCKOUT] (11 with the current schedule). */
        val CAP_FAILURES: Int = generateSequence(FREE_ATTEMPTS + 1) { it + 1 }.first { lockoutAfter(it) >= MAX_LOCKOUT }

        /** Lockout imposed right after the [failures]-th consecutive failure. */
        fun lockoutAfter(failures: Int): Duration {
            if (failures <= FREE_ATTEMPTS) return Duration.ZERO
            val doublings = (failures - FREE_ATTEMPTS - 1).coerceAtMost(MAX_DOUBLINGS)
            return (BASE_LOCKOUT * (1 shl doublings)).coerceAtMost(MAX_LOCKOUT)
        }
    }
}
