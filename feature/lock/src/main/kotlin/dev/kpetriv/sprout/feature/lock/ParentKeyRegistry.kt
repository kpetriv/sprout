package dev.kpetriv.sprout.feature.lock

import dev.kpetriv.sprout.core.crypto.ParentAuthAvailability
import dev.kpetriv.sprout.core.crypto.ParentAuthorizer
import dev.kpetriv.sprout.core.crypto.ParentKeyHealth
import dev.kpetriv.sprout.core.crypto.ParentKeyInvalidatedException
import dev.kpetriv.sprout.core.crypto.PublicKeyBytes
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The household's registered parent key (`household_policy`, ADR-0004, D-M2-1). The app binds it to
 * `core/database` (`HouseholdPolicyRepository` and `RegisterParentKeyUseCase`); tests use an in-memory fake.
 *
 * The registered key is the only one this module trusts: biometric unlock and per-event parent authorisation are
 * offered only when this device's key byte-equals it, and their signatures are verified against it. Only the
 * PIN-gated [ParentKeyEnrolment] and [ParentKeyRenewal] write to it.
 */
interface ParentKeyRegistry {
    /** The registered parent public key, or null when none is registered. Throws when storage cannot be read. */
    suspend fun registeredParentKey(): PublicKeyBytes?

    /**
     * Registers [publicKey] as the household's parent key and turns required parent signatures on. Throws
     * [IllegalStateException] when a key is already registered: replacing one is [replaceParentKey]'s job.
     */
    suspend fun registerParentKey(publicKey: PublicKeyBytes)

    /**
     * Replaces the registered key with [publicKey] after a renewal, keeping the lamport from which signatures are
     * required. Throws [IllegalStateException] when no key is registered.
     */
    suspend fun replaceParentKey(publicKey: PublicKeyBytes)

    /**
     * Records the D-M2-1 state for a device without a usable strong biometric: the household policy exists with
     * parent signatures off. Never removes a registered key.
     */
    suspend fun recordSigningUnavailable()
}

/** Whether this device can sign as the household's parent right now. See [ParentKeyStatusCheck]. */
enum class ParentKeyStatus {
    /** A strong biometric is usable and this device's key is the registered one and still valid. */
    READY,

    /** A strong biometric is usable but no parent key is registered: signing can be turned on (enrolment). */
    NOT_REGISTERED,

    /**
     * A key is registered, but this device's key was invalidated by a fingerprint change, is missing, or differs
     * from the registered one: the parent renews it with the PIN ([ParentKeyRenewal]).
     */
    NEEDS_RENEWAL,

    /** No usable strong biometric on this device (D-M2-1); [ParentAuthorizer.availability] says why. */
    UNAVAILABLE,
}

/**
 * Computes [ParentKeyStatus] from the local key and the [ParentKeyRegistry]. Also the [ParentKeyHealth] that
 * `PinLockoutPolicy` asks before closing the PIN path at the lockout cap: only [ParentKeyStatus.READY] means a
 * biometric unlock can actually succeed.
 *
 * Throws when the registry cannot be read; [biometricUnlockUsable] answers false then.
 */
@Singleton
class ParentKeyStatusCheck @Inject constructor(
    private val authorizer: ParentAuthorizer,
    private val registry: ParentKeyRegistry,
) : ParentKeyHealth {
    suspend fun status(): ParentKeyStatus {
        if (authorizer.availability() != ParentAuthAvailability.STRONG_BIOMETRIC_AVAILABLE) {
            return ParentKeyStatus.UNAVAILABLE
        }
        val registered = registry.registeredParentKey()
        return when {
            registered == null -> ParentKeyStatus.NOT_REGISTERED
            authorizer.existingParentPublicKey() != registered -> ParentKeyStatus.NEEDS_RENEWAL
            localKeyUsable() -> ParentKeyStatus.READY
            else -> ParentKeyStatus.NEEDS_RENEWAL
        }
    }

    /** Keystore reports a permanently invalidated key only when an operation is initialised. */
    private fun localKeyUsable(): Boolean = try {
        authorizer.initSignature()
        true
    } catch (expected: ParentKeyInvalidatedException) {
        false
    } catch (expected: IllegalStateException) {
        // The key disappeared between the two calls.
        false
    }

    override suspend fun biometricUnlockUsable(): Boolean = status() == ParentKeyStatus.READY
}
