package dev.kpetriv.sprout.core.crypto

import java.security.GeneralSecurityException
import java.security.Signature
import javax.inject.Inject

/**
 * The parent authentication key's Keystore lifecycle (alias [KeyAliases.PARENT_AUTH]): EC P-256, every use bound
 * to its own strong-biometric `BiometricPrompt` through a `CryptoObject`, invalidated when biometric enrolment
 * changes (ADR-0004, threats T7 and T16). Feature and data code go through [ParentAuthorizer] instead.
 */
interface ParentAuthKeyStore {
    /**
     * Creates the key if missing and returns its public key. Throws [ParentAuthUnavailableException] when Keystore
     * refuses to create it (typically: no strong biometric is enrolled). Never leaves a partial key behind.
     *
     * With [attestationChallenge] a new key is generated with `setAttestationChallenge` (falling back to an
     * unattested key where Keystore cannot attest); an existing key is returned as is, whatever challenge it carries.
     */
    suspend fun ensureKey(attestationChallenge: ByteArray? = null): PublicKeyBytes

    /**
     * The key's attestation chain, DER certificates leaf first; empty before [ensureKey], for a key created without a
     * challenge, or where Keystore could not attest (only a self-signed certificate).
     */
    fun attestationChain(): List<ByteArray>

    /** The public key, or null before [ensureKey]. */
    fun publicKey(): PublicKeyBytes?

    /**
     * The key's [DeviceKeyProperties] from Keystore `KeyInfo`, or null before [ensureKey]. Self-reported, **not**
     * attestation; see [DeviceKeyProperties].
     */
    fun keyProperties(): DeviceKeyProperties?

    /**
     * A fresh [Signature] initialised with the private key, see [ParentAuthorizer.initSignature]. Throws
     * [ParentKeyInvalidatedException] after an enrolment change and [IllegalStateException] before [ensureKey].
     */
    fun initSignature(): Signature

    /** Deletes the key, for example after it was permanently invalidated. */
    suspend fun deleteKey()
}

/**
 * Parent authorisation of parent-only ledger events (ADR-0004 section 1). No UI here: the feature layer wraps
 * [initSignature] in a `BiometricPrompt.CryptoObject`, prompts with `BIOMETRIC_STRONG` only, and passes the
 * authenticated [Signature] from the prompt result to [ParentSigner.sign].
 *
 * **Key creation is never lazy.** Reading the key ([existingParentPublicKey]) never creates one; only
 * [createParentKey] does, and only the PIN-gated enrolment and renewal flows in `feature/lock`
 * (`ParentKeyEnrolment`, `ParentKeyRenewal`) may call it. A key minted anywhere else would let whoever holds an
 * unlocked phone, without the parent PIN, bind a fingerprint they enrolled to the parent role.
 *
 * **A local key is not trusted on its own.** Before offering a biometric unlock or handing out a parent
 * signature, callers check that [existingParentPublicKey] byte-equals the household's registered parent key and
 * verify signatures against the registered key. A parent-only event signed by a key that is not the registered
 * one must be treated (and reported) as unsigned.
 *
 * Production binds [KeystoreParentAuthorizer]; JVM tests use `FakeParentAuthorizer` from the test fixtures.
 */
interface ParentAuthorizer {
    /** Whether this device can produce parent signatures right now. */
    fun availability(): ParentAuthAvailability

    /**
     * The parent public key (X.509 SPKI DER) if this device holds one, or null. Never creates a key. Blocking
     * Keystore IPC, but cheap.
     */
    fun existingParentPublicKey(): PublicKeyBytes?

    /**
     * Creates the parent key if missing and returns its public key. Creation may take a few seconds on StrongBox,
     * so this suspends. **Only for the PIN-gated enrolment and renewal flows** (see the interface comment).
     *
     * Throws [ParentAuthUnavailableException] (with the reason) when no key exists yet and [availability] is not
     * [ParentAuthAvailability.STRONG_BIOMETRIC_AVAILABLE], or when Keystore refuses the key.
     *
     * [attestationChallenge] is the server's registration challenge (`beginDeviceRegistration`): a new key is created
     * with it bound into its Keystore attestation ([parentKeyAttestationChain]); null (the trial household, no
     * session) creates the key without attestation. An existing key is returned unchanged.
     */
    suspend fun createParentKey(attestationChallenge: ByteArray? = null): PublicKeyBytes

    /**
     * The attestation chain of this device's parent key (DER certificates, leaf first), for `registerParentKey`.
     * Empty when there is no key, it was created without a challenge, or Keystore could not attest it.
     */
    fun parentKeyAttestationChain(): List<ByteArray>

    /**
     * A new [Signature] initialised with the parent private key and ready for a `BiometricPrompt.CryptoObject`.
     * Call it once per event: the key authorises exactly one signing operation per prompt.
     *
     * Throws [ParentKeyInvalidatedException] when biometric enrolment changed since the key was created (renew the
     * key through the PIN-gated renewal flow) and [IllegalStateException] when no key exists.
     */
    fun initSignature(): Signature

    /** Deletes the parent key. Only the PIN-gated renewal and enrolment flows call this. */
    suspend fun resetParentKey()
}

/** [ParentAuthorizer] over the Keystore [ParentAuthKeyStore], gated by [ParentAuthAvailabilityChecker]. */
class KeystoreParentAuthorizer(
    private val keyStore: ParentAuthKeyStore,
    private val availabilityChecker: ParentAuthAvailabilityChecker,
) : ParentAuthorizer {
    override fun availability(): ParentAuthAvailability = availabilityChecker.availability()

    override fun existingParentPublicKey(): PublicKeyBytes? = keyStore.publicKey()

    override suspend fun createParentKey(attestationChallenge: ByteArray?): PublicKeyBytes {
        keyStore.publicKey()?.let { return it }
        val availability = availability()
        if (availability != ParentAuthAvailability.STRONG_BIOMETRIC_AVAILABLE) {
            throw ParentAuthUnavailableException(availability)
        }
        return keyStore.ensureKey(attestationChallenge)
    }

    override fun parentKeyAttestationChain(): List<ByteArray> = keyStore.attestationChain()

    override fun initSignature(): Signature = keyStore.initSignature()

    override suspend fun resetParentKey() = keyStore.deleteKey()
}

/**
 * Turns an authenticated [Signature] (from a successful `BiometricPrompt` whose `CryptoObject` wrapped
 * [ParentAuthorizer.initSignature]) into a parent signature.
 */
class ParentSigner @Inject constructor() {
    /**
     * ECDSA P-256 / SHA-256 DER signature over [payload]: the same domain-separated bytes the device signature
     * covers (ADR-0004 section 5). The Keystore call is blocking IPC, so call it off the main thread.
     *
     * Throws [IllegalArgumentException] when [authorizedSignature] is not `SHA256withECDSA`, and
     * [java.security.SignatureException] (or another [GeneralSecurityException]) when it was not initialised for
     * signing, was not authorised by a prompt, or was already used once.
     */
    fun sign(authorizedSignature: Signature, payload: ByteArray): ByteArray {
        require(authorizedSignature.algorithm == CryptoAlgorithms.SIGNATURE) { "Parent signatures are SHA256withECDSA" }
        authorizedSignature.update(payload)
        return authorizedSignature.sign()
    }
}

/**
 * True only when [signature] is a valid parent signature by [parentPublicKey] over [payload]. Never throws on
 * malformed input (see [JceEcdsaVerifier]). M3 receivers check [parentPublicKey] against the household's
 * registered parent keys before trusting the result.
 */
fun verifyParentSignature(
    parentPublicKey: PublicKeyBytes,
    payload: ByteArray,
    signature: ByteArray,
    verifier: Verifier = JceEcdsaVerifier(),
): Boolean = verifier.verify(parentPublicKey, payload, signature)

/**
 * The parent key cannot be created or used on this device; [availability] says why. Data-layer use cases treat it
 * as "this device cannot sign" and fall back per the household policy (D-M2-1).
 */
open class ParentAuthUnavailableException(
    val availability: ParentAuthAvailability,
    cause: Throwable? = null,
    message: String = "Parent authentication is unavailable: $availability",
) : GeneralSecurityException(message, cause)

/**
 * The household has no registered parent key, so a parent signature could not be checked against anything and is
 * not produced (an event signed by an unregistered key counts as unsigned). A [ParentAuthUnavailableException], so
 * the D-M2-1 fallback applies: with no registered key the policy does not require signatures.
 */
class ParentKeyNotRegisteredException(availability: ParentAuthAvailability) :
    ParentAuthUnavailableException(availability, message = "No parent key is registered for this household")

/**
 * The parent key was permanently invalidated because biometric enrolment changed (a fingerprint was added or
 * removed). The old key is unusable: the parent renews it with the PIN (`feature/lock` `ParentKeyRenewal`), which
 * creates a new key and replaces the registered one. UIs show "Fingerprint changed: renew the signing key in
 * Settings".
 */
open class ParentKeyInvalidatedException(
    cause: Throwable? = null,
    message: String = "Parent key was invalidated by a biometric enrolment change",
) : GeneralSecurityException(message, cause)

/**
 * This device's parent key is missing or is not byte-equal to the household's registered parent key, so it cannot
 * sign for the household (audit H1). Fails closed: no prompt, no signature. A [ParentKeyInvalidatedException]
 * because the recovery is the same PIN-gated renewal.
 */
class ParentKeyMismatchException :
    ParentKeyInvalidatedException(message = "This device's parent key does not match the registered parent key")
