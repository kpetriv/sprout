package dev.kpetriv.sprout.core.domainledger

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
 * Only the signature may differ (ECDSA signatures are randomised, so a re-signed copy is still the same event).
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
 * - when copies conflict and **exactly one** is authored by [Role.PARENT] while every other copy is authored by
 *   [Role.KID], the parent's copy is applied and the kid copies are excluded. A kid device that reuses a parent's
 *   eventId (seen on a shared tablet, or replayed from sync) therefore cannot cancel the parent's event;
 * - otherwise (all copies share a role, or several parent copies) **none** is applied.
 *
 * Every conflict is still reported as a [LedgerAnomaly.ConflictingDuplicate] listing all copies. The rule depends
 * only on the set of copies, never on arrival order, so every device converges. Until receivers verify the parent
 * signature (ADR-0004, schema version 2), `authorRole` is self-declared: a kid device that also claims PARENT on its
 * copy makes two parent copies, and then nothing is applied.
 */
fun appliedCopyOf(copies: List<LedgerEvent>): LedgerEvent? = resolveConflict(copies) { it.envelope.authorRole }

/** [appliedCopyOf] over any copy representation; [role] reads the author role of a copy. */
internal inline fun <T> resolveConflict(copies: List<T>, role: (T) -> Role): T? {
    if (copies.size <= 1) return copies.singleOrNull()
    val parents = copies.filter { role(it) == Role.PARENT }
    val kids = copies.count { role(it) == Role.KID }
    return parents.singleOrNull()?.takeIf { kids == copies.size - 1 }
}

/**
 * An immutable set of events keyed by [EventId] that compares content, not just ids:
 * - adding the same event again (see [isSameEventAs]; a re-signed copy counts as the same) is a no-op, which makes
 *   outbox retries and duplicate pulls safe;
 * - adding a different event under an id already present records a **conflict**: every distinct copy is kept, the
 *   id is listed in [conflictingIds], and [Ledger.project] of [sorted] reports [LedgerAnomaly.ConflictingDuplicate]
 *   and applies at most [appliedCopyOf] (the single parent copy when every other copy is a kid's; otherwise none).
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
