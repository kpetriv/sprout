package dev.kpetriv.sprout.core.domainhousehold

import dev.kpetriv.sprout.core.model.ChoreSchedule
import dev.kpetriv.sprout.core.model.ChoreSubmission
import dev.kpetriv.sprout.core.model.PhotoRef
import dev.kpetriv.sprout.core.model.SubmissionId
import dev.kpetriv.sprout.core.model.SubmissionStatus
import dev.kpetriv.sprout.core.model.UserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.minutes

class SubmissionStatusPrecedenceTest {
    private val chore = chore(ChoreSchedule.Daily.EVERY_DAY)
    private val submitted = ChoreSubmissions.submit(chore, date("2026-11-02"), CREATED_AT, note = "done")
    private val edited = submitted.copy(
        submittedAt = CREATED_AT + 5.minutes,
        note = "done, with photo",
        photoRef = PhotoRef("p.jpg", "b".repeat(64)),
    )
    private val approvedByA = ChoreSubmissions.approve(submitted, CREATED_AT + 10.minutes, UserId("parentA"))
    private val approvedByB = ChoreSubmissions.approve(submitted, CREATED_AT + 20.minutes, UserId("parentB"))
    private val approvedSameTime = ChoreSubmissions.approve(submitted, CREATED_AT + 10.minutes, UserId("parentC"))
    private val rejected = ChoreSubmissions.reject(submitted, CREATED_AT + 15.minutes, UserId("parentB"), "redo")
    private val rejectedEarly = ChoreSubmissions.reject(submitted, CREATED_AT + 1.minutes, UserId("parentA"))
    private val all = listOf(submitted, edited, approvedByA, approvedByB, approvedSameTime, rejected, rejectedEarly)

    @Test
    fun statusPrecedence() {
        val statuses = SubmissionStatus.entries
        for (a in statuses) {
            for (b in statuses) {
                val expected = maxOf(a, b, compareBy(SubmissionStatusPrecedence::rank))
                assertEquals(expected, SubmissionStatusPrecedence.merge(a, b))
            }
        }
        assertEquals(
            SubmissionStatus.APPROVED,
            SubmissionStatusPrecedence.merge(SubmissionStatus.REJECTED, SubmissionStatus.APPROVED),
        )
        assertEquals(
            SubmissionStatus.REJECTED,
            SubmissionStatusPrecedence.merge(SubmissionStatus.REJECTED, SubmissionStatus.SUBMITTED),
        )
    }

    @Test
    fun decisionsBeatStaleCopiesAndApprovalBeatsRejection() {
        assertEquals(approvedByA, SubmissionStatusPrecedence.merge(submitted, approvedByA))
        assertEquals(approvedByA, SubmissionStatusPrecedence.merge(approvedByA, submitted))
        assertEquals(rejected, SubmissionStatusPrecedence.merge(edited, rejected))
        assertEquals(approvedByB, SubmissionStatusPrecedence.merge(rejected, approvedByB))
        assertEquals(approvedByB, SubmissionStatusPrecedence.merge(approvedByB, rejected))
    }

    @Test
    fun tiesPickTheFirstDecisionOrTheLatestSubmission() {
        assertEquals(approvedByA, SubmissionStatusPrecedence.merge(approvedByB, approvedByA))
        assertEquals(rejectedEarly, SubmissionStatusPrecedence.merge(rejected, rejectedEarly))
        assertEquals(edited, SubmissionStatusPrecedence.merge(submitted, edited))
        assertEquals(edited, SubmissionStatusPrecedence.merge(edited, submitted))
    }

    @Test
    fun mergeIsCommutativeAssociativeAndIdempotent() {
        fun merge(a: ChoreSubmission, b: ChoreSubmission) = SubmissionStatusPrecedence.merge(a, b)
        for (a in all) {
            assertEquals(a, merge(a, a))
            for (b in all) {
                assertEquals(merge(a, b), merge(b, a), "$a vs $b")
                for (c in all) assertEquals(merge(merge(a, b), c), merge(a, merge(b, c)))
            }
        }
        // Any arrival order converges on the same copy.
        val winner = all.reduce(::merge)
        assertEquals(winner, all.reversed().reduce(::merge))
        assertEquals(winner, all.shuffled(kotlin.random.Random(7)).reduce(::merge))
    }

    @Test
    fun onlyCopiesOfOneSubmissionMerge() {
        assertFailsWith<IllegalArgumentException> {
            SubmissionStatusPrecedence.merge(submitted, submitted.copy(id = SubmissionId("other")))
        }
    }
}
