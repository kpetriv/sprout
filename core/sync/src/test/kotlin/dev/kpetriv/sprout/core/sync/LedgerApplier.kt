package dev.kpetriv.sprout.core.sync

import dev.kpetriv.sprout.core.domainledger.EventId
import dev.kpetriv.sprout.core.domainledger.Fixtures
import dev.kpetriv.sprout.core.domainledger.IngestOrdering
import dev.kpetriv.sprout.core.domainledger.LamportClock
import dev.kpetriv.sprout.core.domainledger.Ledger
import dev.kpetriv.sprout.core.domainledger.LedgerEvent
import dev.kpetriv.sprout.core.domainledger.LedgerJson
import dev.kpetriv.sprout.core.domainledger.LedgerPolicy
import dev.kpetriv.sprout.core.domainledger.Projection
import dev.kpetriv.sprout.core.domainledger.QuarantineReason
import dev.kpetriv.sprout.core.domainledger.RegisteredDevice
import dev.kpetriv.sprout.core.domainledger.RegisteredParentKey
import dev.kpetriv.sprout.core.domainledger.RemoteEventVerifier
import dev.kpetriv.sprout.core.domainledger.StreamHead
import dev.kpetriv.sprout.core.domainledger.VerifiedRemote
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.ThreadId
import kotlinx.serialization.json.JsonObject
import kotlin.time.Instant

/**
 * One device's local store for engine-level convergence tests: documents go through [RecordingApplier] (the real
 * [RemoteDocumentVerifier] and [ConflictResolver]); ledger events go through the real [RemoteEventVerifier] and
 * [IngestOrdering], with a minimal in-memory stand-in for core/database's ingest (stored events, `ledger_inbox`,
 * quarantine, fork handling). Balances come from [Ledger.project] over the applied events with registry roles and
 * the server stamps the events were pulled with.
 *
 * Remote lamports are observed by [lamport], the clock the device's own `DeviceWriter` writes with.
 */
internal class LedgerApplier(
    householdId: HouseholdId,
    private val policy: LedgerPolicy,
    private val lamport: LamportClock,
) : RemoteApplier {
    val documents = RecordingApplier(SoftwareSignatureCheck, householdId, policy.maxLamportJump)
    private val verifier = RemoteEventVerifier(documents, SoftwareSignatureCheck, policy)

    private class StoredEvent(val event: LedgerEvent, var quarantine: QuarantineReason?)

    private val stored = LinkedHashMap<Pair<EventId, String>, StoredEvent>()
    private val inbox = LinkedHashMap<Pair<EventId, String>, PulledEvent>()
    private val stamps = LinkedHashMap<String, Instant>()
    private val undecodable = mutableListOf<JsonObject>()

    /** The device's own write, stored as applied (an outbox row is queued by the caller). */
    fun recordLocal(event: LedgerEvent) = synchronized(this) {
        stored[event.key] = StoredEvent(event, null)
    }

    /** Keys `(eventId, hash)` of applied (not quarantined) events. */
    fun appliedKeys(): Set<Pair<EventId, String>> = synchronized(this) {
        stored.filterValues { it.quarantine == null }.keys.toSet()
    }

    /** Quarantined events and why. */
    fun quarantined(): Map<Pair<EventId, String>, QuarantineReason> = synchronized(this) {
        stored.mapNotNull { (key, value) -> value.quarantine?.let { key to it } }.toMap()
    }

    /** Events waiting in the inbox (gap, unknown or pending author). */
    fun heldKeys(): Set<Pair<EventId, String>> = synchronized(this) { inbox.keys.toSet() }

    /** The projection of the applied events, judging conflicting copies by registered role. */
    suspend fun projection(): Projection {
        val events = synchronized(this) { stored.values.filter { it.quarantine == null }.map { it.event } }
        val roles = events.map { it.envelope.authorDeviceId }.toSet().associateWith { documents.device(it)?.role }
        val received = synchronized(this) { stamps.toMap() }
        return Ledger.project(events, Fixtures.accounts, policy, roles::get, receivedAt = received)
    }

    override suspend fun applyRegistry(devices: List<RegisteredDevice>, parentKeys: List<RegisteredParentKey>) =
        documents.applyRegistry(devices, parentKeys)

    override suspend fun applyDocuments(documents: List<DocumentEnvelope>): DocumentApplyReport =
        this.documents.applyDocuments(documents)

    override suspend fun applyEvents(events: List<PulledEvent>): EventApplyReport = ingest(events)

    override suspend fun applyMessages(messages: List<JsonObject>, stamps: Map<String, Instant>): MessageApplyReport =
        documents.applyMessages(messages, stamps)

    override suspend fun expectedSeq(deviceId: DeviceId, threadId: ThreadId): Long =
        documents.expectedSeq(deviceId, threadId)

    override suspend fun retryHeld(): EventApplyReport {
        documents.retryHeld()
        return ingest(emptyList())
    }

    override suspend fun cursor(): SyncCursor = documents.cursor()

    override suspend fun saveCursor(cursor: SyncCursor) = documents.saveCursor(cursor)

    override suspend fun localMaxLamport(): Long = maxOf(
        documents.localMaxLamport(),
        synchronized(this) { stored.values.filter { it.quarantine == null }.maxOfOrNull { it.event.lamport } ?: 0 },
    )

    private suspend fun ingest(pulled: List<PulledEvent>): EventApplyReport {
        val incoming = synchronized(this) { inbox.values.toList().also { inbox.clear() } } + pulled
        val localMax = localMaxLamport()
        var report = EventApplyReport()
        val ok = LinkedHashMap<Pair<EventId, String>, Pair<LedgerEvent, PulledEvent>>()
        for (item in incoming) {
            val json = item.json
            when (val verified = verifier.verify(json, localMax)) {
                is VerifiedRemote.Ok -> synchronized(this) {
                    val key = verified.event.key
                    if (key in stored || key in ok) {
                        report += EventApplyReport(duplicates = 1)
                    } else {
                        ok[key] = verified.event to item
                    }
                }

                is VerifiedRemote.Held -> synchronized(this) {
                    inbox[verified.eventId to verified.hash] = item
                    report += EventApplyReport(held = 1)
                }

                is VerifiedRemote.Quarantined -> synchronized(this) {
                    val event = LedgerJson.decodeOrNull(json)
                    when {
                        event == null -> undecodable += json
                        event.key !in stored -> stored[event.key] = StoredEvent(event, verified.reason)
                    }
                    report += EventApplyReport(quarantined = 1)
                }
            }
        }
        return report + synchronized(this) { applyPlanned(ok) }
    }

    private fun applyPlanned(ok: Map<Pair<EventId, String>, Pair<LedgerEvent, PulledEvent>>): EventApplyReport {
        val plan = IngestOrdering.plan(ok.values.map { it.first }, ::headOf, ::successorsOf)
        for (event in plan.apply) {
            stored[event.key] = StoredEvent(event, null)
            ok.getValue(event.key).second.serverReceivedAt?.let { stamps[event.envelope.hash] = it }
            lamport.observe(event.lamport)
        }
        plan.held.forEach { inbox[it.key] = ok.getValue(it.key).second }
        plan.forked.forEach { stored[it.key] = StoredEvent(it, QuarantineReason.FORKED_CHAIN) }
        quarantineStoredBranches(plan.forkedStored)
        return EventApplyReport(
            applied = plan.apply.size,
            held = plan.held.size,
            quarantined = plan.forked.size,
        )
    }

    /** Quarantines stored events with [hashes] and every stored event of their authors that descends from them. */
    private fun quarantineStoredBranches(hashes: Set<String>) {
        val forked = hashes.toMutableSet()
        var grew = forked.isNotEmpty()
        while (grew) {
            grew = false
            for (entry in stored.values) {
                val envelope = entry.event.envelope
                val onBranch = envelope.hash in forked || envelope.prevHash?.let { it in forked } == true
                if (onBranch && entry.quarantine == null) {
                    entry.quarantine = QuarantineReason.FORKED_CHAIN
                    grew = forked.add(envelope.hash) || grew
                }
            }
        }
    }

    private fun headOf(author: DeviceId): StreamHead? = stored.values
        .filter { it.quarantine == null && it.event.envelope.authorDeviceId == author }
        .maxByOrNull { it.event.lamport }
        ?.let { StreamHead(it.event.envelope.hash, it.event.lamport) }

    private fun successorsOf(author: DeviceId, prevHash: String?): Set<String> = stored.values
        .map { it.event.envelope }
        .filter { it.authorDeviceId == author && it.prevHash == prevHash }
        .mapTo(HashSet()) { it.hash }

    private val LedgerEvent.key: Pair<EventId, String> get() = eventId to envelope.hash
}
