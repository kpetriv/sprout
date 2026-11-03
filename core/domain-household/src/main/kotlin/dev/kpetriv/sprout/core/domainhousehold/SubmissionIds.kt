package dev.kpetriv.sprout.core.domainhousehold

import dev.kpetriv.sprout.core.domainledger.Hasher
import dev.kpetriv.sprout.core.domainledger.JvmSha256Hasher
import dev.kpetriv.sprout.core.domainledger.toHex
import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.model.ChoreId
import dev.kpetriv.sprout.core.model.ChoreSubmission
import dev.kpetriv.sprout.core.model.SubmissionId

/**
 * Submission ids derived from the occurrence they belong to, so every device that submits (or approves) the same
 * chore occurrence produces the same id: re-submission and re-approval are idempotent, and the `ChoreRewarded`
 * events of an approval can be keyed on it.
 */
object SubmissionIds {
    /** Domain-separation prefix; bump the version only together with a data migration. */
    const val DOMAIN: String = "sprout/chore-submission/v1"

    /**
     * `hex(sha256(DOMAIN || 0x00 || choreId || 0x00 || dueOn))`: 64 lower-case hex characters. The separators
     * cannot occur in an id or a date, so distinct inputs never share an encoding.
     */
    fun deterministic(choreId: ChoreId, dueOn: CalendarDate, hasher: Hasher = JvmSha256Hasher): SubmissionId {
        val input = "$DOMAIN\u0000${choreId.value}\u0000$dueOn"
        return SubmissionId(hasher.sha256(input.encodeToByteArray()).toHex())
    }

    /** Whether [submission]'s id is the deterministic id of its occurrence. */
    fun isDeterministic(submission: ChoreSubmission, hasher: Hasher = JvmSha256Hasher): Boolean =
        submission.id == deterministic(submission.choreId, submission.dueOn, hasher)
}
