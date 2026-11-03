package dev.kpetriv.sprout.core.domainhousehold

import dev.kpetriv.sprout.core.model.ChoreSubmission
import dev.kpetriv.sprout.core.model.SubmissionStatus

/**
 * Conflict resolution for a submission edited on two devices: [SubmissionStatus.APPROVED] >
 * [SubmissionStatus.REJECTED] > [SubmissionStatus.SUBMITTED]. A decision is never undone by a stale copy, and an
 * approval wins over a concurrent rejection (the reward is paid exactly once because the submission id, and so the
 * reward events, are deterministic).
 *
 * [merge] is commutative, associative and idempotent, so replicas converge whatever order copies arrive in.
 */
object SubmissionStatusPrecedence {
    /** Higher wins. */
    fun rank(status: SubmissionStatus): Int = when (status) {
        SubmissionStatus.SUBMITTED -> 0
        SubmissionStatus.REJECTED -> 1
        SubmissionStatus.APPROVED -> 2
    }

    /** The status that wins between [local] and [remote]. */
    fun merge(local: SubmissionStatus, remote: SubmissionStatus): SubmissionStatus =
        if (rank(remote) > rank(local)) remote else local

    /**
     * The copy of one submission that wins. The higher status wins; between two decided copies with the same status
     * the earlier decision wins (first parent to act); between two undecided copies the later submission wins (the
     * kid's latest note or photo). Remaining ties are broken on the other fields so the result never depends on
     * argument order.
     */
    fun merge(local: ChoreSubmission, remote: ChoreSubmission): ChoreSubmission {
        require(local.id == remote.id) { "only copies of the same submission can be merged" }
        return if (order.compare(remote, local) > 0) remote else local
    }

    /** Total order on copies of one submission; the greatest copy wins. */
    private val order: Comparator<ChoreSubmission> = compareBy<ChoreSubmission> { rank(it.status) }
        // Earlier decision wins, so compare descending; undecided copies have no decidedAt.
        .thenByDescending { it.decidedAt }
        .thenBy { it.submittedAt }
        .thenBy { it.decidedBy?.value }
        .thenBy { it.rejectionReason }
        .thenBy { it.note }
        .thenBy { it.photoRef?.sha256 }
        .thenBy { it.photoRef?.fileName }
        .thenBy { it.kidId.value }
        .thenBy { it.choreId.value }
        .thenBy { it.dueOn }
}
