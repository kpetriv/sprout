package dev.kpetriv.sprout.core.domainledger

import dev.kpetriv.sprout.core.model.AccountId
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.model.UserId
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

class HashChainTest {
    private val chain = HashChain()

    private fun stream(): List<LedgerEvent> {
        val writer = DeviceWriter(Fixtures.parentPhone)
        writer.chore(100)
        writer.allowance(200)
        writer.adjust(-50)
        writer.interest(3)
        writer.chore(75)
        return writer.written.toList()
    }

    private fun broken(
        events: List<LedgerEvent>,
        anchor: String? = null,
        anchorLamport: Long? = null,
    ): ChainVerification.Broken = assertIs<ChainVerification.Broken>(chain.verifyChain(events, anchor, anchorLamport))

    @Test
    fun sha256MatchesTheStandardVector() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            JvmSha256Hasher.sha256("abc".encodeToByteArray()).toHex(),
        )
    }

    @Test
    fun computeHashChainsFromGenesis() {
        val bytes = "payload".encodeToByteArray()
        val first = chain.computeHash(null, bytes)
        assertEquals(chain.computeHash(HashChain.GENESIS, bytes), first)
        assertEquals(64, first.length)
        assertEquals(false, first == chain.computeHash(first, bytes))
    }

    @Test
    fun aWellFormedStreamVerifies() {
        val events = stream()
        assertEquals(ChainVerification.Valid(events.last().envelope.hash, events.size), chain.verifyChain(events))
        assertEquals(ChainVerification.Valid(null, 0), chain.verifyChain(emptyList()))
    }

    @Test
    fun aContinuationVerifiesAgainstItsAnchor() {
        val events = stream()
        val tail = events.drop(2)
        assertIs<ChainVerification.Valid>(chain.verifyChain(tail, anchorHash = events[1].envelope.hash))
        assertIs<ChainBreak.MissingPrev>(broken(tail).reason)
    }

    @Test
    fun signaturesDoNotAffectTheChain() {
        val signed = stream().map { it.withSignature(byteArrayOf(9, 9)) }
        assertIs<ChainVerification.Valid>(chain.verifyChain(signed))
    }

    @Test
    fun parentSignaturesDoNotAffectTheChain() {
        val signed = stream().map { it.withParentSignature(byteArrayOf(7)).withSignature(byteArrayOf(9)) }
        assertIs<ChainVerification.Valid>(chain.verifyChain(signed))
        assertEquals(stream().map { it.envelope.hash }, signed.map { chain.hashOf(it) })
    }

    @Test
    fun aGapIsAMissingPrev() {
        val events = stream()
        val result = broken(events.filterIndexed { i, _ -> i != 2 })
        assertEquals(2, result.index)
        assertEquals(ChainBreak.MissingPrev(events[1].envelope.hash, events[2].envelope.hash), result.reason)
    }

    @Test
    fun aForgedFirstEventIsAMissingPrev() {
        val events = stream()
        assertEquals(0, broken(events.drop(1)).index)
    }

    @Test
    fun anEditedEventIsAHashMismatch() {
        val events = stream().toMutableList()
        val original = events[1] as AllowancePaid
        events[1] = original.copy(amount = Money(20_000))
        val result = broken(events)
        assertEquals(1, result.index)
        val reason = assertIs<ChainBreak.HashMismatch>(result.reason)
        assertEquals(original.envelope.hash, reason.stored)
    }

    @Test
    fun aReplayedEventIsADuplicate() {
        val events = stream()
        val result = broken(events + events[1])
        assertEquals(events.size, result.index)
        assertEquals(ChainBreak.DuplicateEventId(events[1].eventId), result.reason)
    }

    @Test
    fun aReorderBreaksTheLinkAtTheFirstMovedEvent() {
        val events = stream().toMutableList()
        events[2] = events[3].also { events[3] = events[2] }
        val result = broken(events)
        assertEquals(2, result.index)
        assertIs<ChainBreak.MissingPrev>(result.reason)
    }

    @Test
    fun aLamportGoingBackwardsIsNonMonotonic() {
        val events = stream().toMutableList()
        events[3] = events[3].mutateEnvelope { it.copy(lamport = 1) }
        val result = broken(events)
        assertEquals(3, result.index)
        assertEquals(ChainBreak.NonMonotonicLamport(previous = 3, actual = 1), result.reason)
    }

    @Test
    fun aRepeatedLamportIsNonMonotonic() {
        val events = stream().toMutableList()
        events[2] = events[2].mutateEnvelope { it.copy(lamport = events[1].lamport) }
        assertIs<ChainBreak.NonMonotonicLamport>(broken(events).reason)
    }

    @Test
    fun anotherAuthorsEventIsForeign() {
        val events = stream() + DeviceWriter(Fixtures.kidTablet).game(5)
        val result = broken(events)
        assertEquals(ChainBreak.ForeignAuthor(DeviceId("parent-phone"), DeviceId("kid-tablet")), result.reason)
    }

    @Test
    fun everyLamportMustBeAboveTheAnchors() {
        val events = stream()
        val tail = events.drop(2)
        val anchor = events[1]
        assertIs<ChainVerification.Valid>(chain.verifyChain(tail, anchor.envelope.hash, anchor.lamport))
        // A continuation whose first lamport does not exceed the anchor's is rejected even though it links.
        val result = broken(tail, anchor.envelope.hash, anchorLamport = tail.first().lamport)
        assertEquals(0, result.index)
        assertEquals(ChainBreak.NonMonotonicLamport(tail.first().lamport, tail.first().lamport), result.reason)
        assertIs<ChainBreak.NonMonotonicLamport>(broken(tail, anchor.envelope.hash, anchorLamport = 1_000).reason)
    }

    @Test
    fun anUnencodableEventIsReportedNotThrown() {
        val event = stream().first() as ChoreRewarded
        val bad = event.copy(choreId = "x\uD800")
        assertEquals(ChainBreak.Unencodable, broken(listOf(bad)).reason)
    }

    @Test
    fun aStoredDocumentIsHashedFromItsOwnFields() {
        for (event in CanonicalEncoderTest.allEventTypes()) {
            val stored = LedgerJson.json.parseToJsonElement(LedgerJson.encode(event)).jsonObject
            assertEquals(event.envelope.hash, chain.hashOf(stored, event.envelope.prevHash))
        }
    }

    @Test
    fun anySingleFieldMutationBreaksVerification() {
        val mutations: List<(Envelope) -> Envelope> = listOf(
            { it.copy(eventId = EventId("ffffffff-ffff-4fff-bfff-ffffffffffff")) },
            { it.copy(householdId = HouseholdId("house-2")) },
            { it.copy(kidId = KidId("kid-2")) },
            { it.copy(accountId = AccountId("main-2")) },
            { it.copy(authorUid = UserId("someone-else")) },
            { it.copy(authorRole = if (it.authorRole == Role.PARENT) Role.KID else Role.PARENT) },
            { it.copy(createdAt = it.createdAt + 1.seconds) },
            { it.copy(lamport = it.lamport + 1) },
            { it.copy(prevHash = "e".repeat(64)) },
            { it.copy(hash = "d".repeat(64)) },
            { it.copy(schemaVersion = it.schemaVersion + 1) },
        )
        val events = stream()
        for (index in events.indices) {
            for ((m, mutate) in mutations.withIndex()) {
                val tampered = events.toMutableList().apply { this[index] = this[index].mutateEnvelope(mutate) }
                assertIs<ChainVerification.Broken>(chain.verifyChain(tampered), "event $index mutation $m")
            }
            val payloadEdits = payloadMutations(events[index])
            for (edited in payloadEdits) {
                val tampered = events.toMutableList().apply { this[index] = edited }
                assertIs<ChainVerification.Broken>(chain.verifyChain(tampered), "event $index payload $edited")
            }
        }
    }

    @Test
    fun editingAGamePlayBreaksTheChain() {
        val kid = DeviceWriter(Fixtures.kidTablet)
        kid.game(10, score = 9)
        kid.game()
        val events = kid.written.toList()
        assertIs<ChainVerification.Valid>(chain.verifyChain(events))
        for (index in events.indices) {
            val edits = payloadMutations(events[index])
            assertEquals(4, edits.size)
            for (edited in edits) {
                val tampered = events.toMutableList().apply { this[index] = edited }
                assertIs<ChainVerification.Broken>(chain.verifyChain(tampered), "event $index payload $edited")
            }
        }
    }

    private fun payloadMutations(event: LedgerEvent): List<LedgerEvent> = when (event) {
        is ChoreRewarded -> listOf(
            event.copy(amount = event.amount + Money(1)),
            event.copy(choreId = "other"),
            event.copy(currency = Currency.Fiat("EUR")),
            event.copy(dueOn = event.dueOn.plusDays(1)),
        )

        is AllowancePaid -> listOf(
            event.copy(amount = Money(1)),
            event.copy(scheduleId = "x"),
            event.copy(periodKey = "2099-W01"),
        )

        is ManualAdjustment -> listOf(event.copy(amount = -event.amount), event.copy(reason = "edited"))

        is InterestAccrued -> listOf(
            event.copy(days = event.days + 1),
            event.copy(principal = Money(1)),
            event.copy(periodKey = "2099-01"),
        )

        is GamePlayed -> listOf(
            event.copy(gameId = "memory.sapling"),
            event.copy(score = event.score - 1),
            event.copy(amount = event.amount - Money(1)),
            event.copy(playedOn = event.playedOn.plusDays(-1)),
        )

        else -> emptyList()
    }
}
