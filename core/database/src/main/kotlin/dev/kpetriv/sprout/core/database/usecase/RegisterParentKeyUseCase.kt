package dev.kpetriv.sprout.core.database.usecase

import dev.kpetriv.sprout.core.crypto.PublicKeyBytes
import dev.kpetriv.sprout.core.database.HouseholdPolicy
import dev.kpetriv.sprout.core.database.HouseholdPolicyRepository
import java.security.GeneralSecurityException
import java.security.interfaces.ECPublicKey
import javax.inject.Inject

/**
 * Registers a parent authentication public key with the household policy (ADR-0004) and turns
 * `requireParentSignature` on from the next lamport (a replacement keeps the original from-lamport).
 *
 * It never creates a key: the key is created only by `feature/lock`'s PIN-gated enrolment and renewal flows
 * (ADR-0004 amendments, no lazy creation), which then hand the public key to [register].
 */
class RegisterParentKeyUseCase @Inject constructor(private val policies: HouseholdPolicyRepository) {
    /**
     * Registers [publicKeyDer] (X.509 SPKI DER). Throws [IllegalArgumentException] unless it is an EC P-256 public
     * key, so a malformed key can never turn the signature requirement on with nothing able to satisfy it.
     */
    suspend fun register(publicKeyDer: ByteArray): HouseholdPolicy {
        val key = try {
            PublicKeyBytes(publicKeyDer).toPublicKey()
        } catch (e: GeneralSecurityException) {
            throw IllegalArgumentException("not an EC public key", e)
        }
        require(key is ECPublicKey && key.params.curve.field.fieldSize == P256_FIELD_BITS) { "not a P-256 key" }
        return policies.registerParentKey(publicKeyDer)
    }

    private companion object {
        const val P256_FIELD_BITS = 256
    }
}
