package dev.kpetriv.sprout.core.domainledger

import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.KidId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * [LedgerRepository] built only from the domain parts, holding events in memory. The reference implementation for
 * [LedgerRepositoryContract] and a fake for tests that do not need storage. Not thread-safe.
 *
 * Projections always fold every stored event (all kids), so an eventId reused across kids is still a conflict, and
 * reads filter the result per kid.
 *
 * @param policy used for validation and every projection (for example [LedgerPolicy.requireParentSignature]).
 *   Projections trust the envelopes' self-declared roles (`trustEnvelopeRoles = true`), since there is no registry.
 */
class InMemoryLedgerRepository(
    private val accounts: List<Account>,
    private val policy: LedgerPolicy = LedgerPolicy(),
) : LedgerRepository {
    private val events = MutableStateFlow(LedgerEventSet.EMPTY)
    private val chain = HashChain()

    // Every event here was written on this "device" by the test itself, so the envelope roles are the truth
    // (there is no device registry): opt in to them for parent-wins conflict resolution.
    private fun projection(set: LedgerEventSet = events.value) =
        Ledger.project(set.sorted(), accounts, policy, trustEnvelopeRoles = true)

    override fun observeBalances(kidId: KidId): Flow<List<Balance>> = events.map { set ->
        val ids = accounts.filter { it.kidId == kidId }.map { it.id }.toSet()
        projection(set).balances.values.filter { it.accountId in ids }
    }

    override fun observeAnomalies(kidId: KidId): Flow<List<LedgerAnomaly>> =
        events.map { set -> projection(set).anomaliesFor(kidId) }

    override fun observeEvents(kidId: KidId, limit: Int): Flow<List<LedgerEvent>> = events.map { set ->
        set.sorted().filter { it.envelope.kidId == kidId }.asReversed().take(limit)
    }

    override suspend fun append(event: LedgerEvent): AppendResult {
        val set = events.value
        val stored = set.copiesOf(event.eventId)
        if (stored.any { it.isSameEventAs(event) }) return AppendResult.Duplicate
        val head = head(set, event.envelope.authorDeviceId)
        val chained = chain.verifyChain(listOf(event), head?.envelope?.hash, head?.lamport)
        val validation = if (stored.isEmpty()) LedgerRules.validate(event, projection(set)) else ValidationResult.Valid
        return when {
            chained !is ChainVerification.Valid -> AppendResult.Rejected(LedgerRepositoryContract.CHAIN_REJECTION)

            // Kept next to the stored copy; projections apply at most appliedCopyOf(copies).
            stored.isNotEmpty() -> {
                events.value = set + event
                AppendResult.Conflict(event.eventId, stored.first().envelope.hash, event.envelope.hash)
            }

            validation is ValidationResult.Invalid ->
                AppendResult.Rejected(LedgerRepositoryContract.INVARIANT_REJECTION, validation.violations)

            else -> AppendResult.Accepted.also { events.value = set + event }
        }
    }

    override suspend fun latestLamport(): Long = events.value.sorted().maxOfOrNull { it.lamport } ?: 0

    override suspend fun headHash(authorDeviceId: DeviceId): String? =
        head(events.value, authorDeviceId)?.envelope?.hash

    /** The newest stored event by [authorDeviceId], conflicting copies included (they are chained). */
    private fun head(set: LedgerEventSet, authorDeviceId: DeviceId): LedgerEvent? =
        set.sorted().lastOrNull { it.envelope.authorDeviceId == authorDeviceId }
}
