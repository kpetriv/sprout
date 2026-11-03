package dev.kpetriv.sprout.feature.lock

import dev.kpetriv.sprout.core.common.Dispatcher
import dev.kpetriv.sprout.core.common.SproutDispatchers
import dev.kpetriv.sprout.core.crypto.LockoutDecision
import dev.kpetriv.sprout.core.crypto.ParentAuthAvailability
import dev.kpetriv.sprout.core.crypto.ParentAuthUnavailableException
import dev.kpetriv.sprout.core.crypto.ParentAuthorizer
import dev.kpetriv.sprout.core.crypto.ParentSigner
import dev.kpetriv.sprout.core.crypto.PublicKeyBytes
import dev.kpetriv.sprout.core.crypto.verifyParentSignature
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.security.Signature
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of [ParentKeyEnrolment.enrol] and [ParentKeyRenewal.renew]. Nothing is registered unless [Registered]. */
sealed interface ParentKeyChangeResult {
    /** A new parent key exists, passed its proof prompt, and is now the household's registered key. */
    data object Registered : ParentKeyChangeResult

    /** No usable strong biometric ([availability] says why). For enrolment the D-M2-1 state was recorded. */
    data class Unavailable(val availability: ParentAuthAvailability) : ParentKeyChangeResult

    /** Enrolment refused: a key is already registered. Replacing it is [ParentKeyRenewal]'s job. */
    data object AlreadyRegistered : ParentKeyChangeResult

    /** Renewal refused: no key is registered yet. Use [ParentKeyEnrolment]. */
    data object NotRegistered : ParentKeyChangeResult

    /** Wrong PIN. The failure was recorded; [next] is the lockout decision that now applies. */
    data class WrongPin(val next: LockoutDecision) : ParentKeyChangeResult

    /** The PIN was not checked: [decision] is a [LockoutDecision.Wait] or [LockoutDecision.RequireBiometric]. */
    data class LockedOut(val decision: LockoutDecision) : ParentKeyChangeResult

    /** The parent session is not unlocked. */
    data object NotAllowed : ParentKeyChangeResult

    /** The parent dismissed the proof prompt. No key was kept. */
    data object Cancelled : ParentKeyChangeResult

    /** Storage, Keystore, prompt or proof failure. No new key was kept or registered. */
    data object Failed : ParentKeyChangeResult
}

/**
 * Shows the one strong-biometric prompt that proves a freshly created parent key works, bound to [signature]
 * through a `CryptoObject`. Returns the authenticated signature; throws [BiometricCancelledException] or
 * [BiometricErrorException]. Bound in Hilt to [HostedParentKeyProofPrompter].
 */
fun interface ParentKeyProofPrompter {
    suspend fun prompt(signature: Signature): Signature
}

/**
 * Turns fingerprint signing on (audit H1, M1): the **only** way a parent key is first created and registered.
 *
 * [enrol] needs the parent PIN in the same call, verified under the PIN lockout inside an unlocked session (a
 * wrong PIN counts as a failed attempt). Then it fetches the server's attestation challenge
 * ([ParentKeyChallengeSource]), deletes any stray local key, creates a new one bound to that challenge (StrongBox
 * first, TEE otherwise), proves it with one strong-biometric prompt over a fresh, domain-separated challenge (signed
 * and verified against the new public key), and only then registers it, with its attestation chain, with the
 * household ([ParentKeyRegistry]), which makes parent signatures required. It refuses to replace a registered key:
 * that is [ParentKeyRenewal].
 *
 * Runs at PIN setup (with the PIN the parent just chose, see [enrolInBackground]) and from Settings ("Turn on").
 */
@Singleton
class ParentKeyEnrolment @Inject internal constructor(private val provisioner: ParentKeyProvisioner) {
    /** See the class comment. Zeroes [pin]. */
    suspend fun enrol(pin: CharArray): ParentKeyChangeResult = provisioner.provision(pin, renew = false)

    /**
     * [enrol] in the lock feature's process-wide scope, so leaving the PIN setup screen (which the unlock does at
     * once) does not cancel the proof prompt. Without a usable strong biometric it only records the D-M2-1 state.
     * Zeroes [pin].
     */
    fun enrolInBackground(pin: CharArray): Job = provisioner.launch { enrol(pin) }
}

/**
 * Recovery after a fingerprint change (audit H2): replaces the registered parent key with a new one. Needs the
 * parent PIN in the same call (verified under the PIN lockout inside an unlocked session); then fetches a new
 * attestation challenge, deletes the invalidated (or mismatched) local key, creates a new one bound to the challenge,
 * proves it with one strong-biometric prompt, and replaces the registered key (with the new attestation chain),
 * keeping the lamport from which signatures are required. Refuses when no key is registered ([ParentKeyEnrolment]
 * instead).
 *
 * Offered from Settings ("Fingerprint changed: renew signing key", and "Signing key not registered with the
 * household: Retry" when the server registration failed, D-M3-39: the chain is bound to a consumed challenge, so a
 * retry needs a new key) and from the PIN screen after the fingerprint reported
 * [BiometricUnlockRequest.KeyInvalidated] ([renewInBackground]). A cancelled proof prompt leaves no key, so a retry
 * that is cancelled turns into "renew signing key".
 */
@Singleton
class ParentKeyRenewal @Inject internal constructor(private val provisioner: ParentKeyProvisioner) {
    /** See the class comment. Zeroes [pin]. */
    suspend fun renew(pin: CharArray): ParentKeyChangeResult = provisioner.provision(pin, renew = true)

    /** [renew] in the lock feature's process-wide scope, for the PIN screen that the unlock removes. Zeroes [pin]. */
    fun renewInBackground(pin: CharArray): Job = provisioner.launch { renew(pin) }
}

/** The shared, serialised create-prove-register flow behind [ParentKeyEnrolment] and [ParentKeyRenewal]. */
@Singleton
@Suppress("LongParameterList") // Injected collaborators.
internal class ParentKeyProvisioner @Inject constructor(
    private val session: ParentSession,
    private val authorizer: ParentAuthorizer,
    private val signer: ParentSigner,
    private val registry: ParentKeyRegistry,
    private val challenges: ParentKeyChallengeSource,
    private val prompter: ParentKeyProofPrompter,
    private val random: SecureRandom,
    @LockScope private val scope: CoroutineScope,
    @Dispatcher(SproutDispatchers.IO) private val ioDispatcher: CoroutineDispatcher,
) {
    private val mutex = Mutex()

    fun launch(block: suspend () -> ParentKeyChangeResult): Job = scope.launch { block() }

    @Suppress("TooGenericExceptionCaught")
    suspend fun provision(pin: CharArray, renew: Boolean): ParentKeyChangeResult = try {
        mutex.withLock { provisionLocked(pin, renew) }
    } catch (e: CancellationException) {
        throw e
    } catch (expected: BiometricCancelledException) {
        ParentKeyChangeResult.Cancelled
    } catch (expected: ParentAuthUnavailableException) {
        ParentKeyChangeResult.Unavailable(expected.availability)
    } catch (expected: Exception) {
        // GeneralSecurityException, IOException, BiometricErrorException, IllegalStateException (no prompt host,
        // registry refused), IllegalArgumentException (registry refused the key): nothing was registered.
        ParentKeyChangeResult.Failed
    } finally {
        pin.fill(ZERO_CHAR)
    }

    /** Caller holds [mutex]. */
    private suspend fun provisionLocked(pin: CharArray, renew: Boolean): ParentKeyChangeResult =
        refusal(pin, renew) ?: createProveAndRegister(renew)

    /** Why the key must not change now, or null once the PIN is verified. Caller holds [mutex]. */
    private suspend fun refusal(pin: CharArray, renew: Boolean): ParentKeyChangeResult? {
        val availability = authorizer.availability()
        val available = availability == ParentAuthAvailability.STRONG_BIOMETRIC_AVAILABLE
        val registered = if (available) registry.registeredParentKey() else null
        return when {
            !available -> {
                if (!renew) recordSigningUnavailableBestEffort()
                ParentKeyChangeResult.Unavailable(availability)
            }

            !renew && registered != null -> ParentKeyChangeResult.AlreadyRegistered

            renew && registered == null -> ParentKeyChangeResult.NotRegistered

            else -> when (val check = session.verifyPinForKeyChange(pin)) {
                KeyChangePinCheck.Verified -> null
                is KeyChangePinCheck.Wrong -> ParentKeyChangeResult.WrongPin(check.next)
                is KeyChangePinCheck.LockedOut -> ParentKeyChangeResult.LockedOut(check.decision)
                KeyChangePinCheck.NotAllowed -> ParentKeyChangeResult.NotAllowed
                KeyChangePinCheck.Unavailable -> ParentKeyChangeResult.Failed
            }
        }
    }

    /** After a verified PIN. Caller holds [mutex]. */
    private suspend fun createProveAndRegister(renew: Boolean): ParentKeyChangeResult {
        // In this order (D-M3-34): the challenge first, while the current key is still in place (a failure here, such
        // as being offline, changes nothing), then the key bound to it, the proof prompt and the registration, so the
        // 10-minute challenge is fresh when the server consumes it.
        val challenge = challenges.registrationChallenge()
        // A key that exists now was not made by this flow (or was invalidated): never trust it, start fresh.
        authorizer.resetParentKey()
        val publicKey = authorizer.createParentKey(challenge)
        var kept = false
        try {
            prove(publicKey)
            val chain = authorizer.parentKeyAttestationChain()
            if (renew) registry.replaceParentKey(publicKey, chain) else registry.registerParentKey(publicKey, chain)
            kept = true
        } finally {
            if (!kept) deleteKeyBestEffort()
        }
        return ParentKeyChangeResult.Registered
    }

    /** One prompt: sign a fresh, domain-separated challenge with the new key and verify it. Throws on failure. */
    private suspend fun prove(publicKey: PublicKeyBytes) {
        val challenge = KEY_PROOF_PREFIX + ByteArray(CHALLENGE_SIZE).also(random::nextBytes)
        val signature = authorizer.initSignature()
        val authenticated = prompter.prompt(signature)
        if (authenticated !== signature) throw GeneralSecurityException("The proof prompt returned another signature")
        // Keystore signing is blocking IPC.
        val signed = withContext(ioDispatcher) { signer.sign(authenticated, challenge) }
        if (!verifyParentSignature(publicKey, challenge, signed)) {
            throw GeneralSecurityException("The new parent key's proof signature did not verify")
        }
    }

    /** A key that was not proven and registered must not stay behind, even when the flow was cancelled. */
    private suspend fun deleteKeyBestEffort() {
        try {
            withContext(NonCancellable) { authorizer.resetParentKey() }
        } catch (expected: GeneralSecurityException) {
            // The next enrolment or renewal deletes it first anyway, and nothing trusts an unregistered key.
        }
    }

    private suspend fun recordSigningUnavailableBestEffort() {
        try {
            registry.recordSigningUnavailable()
        } catch (expected: IOException) {
            // Without a policy row the ledger default (signatures not required) applies anyway.
        } catch (expected: GeneralSecurityException) {
            // Same.
        }
    }

    private companion object {
        const val ZERO_CHAR = '\u0000'
        const val CHALLENGE_SIZE = 32

        /** Domain separation from ledger parent signatures and the unlock challenge. */
        val KEY_PROOF_PREFIX = "sprout/parent-key-registration/v1".encodeToByteArray() + 0.toByte()
    }
}
