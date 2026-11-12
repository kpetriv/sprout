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
     *
     * [attestationChain] is the key's Keystore attestation (DER certificates, leaf first) bound to the challenge from
     * [ParentKeyChallengeSource]; empty when there was no challenge (trial household) or Keystore could not attest.
     * The app sends it with the server registration (`registerParentKey`, D-M3-34).
     */
    suspend fun registerParentKey(publicKey: PublicKeyBytes, attestationChain: List<ByteArray>)

    /**
     * Replaces the registered key with [publicKey] after a renewal, keeping the lamport from which signatures are
     * required. Throws [IllegalStateException] when no key is registered. [attestationChain] as for
     * [registerParentKey].
     */
    suspend fun replaceParentKey(publicKey: PublicKeyBytes, attestationChain: List<ByteArray>)

    /**
     * True when the household policy that [registerParentKey] writes into is there, or can be recovered (D-REL-15):
     * always for the trial household; in a real household when this device has its policy row, or after a pull that
     * completed without an error the server holds no policy at all, so registration recovers it from the household's
     * own currency (D-REL-14). [ParentKeyEnrolment] asks before the PIN check, the server challenge, key creation and
     * the proof prompt, so a registration that is bound to be refused never shows a fingerprint prompt. May sync, so it
     * can take a few seconds. Throws when storage cannot be read.
     */
    suspend fun householdPolicyReady(): Boolean

    /**
     * Records the D-M2-1 state for a device without a usable strong biometric: the household policy exists with
     * parent signatures off. Never removes a registered key.
     */
    suspend fun recordSigningUnavailable()
}

/**
 * The server's registration challenge for the parent key's Keystore attestation (`beginDeviceRegistration`,
 * D-M3-34), fetched by [ParentKeyEnrolment] and [ParentKeyRenewal] after the PIN check and right before the key is
 * created, so the 10-minute challenge is fresh when `registerParentKey` consumes it. The app adapts the callable.
 */
fun interface ParentKeyChallengeSource {
    /**
     * A fresh challenge, or null when there is no household session (the trial household): the key is then created
     * without attestation and stays local. Throws when a household exists but the challenge cannot be fetched
     * (offline): the key change fails and nothing changes.
     */
    suspend fun registrationChallenge(): ByteArray?
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
