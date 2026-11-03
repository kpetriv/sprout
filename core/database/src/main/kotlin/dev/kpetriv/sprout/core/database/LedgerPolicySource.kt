package dev.kpetriv.sprout.core.database

import dev.kpetriv.sprout.core.crypto.PublicKeyBytes
import dev.kpetriv.sprout.core.crypto.Verifier
import dev.kpetriv.sprout.core.crypto.verifyParentSignature
import dev.kpetriv.sprout.core.domainledger.LedgerPolicy

/** Checks a parent signature against the household's registered parent key. Never throws. */
internal fun interface ParentSignatureVerifier {
    fun verify(payload: ByteArray, signature: ByteArray): Boolean
}

/**
 * The ledger policy in force for one transaction, and the registered parent key's verifier (null while no parent key
 * is registered).
 */
internal class ActivePolicy(val policy: LedgerPolicy, val parentKey: ParentSignatureVerifier?)

/** Where [RoomLedgerRepository] gets its policy. [load] runs inside the repository's transaction. */
internal fun interface LedgerPolicySource {
    suspend fun load(): ActivePolicy
}

/**
 * The production [LedgerPolicySource]: [LedgerPolicy] defaults, with `requireParentSignature` and
 * `parentSignatureRequiredFromLamport` from `household_policy`, and the stored parent public key for verification.
 * Without a policy row nothing requires a parent signature.
 */
internal class HouseholdPolicySource(private val database: SproutDatabase, private val verifier: Verifier) :
    LedgerPolicySource {
    override suspend fun load(): ActivePolicy {
        val row = database.householdPolicyDao().get() ?: return ActivePolicy(LedgerPolicy(), null)
        val policy = LedgerPolicy(
            requireParentSignature = row.requireParentSignature,
            parentSignatureRequiredFromLamport = row.parentSignatureFromLamport,
        )
        val key = row.parentPublicKey?.let(::PublicKeyBytes)
        return ActivePolicy(
            policy,
            key?.let {
                ParentSignatureVerifier { payload, signature ->
                    verifyParentSignature(it, payload, signature, verifier)
                }
            },
        )
    }
}
