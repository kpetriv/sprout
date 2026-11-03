package dev.kpetriv.sprout.core.domainledger

import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.Role

/**
 * The total order events are folded in (ADR-0002): `(lamport, authorDeviceId, eventId)`, compared as numbers and
 * then as strings. Deterministic on every device; `createdAt` is never consulted because device clocks are
 * untrusted.
 */
object LedgerOrdering : Comparator<LedgerEvent> {
    override fun compare(a: LedgerEvent, b: LedgerEvent): Int = compareValuesBy(
        a,
        b,
        { it.envelope.lamport },
        { it.envelope.authorDeviceId.value },
        { it.envelope.eventId.value },
    )
}

/**
 * True when [other] is the same event as this one: the same eventId, the same stored hash and the same content.
 * Only the device signature may differ (ECDSA signatures are randomised, so a re-signed copy is still the same event).
 * The parent signature does count: it cannot be re-made without a new parent prompt, so a copy with a stripped or
 * different [Envelope.parentSignature] is a conflict, which no device applies, rather than a duplicate whose fate
 * would depend on which copy arrived first.
 * Two copies of one eventId that are not the same event are a conflict (see [LedgerAnomaly.ConflictingDuplicate]).
 */
fun LedgerEvent.isSameEventAs(other: LedgerEvent): Boolean = eventId == other.eventId &&
    envelope.hash == other.envelope.hash &&
    withoutSignature() == other.withoutSignature()

private fun LedgerEvent.withoutSignature(): LedgerEvent =
    if (envelope.signature == null) this else withEnvelope(envelope.copy(signature = null))

/**
 * The copy of one eventId that projections apply, given every distinct copy of it ([isSameEventAs] tells copies
 * apart), or null when none is applied:
 * - a single copy is applied;
 * - when copies conflict and **exactly one** comes from a device whose role [roleOf] reports as [Role.PARENT]
 *   while [roleOf] reports [Role.KID] for every other copy's device, the parent device's copy is applied and the
 *   kid copies are excluded. A kid device that reuses a parent's eventId (seen on a shared tablet, or replayed from
 *   sync) therefore cannot cancel the parent's event;
 * - otherwise (all copies share a role, several parent copies, or any copy whose device role is unknown) **none**
 *   is applied.
 *
 * [roleOf] must answer from the household's device registry (ADR-0004 M2-review amendment, M3 §2), never from the
 * envelope's self-declared `authorRole`: a kid device that writes `authorRole = PARENT` on its copy is still a KID
 * device. Pulled parent-only copies only get this far when [RemoteEventVerifier] verified their parent signature.
 *
 * Every conflict is still reported as a [LedgerAnomaly.ConflictingDuplicate] listing all copies. The rule depends
 * only on the set of copies and the registry, never on arrival order, so every device converges.
 */
fun appliedCopyOf(copies: List<LedgerEvent>, roleOf: (DeviceId) -> Role?): LedgerEvent? =
    resolveConflict(copies) { roleOf(it.envelope.authorDeviceId) }

/** [appliedCopyOf] over any copy representation; [role] reads the verified author role of a copy (null: unknown). */
internal inline fun <T> resolveConflict(copies: List<T>, role: (T) -> Role?): T? {
    if (copies.size <= 1) return copies.singleOrNull()
    val roles = copies.map(role)
    val parents = roles.count { it == Role.PARENT }
    val kids = roles.count { it == Role.KID }
    return if (parents == 1 && kids == copies.size - 1) copies[roles.indexOf(Role.PARENT)] else null
}

/**
 * An immutable set of events keyed by [EventId] that compares content, not just ids:
 * - adding the same event again (see [isSameEventAs]; a re-signed copy counts as the same) is a no-op, which makes
 *   outbox retries and duplicate pulls safe;
 * - adding a different event under an id already present records a **conflict**: every distinct copy is kept, the
 *   id is listed in [conflictingIds], and [Ledger.project] of [sorted] reports [LedgerAnomaly.ConflictingDuplicate]
 *   and applies at most [appliedCopyOf] (the single copy from a PARENT device when every other copy comes from a
 *   KID device; otherwise none).
 *   The choice depends only on the copies, never on arrival order, so every device converges.
 */
class LedgerEventSet private constructor(private val byId: Map<EventId, List<LedgerEvent>>) {

    /** Number of distinct eventIds (a conflicting id counts once). */
    val size: Int get() = byId.size

    /** Ids that more than one distinct event claims. */
    val conflictingIds: Set<EventId> get() = byId.filterValues { it.size > 1 }.keys

    operator fun contains(eventId: EventId): Boolean = eventId in byId

    /** True when this set holds [event] itself (or a re-signed copy of it). */
    fun containsEvent(event: LedgerEvent): Boolean = byId[event.eventId].orEmpty().any { it.isSameEventAs(event) }

    /** The event stored under [eventId]; null if absent or if the id is conflicting (see [copiesOf]). */
    operator fun get(eventId: EventId): LedgerEvent? = byId[eventId]?.singleOrNull()

    /** Every distinct copy stored under [eventId], in the order they were added; empty if absent. */
    fun copiesOf(eventId: EventId): List<LedgerEvent> = byId[eventId].orEmpty()

    /** This set plus [event]: unchanged if it is already present, a conflict if its id is taken by another event. */
    operator fun plus(event: LedgerEvent): LedgerEventSet = plus(listOf(event))

    operator fun plus(events: Iterable<LedgerEvent>): LedgerEventSet {
        val merged = LinkedHashMap(byId)
        var changed = false
        for (event in events) {
            val copies = merged[event.eventId].orEmpty()
            if (copies.none { it.isSameEventAs(event) }) {
                merged[event.eventId] = copies + event
                changed = true
            }
        }
        return if (changed) LedgerEventSet(merged) else this
    }

    /** Every stored copy, conflicting ones included, in [LedgerOrdering] (copies of one id then by hash). */
    fun sorted(): List<LedgerEvent> = byId.values.flatten().sortedWith(LedgerOrdering.thenBy { it.envelope.hash })

    /** Equal when both hold the same events; which re-signed copy of an event was kept does not matter. */
    override fun equals(other: Any?): Boolean = other is LedgerEventSet && other.contentKey() == contentKey()

    override fun hashCode(): Int = contentKey().hashCode()

    private fun contentKey(): Map<EventId, Set<LedgerEvent>> =
        byId.mapValues { (_, copies) -> copies.map { it.withoutSignature() }.toSet() }

    override fun toString(): String = "LedgerEventSet(size=$size, conflicts=${conflictingIds.size})"

    companion object {
        val EMPTY = LedgerEventSet(emptyMap())

        fun of(events: Iterable<LedgerEvent>): LedgerEventSet = EMPTY + events
    }
}
