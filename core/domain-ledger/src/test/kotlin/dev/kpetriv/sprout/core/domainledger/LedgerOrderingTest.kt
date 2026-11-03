package dev.kpetriv.sprout.core.domainledger

import dev.kpetriv.sprout.core.model.DeviceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days

class LedgerOrderingTest {
    @Test
    fun ordersByLamportThenDeviceThenEventId() {
        val a = DeviceWriter(Fixtures.parentPhone, seed = 1)
        val b = DeviceWriter(Fixtures.kidTablet, seed = 2)
        val a1 = a.chore(1)
        val b1 = b.game(1)
        val b2 = b.game(1)
        val a2 = a.chore(1)
        // Lamports: a1=1, b1=1, b2=2, a2=2. Ties break on device id ("kid-tablet" < "parent-phone").
        assertEquals(listOf(b1, a1, b2, a2), listOf(a2, b2, a1, b1).sortedWith(LedgerOrdering))

        val sameKey = a1.mutateEnvelope { it.copy(eventId = EventId("00000000-0000-4000-8000-000000000000")) }
        assertTrue(LedgerOrdering.compare(sameKey, a1) < 0)
    }

    @Test
    fun createdAtIsNeverUsedForOrdering() {
        val writer = DeviceWriter(Fixtures.kidTablet)
        val first = writer.game(1)
        val second = writer.game(1)
        val backdated = second.mutateEnvelope { it.copy(createdAt = it.createdAt - 1000.days) }
        assertEquals(listOf(first, backdated), listOf(backdated, first).sortedWith(LedgerOrdering))
    }

    @Test
    fun eventSetIgnoresCopiesOfTheSameEvent() {
        val writer = DeviceWriter(Fixtures.parentPhone)
        val chore = writer.chore(10)
        val allowance = writer.allowance(20)
        val set = LedgerEventSet.of(listOf(chore, allowance))
        assertSame(set, set + chore)
        assertSame(set, set + chore.withSignature(byteArrayOf(1)))
        assertSame(set, set + listOf(chore, allowance))
        assertEquals(2, set.size)
        assertEquals(chore, set[chore.eventId])
        assertTrue(allowance.eventId in set)
        assertTrue(set.containsEvent(chore.withSignature(byteArrayOf(2))))
        assertEquals(emptySet(), set.conflictingIds)
        assertEquals(listOf(chore, allowance), (LedgerEventSet.EMPTY + allowance + chore + allowance).sorted())
        assertEquals(set, LedgerEventSet.of(listOf(allowance, chore, chore)))
        // Which re-signed copy was kept does not affect equality.
        assertEquals(set, LedgerEventSet.of(listOf(chore.withSignature(byteArrayOf(3)), allowance)))
    }

    @Test
    fun eventSetKeepsEveryDistinctCopyOfAConflictingId() {
        val parent = DeviceWriter(Fixtures.parentPhone)
        val kid = DeviceWriter(Fixtures.kidTablet)
        val chore = parent.chore(10)
        val reused = kid.writeWithId(chore.eventId, Fixtures.main) { RewardPurchased(it, Fixtures.gbp, Money(1), "r") }
        val set = LedgerEventSet.of(listOf(chore, reused))

        assertEquals(1, set.size)
        assertEquals(setOf(chore.eventId), set.conflictingIds)
        assertNull(set[chore.eventId])
        // The parent device's copy is the one projections apply; the kid device's reuse is excluded.
        assertEquals(chore, appliedCopyOf(set.copiesOf(chore.eventId), Fixtures::roleOf))
        assertEquals(listOf(chore, reused), set.copiesOf(chore.eventId))
        assertEquals(listOf(reused, chore), set.sorted())
        assertFalse(set.containsEvent(chore.copy(amount = Money(11))))
        assertEquals(set, LedgerEventSet.of(listOf(reused, chore, reused)))
        assertSame(set, set + reused)
        assertTrue(chore.isSameEventAs(chore.withSignature(byteArrayOf(9))))
        assertFalse(chore.isSameEventAs(reused))
    }

    @Test
    fun deviceIdTieBreakIsByString() {
        assertTrue(DeviceId("a").value < DeviceId("b").value)
    }
}
