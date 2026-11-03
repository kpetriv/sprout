package dev.kpetriv.sprout.core.database

import dev.kpetriv.sprout.core.crypto.ParentSigner
import dev.kpetriv.sprout.core.domainledger.ParentAuthorization
import java.security.Signature
import javax.inject.Inject

/**
 * Turns the authenticated [Signature] of a strong-biometric `BiometricPrompt` into a [ParentAuthorization] for
 * [RoomLedgerWriter.appendAsParent] and the use cases (ADR-0004 section 1).
 *
 * The feature layer calls `ParentAuthorizer.initSignature()` once per event, prompts with
 * `BiometricPrompt.CryptoObject(signature)` and `BIOMETRIC_STRONG`, and passes the prompt result's signature to
 * [from]. The result signs exactly one payload: the Keystore operation is single-use, so a second
 * [ParentAuthorization.sign] fails with a `SignatureException`.
 */
class ParentAuthorizations @Inject constructor(private val signer: ParentSigner) {
    /** An authorisation backed by [authenticated], which a successful prompt unlocked. */
    fun from(authenticated: Signature): ParentAuthorization = SignatureParentAuthorization(authenticated, signer)
}

/** [ParentAuthorization] over one authenticated `SHA256withECDSA` [Signature], via [ParentSigner]. */
internal class SignatureParentAuthorization(private val signature: Signature, private val signer: ParentSigner) :
    ParentAuthorization {
    override fun sign(payload: ByteArray): ByteArray = signer.sign(signature, payload)
}
