package dev.kpetriv.sprout.core.domainledger

import dev.kpetriv.sprout.core.model.DeviceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IngestOrderingTest {
    private val parent = DeviceWriter(Fixtures.parentPhone, seed = 1)
    private val kid = DeviceWriter(Fixtures.kidTablet, seed = 2)

    /** Heads of what the receiver has stored, per author. */
    private val stored = mutableMapOf<DeviceId, StreamHead>()

    private fun plan(batch: List<LedgerEvent>): IngestPlan = IngestOrdering.plan(batch) { stored[it] }

    /** Records [plan]'s applied events as stored, as the data layer would. */
    private fun store(plan: IngestPlan) = plan.apply.forEach {
        stored[it.envelope.authorDeviceId] = StreamHead(it.envelope.hash, it.lamport)
    }

    @Test
    fun anInOrderStreamFromGenesisIsAppliedWhole() {
        val events = listOf(kid.game(1), kid.game(2), kid.purchase(3))
        assertEquals(IngestPlan(apply = events, held = emptyList()), plan(events))
    }

    @Test
    fun aStreamContinuesFromTheStoredHead() {
        val first = kid.game(1)
        stored[first.envelope.authorDeviceId] = StreamHead(first.envelope.hash, first.lamport)
        val rest = listOf(kid.game(2), kid.game(3))
        assertEquals(IngestPlan(rest, emptyList()), plan(rest))

        // An event that restarts from genesis after something is stored does not link.
        val restarted = DeviceWriter(Fixtures.kidTablet, seed = 9).game(4)
        assertEquals(IngestPlan(emptyList(), listOf(restarted)), plan(listOf(restarted)))
    }

    @Test
    fun outOfOrderEventsWithinABatchAreSortedIntoChainOrder() {
        val events = listOf(kid.game(1), kid.game(2), kid.game(3), kid.game(4))
        assertEquals(IngestPlan(events, emptyList()), plan(events.reversed()))
        assertEquals(IngestPlan(events, emptyList()), plan(listOf(events[2], events[0], events[3], events[1])))
    }

    @Test
    fun aGapHoldsEverythingAfterItUntilItIsFilled() {
        val e1 = kid.game(1)
        val e2 = kid.game(2)
        val e3 = kid.game(3)
        val e4 = kid.game(4)

        val first = plan(listOf(e1, e3, e4))
        assertEquals(IngestPlan(listOf(e1), listOf(e3, e4)), first)
        store(first)

        // Next ingest: the held events are retried together with the newly pulled missing link.
        val second = plan(first.held + e2)
        assertEquals(IngestPlan(listOf(e2, e3, e4), emptyList()), second)
        store(second)
        assertEquals(StreamHead(e4.envelope.hash, e4.lamport), stored[Fixtures.kidTablet.deviceId])
    }

    @Test
    fun aMissingFirstEventHoldsTheWholeStream() {
        kid.game(1)
        val later = listOf(kid.game(2), kid.game(3))
        assertEquals(IngestPlan(emptyList(), later), plan(later))
    }

    @Test
    fun twoAuthorsAreWalkedSeparatelyAndInterleavedInTotalOrder() {
        val p1 = parent.chore(1)
        val k1 = kid.game(1)
        val k2 = kid.game(2)
        val p2 = parent.chore(2)
        val p3 = parent.chore(3)
        val k3 = kid.game(3)
        // The kid's stream has a gap (k2 missing); the parent's does not.
        val result = plan(listOf(p3, k3, p1, k1, p2))

        assertEquals(listOf(p1, k1, p2, p3).sortedWith(LedgerOrdering), result.apply)
        assertEquals(listOf(k3), result.held)
        // Lamports tie across authors (p1 and k1 are both 1); the total order breaks the tie by device id.
        assertEquals(listOf(k1, p1, p2, p3), result.apply)
        assertTrue(k2.lamport < k3.lamport)
    }

    @Test
    fun ofAForkTheFirstByLamportAndEventIdIsAppliedAndTheOtherHeld() {
        val root = kid.game(1)
        val branchA = kid.game(2)
        // Another event chained to the same head at the same lamport (a cloned or replayed device state).
        val branchB = forkOf(branchA, EventId("00000000-0000-4000-8000-000000000000"))
        assertEquals(branchA.envelope.prevHash, branchB.envelope.prevHash)
        assertEquals(branchA.lamport, branchB.lamport)

        val (winner, loser) = listOf(branchA, branchB).sortedBy { it.eventId.value }
        val result = plan(listOf(branchB, root, branchA))
        assertEquals(IngestPlan(listOf(root, winner), listOf(loser)), result)
        // Arrival order does not matter.
        assertEquals(result, plan(listOf(branchA, branchB, root)))

        // A fork at a higher lamport loses to the lower one regardless of id.
        val late = forkOf(branchA, EventId("00000000-0000-4000-8000-000000000001"), lamport = branchA.lamport + 5)
        assertEquals(IngestPlan(listOf(root, branchA), listOf(late)), plan(listOf(late, branchA, root)))
    }

    @Test
    fun aLamportThatDoesNotIncreaseIsHeldEvenIfItLinks() {
        val first = kid.game(1)
        stored[first.envelope.authorDeviceId] = StreamHead(first.envelope.hash, first.lamport + 10)
        val next = kid.game(2)
        assertEquals(IngestPlan(emptyList(), listOf(next)), plan(listOf(next)))
    }

    @Test
    fun identicalCopiesInABatchAreAppliedOnce() {
        val game = kid.game(1)
        val resigned = game.withSignature(byteArrayOf(1))
        assertEquals(IngestPlan(listOf(game), emptyList()), plan(listOf(game, resigned, game)))
    }

    @Test
    fun anEmptyBatchPlansNothing() {
        assertEquals(IngestPlan(emptyList(), emptyList()), plan(emptyList()))
    }

    /** A different game chained to the same head as [event], with [id] and [lamport], correctly hashed. */
    private fun forkOf(event: GamePlayed, id: EventId, lamport: Long = event.lamport): LedgerEvent {
        val changed = event.copy(amount = Money(5)).mutateEnvelope { it.copy(eventId = id, lamport = lamport) }
        return changed.mutateEnvelope { it.copy(hash = HashChain().hashOf(changed)) }
    }
}
