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
 * The ledger policy in force for one transaction, the registered parent key's verifier for **local** appends (null
 * while this device has registered none), and the author-device roles projections resolve conflicts with ([roles]).
 */
internal class ActivePolicy(
    val policy: LedgerPolicy,
    val parentKey: ParentSignatureVerifier?,
    val roles: RoleLookup = RoleLookup.LOCAL,
)

/** Where [RoomLedgerRepository] gets its policy. [load] runs inside the repository's transaction. */
internal fun interface LedgerPolicySource {
    suspend fun load(): ActivePolicy
}

/**
 * The production [LedgerPolicySource]: [LedgerPolicy] defaults, with `requireParentSignature` and
 * `parentSignatureRequiredFromLamport` from `household_policy`, and the stored parent public key for verifying parent
 * signatures on events appended on this device. Without a policy row nothing requires a parent signature.
 *
 * Remote events never use this key: `RemoteEventVerifier` checks their parent signatures against every registered
 * parent key in the registry (`parent_keys`), with no from-lamport exemption (M3 §2). [ActivePolicy.roles] comes
 * from the registry ([RoomDeviceRegistry.roleLookup]): registry roles once any device is registered, envelope roles
 * only for the pre-linking local household.
 */
internal class HouseholdPolicySource(private val database: SproutDatabase, private val verifier: Verifier) :
    LedgerPolicySource {
    private val registry = RoomDeviceRegistry(database)

    override suspend fun load(): ActivePolicy {
        val roles = registry.roleLookup()
        val row = database.householdPolicyDao().get() ?: return ActivePolicy(LedgerPolicy(), null, roles)
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
            roles,
        )
    }
}
