package dev.kpetriv.sprout.core.sync

import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.model.SubmissionStatus
import dev.kpetriv.sprout.core.sync.ConflictResolver.Decision
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class ConflictResolverTest {
    private val a = TestIdentity("device-a", "uid-a", Role.PARENT)
    private val b = TestIdentity("device-b", "uid-b", Role.PARENT)
    private val kid = TestIdentity("device-k", "uid-k", Role.KID, KID_1)

    private fun named(name: String) = buildJsonObject { put("name", name) }

    private fun resolve(local: DocumentEnvelope?, remote: DocumentEnvelope) =
        ConflictResolver.resolve(local?.let(StoredDocument::of), remote)

    @Test
    fun noLocalCopyApplies() {
        assertEquals(Decision.Apply, resolve(null, a.envelope(DocTypes.CHORE, "c", 1)))
        assertEquals(Decision.Apply, resolve(null, a.envelope(DocTypes.CHORE, "c", 1, deleted = true)))
    }

    @Test
    fun lastWriterWinsOnLamportThenDevice() {
        val local = a.envelope(DocTypes.CHORE, "c", 5, named("local"))
        assertEquals(Decision.Apply, resolve(local, b.envelope(DocTypes.CHORE, "c", 6, named("remote"))))
        assertEquals(Decision.Ignore, resolve(local, b.envelope(DocTypes.CHORE, "c", 4, named("remote"))))
        // Same lamport: the greater device id wins, from either side.
        assertEquals(Decision.Apply, resolve(local, b.envelope(DocTypes.CHORE, "c", 5, named("remote"))))
        assertEquals(Decision.Ignore, resolve(b.envelope(DocTypes.CHORE, "c", 5), local))
    }

    @Test
    fun reapplyingTheSameCopyIsIgnored() {
        val copy = a.envelope(DocTypes.CHORE, "c", 5)
        assertEquals(Decision.Ignore, resolve(copy, copy))
        val tombstone = a.envelope(DocTypes.CHORE, "c", 5, deleted = true)
        assertEquals(Decision.Ignore, resolve(tombstone, tombstone))
    }

    @Test
    fun tombstoneIsNeverResurrected() {
        val tombstone = a.envelope(DocTypes.CHORE, "c", 5, deleted = true)
        assertEquals(Decision.Ignore, resolve(tombstone, b.envelope(DocTypes.CHORE, "c", 6)))
        assertEquals(Decision.Ignore, resolve(tombstone, b.envelope(DocTypes.CHORE, "c", 1_000)))
        assertEquals(Decision.Ignore, resolve(tombstone, b.envelope(DocTypes.CHORE, "c", 4, deleted = true)))
        // Only a later tombstone replaces a tombstone.
        assertEquals(Decision.Apply, resolve(tombstone, b.envelope(DocTypes.CHORE, "c", 6, deleted = true)))
    }

    @Test
    fun tombstoneBeatsAnyLiveCopy() {
        val live = a.envelope(DocTypes.CHORE, "c", 9)
        assertEquals(Decision.Apply, resolve(live, b.envelope(DocTypes.CHORE, "c", 10, deleted = true)))
        // Even an older delete: otherwise the outcome would depend on arrival order.
        assertEquals(Decision.Apply, resolve(live, b.envelope(DocTypes.CHORE, "c", 3, deleted = true)))
    }

    @Test
    fun submissionStatusRankBeatsLamport() {
        val approvedPayload = submission(SubmissionStatus.APPROVED).toPayload()
        val approved = a.envelope(DocTypes.CHORE_SUBMISSION, "sub-1", 5, approvedPayload)
        val laterSubmitted = kid.envelope(DocTypes.CHORE_SUBMISSION, "sub-1", 9, submission(note = "again").toPayload())
        assertEquals(Decision.Ignore, resolve(approved, laterSubmitted))
        assertEquals(Decision.Apply, resolve(laterSubmitted, approved))
        val rejected = b.envelope(
            DocTypes.CHORE_SUBMISSION,
            "sub-1",
            12,
            submission(SubmissionStatus.REJECTED).toPayload(),
        )
        assertEquals(Decision.Ignore, resolve(approved, rejected))
        assertEquals(Decision.Apply, resolve(rejected, approved))
    }

    @Test
    fun equalSubmissionsFallBackToLastWriterWins() {
        val payload = submission().toPayload()
        val first = kid.envelope(DocTypes.CHORE_SUBMISSION, "sub-1", 3, payload)
        val second = a.envelope(DocTypes.CHORE_SUBMISSION, "sub-1", 4, payload)
        assertEquals(Decision.Apply, resolve(first, second))
        assertEquals(Decision.Ignore, resolve(second, first))
    }

    @Test
    fun sameStatusFallsBackToLastWriterWinsWhateverTheOtherFields() {
        // Two parents decided concurrently: the later write wins, not the earlier decision.
        val early = a.envelope(
            DocTypes.CHORE_SUBMISSION,
            "sub-1",
            7,
            submission(SubmissionStatus.APPROVED, decidedAtSeconds = 1, decidedBy = "p1").toPayload(),
        )
        val late = b.envelope(
            DocTypes.CHORE_SUBMISSION,
            "sub-1",
            8,
            submission(SubmissionStatus.APPROVED, decidedAtSeconds = 9, decidedBy = "p2").toPayload(),
        )
        assertEquals(Decision.Apply, resolve(early, late))
        assertEquals(Decision.Ignore, resolve(late, early))
        // Two kid edits: the later note wins.
        val note1 = kid.envelope(DocTypes.CHORE_SUBMISSION, "sub-1", 3, submission(note = "zzz").toPayload())
        val note2 = kid.envelope(DocTypes.CHORE_SUBMISSION, "sub-1", 4, submission(note = "aaa").toPayload())
        assertEquals(Decision.Apply, resolve(note1, note2))
        assertEquals(Decision.Ignore, resolve(note2, note1))
    }

    @Test
    fun submissionGuards() {
        val local = kid.envelope(DocTypes.CHORE_SUBMISSION, "sub-1", 3, submission().toPayload())
        val otherKid = a.envelope(
            DocTypes.CHORE_SUBMISSION,
            "sub-1",
            9,
            submission(SubmissionStatus.APPROVED, kidId = KID_2).toPayload(),
        )
        assertEquals(Decision.Ignore, resolve(local, otherKid), "identity fields never change")
        val garbage = a.envelope(DocTypes.CHORE_SUBMISSION, "sub-1", 9, named("not a submission"))
        assertEquals(Decision.Ignore, resolve(local, garbage))
        assertEquals(Decision.Apply, resolve(garbage, local))
    }

    /**
     * Property: folding any set of copies through the resolver, in any order, ends in the same state, and re-applying
     * any copy afterwards changes nothing (commutative, idempotent).
     */
    @Test
    fun convergesRegardlessOfArrivalOrder() {
        val random = Random(42)
        repeat(TRIALS) { trial ->
            val docType = if (trial % 2 == 0) DocTypes.CHORE_SUBMISSION else DocTypes.CHORE
            val copies = randomCopies(random, docType)
            val expected = fold(copies)
            repeat(ORDERS) {
                val state = fold(copies.shuffled(random))
                assertEquals(expected, state, "trial $trial")
                // The state is the maximum: no copy, including itself, replaces it.
                copies.forEach { copy -> assertEquals(Decision.Ignore, resolve(state, copy)) }
                // Idempotent: applying the whole sequence again changes nothing.
                assertEquals(state, fold(copies.shuffled(random), start = state))
            }
        }
    }

    private fun fold(copies: List<DocumentEnvelope>, start: DocumentEnvelope? = null): DocumentEnvelope? =
        copies.fold(start) { state, copy -> if (resolve(state, copy) == Decision.Apply) copy else state }

    private fun randomCopies(random: Random, docType: String): List<DocumentEnvelope> {
        val authors = listOf(a, b, kid)
        // Real writes never share (lamport, device): a device's clock hands out each value once.
        val keys = (1L..6L).flatMap { lamport -> authors.map { lamport to it } }
            .shuffled(random)
            .take(random.nextInt(2, 8))
        return keys.map { (lamport, author) ->
            val deleted = random.nextInt(6) == 0
            val payload: JsonObject = if (docType == DocTypes.CHORE_SUBMISSION) {
                val status = SubmissionStatus.entries[random.nextInt(3)]
                submission(
                    status = status,
                    note = listOf(null, "n1", "n2")[random.nextInt(3)],
                    decidedAtSeconds = random.nextLong(1, 4),
                    decidedBy = listOf("p1", "p2")[random.nextInt(2)],
                ).toPayload()
            } else {
                named("v${random.nextInt(3)}")
            }
            author.envelope(docType, "doc-1".takeIf { docType == DocTypes.CHORE } ?: "sub-1", lamport, payload, deleted)
        }
    }

    private companion object {
        const val TRIALS = 400
        const val ORDERS = 8
    }
}
