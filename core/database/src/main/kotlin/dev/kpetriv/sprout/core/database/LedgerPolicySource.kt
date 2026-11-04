package dev.kpetriv.sprout.core.database

import dev.kpetriv.sprout.core.crypto.PublicKeyBytes
import dev.kpetriv.sprout.core.crypto.Verifier
import dev.kpetriv.sprout.core.crypto.verifyParentSignature
import dev.kpetriv.sprout.core.domainledger.LedgerPolicy
import dev.kpetriv.sprout.core.domainledger.Money

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
 * The production [LedgerPolicySource]: [LedgerPolicy] defaults, with `requireParentSignature`,
 * `parentSignatureRequiredFromLamport` and the daily game cap from `household_policy`, and the stored parent public key
 * for verifying parent signatures on events appended on this device. Without a policy row nothing requires a parent
 * signature and the cap is the default.
 *
 * The cap is the household's **synced** setting (`dailyGameCapMinor`, D-M5-4, D-M7-10), so every device folds the
 * same games under the same cap and projects the same exclusions (ADR-0009). It applies to every `playedOn` date:
 * changing it re-decides past days too (the caches are rebuilt when it changes, D-M7-11).
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
            dailyGameCap = row.dailyGameCapMinor?.let(::Money) ?: LedgerPolicy.DEFAULT_DAILY_GAME_CAP,
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
