package dev.kpetriv.sprout.core.model

import kotlinx.serialization.Serializable
import kotlin.time.Instant

/** Review state of a [ChoreSubmission]. Sync precedence is [APPROVED] > [REJECTED] > [SUBMITTED]. */
@Serializable
enum class SubmissionStatus {
    /** Done by the kid; waiting for a parent. */
    SUBMITTED,

    /** A parent approved it; the chore's reward is credited. */
    APPROVED,

    /** A parent rejected it, optionally with a reason. */
    REJECTED,
}

/**
 * A kid marking one occurrence ([choreId], [dueOn]) of a chore as done, and the parent's decision on it. [id] is
 * derived from [choreId] and [dueOn], so the same occurrence always maps to the same submission.
 *
 * Invariants: a [SubmissionStatus.SUBMITTED] submission has no decision fields; a decided one has [decidedAt] not
 * before [submittedAt]; only a [SubmissionStatus.REJECTED] one may carry a [rejectionReason].
 *
 * @property note optional kid-written text, at most [MAX_NOTE_LENGTH] characters; redacted by [toString].
 * @property decidedBy the deciding parent's user id, when known.
 * @property rejectionReason optional parent-written text, at most [MAX_NOTE_LENGTH] characters; redacted by
 *   [toString].
 */
@Serializable
data class ChoreSubmission(
    val id: SubmissionId,
    val choreId: ChoreId,
    val dueOn: CalendarDate,
    val kidId: KidId,
    val submittedAt: Instant,
    val note: String? = null,
    val photoRef: PhotoRef? = null,
    val status: SubmissionStatus = SubmissionStatus.SUBMITTED,
    val decidedAt: Instant? = null,
    val decidedBy: UserId? = null,
    val rejectionReason: String? = null,
) {
    init {
        requireTextLength("note", note, MAX_NOTE_LENGTH)
        requireTextLength("rejectionReason", rejectionReason, MAX_NOTE_LENGTH)
        when (status) {
            SubmissionStatus.SUBMITTED -> require(decidedAt == null && decidedBy == null && rejectionReason == null) {
                "a submission awaiting review has no decision"
            }

            SubmissionStatus.APPROVED, SubmissionStatus.REJECTED -> {
                val decided = requireNotNull(decidedAt) { "a decided submission needs decidedAt" }
                require(decided >= submittedAt) { "decidedAt must not be before submittedAt" }
            }
        }
        require(rejectionReason == null || status == SubmissionStatus.REJECTED) {
            "only a rejected submission has a rejection reason"
        }
    }

    val occurrence: ChoreOccurrence get() = ChoreOccurrence(choreId, dueOn)

    override fun toString(): String =
        "ChoreSubmission(id=$id, choreId=$choreId, dueOn=$dueOn, kidId=$kidId, submittedAt=$submittedAt, " +
            "note=${redacted(note)}, photoRef=$photoRef, status=$status, decidedAt=$decidedAt, " +
            "decidedBy=$decidedBy, rejectionReason=${redacted(rejectionReason)})"
}
