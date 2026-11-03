package dev.kpetriv.sprout.core.domainhousehold

import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.Hasher
import dev.kpetriv.sprout.core.domainledger.JvmSha256Hasher
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.model.ChoreId
import dev.kpetriv.sprout.core.model.ChoreSubmission
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.PhotoRef
import dev.kpetriv.sprout.core.model.SubmissionId
import dev.kpetriv.sprout.core.model.SubmissionStatus
import dev.kpetriv.sprout.core.model.UserId
import kotlin.time.Instant

/**
 * One `ChoreRewarded` ledger event to write for an approved submission: credit [amount] of [currency] to the kid's
 * account in that currency, for the occurrence ([choreId], [dueOn]). An intent only; the data layer builds and signs
 * the event.
 */
data class ChoreRewardIntent(
    val submissionId: SubmissionId,
    val choreId: ChoreId,
    val dueOn: CalendarDate,
    val kidId: KidId,
    val currency: Currency,
    val amount: Money,
)

/** The submission lifecycle: submit, approve, reject, and the reward an approval pays. */
object ChoreSubmissions {
    /**
     * The kid marks the occurrence of [chore] due on [dueOn] as done. The chore must be active
     * ([ChoreProposalRules.canSubmit]) and [dueOn] one of its due dates (any date for `Once(null)`). The submission is
     * keyed on [ChoreScheduler.occurrenceDate]: [dueOn] for a scheduled chore, the chore's fixed
     * [ChoreScheduler.anyTimeDueOn] for an any-time one, so an any-time chore has one submission and pays once.
     */
    fun submit(
        chore: Chore,
        dueOn: CalendarDate,
        submittedAt: Instant,
        note: String? = null,
        photoRef: PhotoRef? = null,
        hasher: Hasher = JvmSha256Hasher,
    ): ChoreSubmission {
        require(ChoreProposalRules.canSubmit(chore)) { "only an active chore can be submitted" }
        require(ChoreScheduler.isDueOn(chore, dueOn)) { "the chore is not due on that date" }
        val occurrence = ChoreScheduler.occurrenceDate(chore, dueOn)
        return ChoreSubmission(
            id = SubmissionIds.deterministic(chore.id, occurrence, hasher),
            choreId = chore.id,
            dueOn = occurrence,
            kidId = chore.kidId,
            submittedAt = submittedAt,
            note = note,
            photoRef = photoRef,
        )
    }

    /**
     * A parent approves [submission]. Approving an already approved submission returns it unchanged (idempotent);
     * a rejection can be overturned, matching the sync precedence.
     */
    fun approve(submission: ChoreSubmission, decidedAt: Instant, decidedBy: UserId?): ChoreSubmission =
        if (submission.status == SubmissionStatus.APPROVED) {
            submission
        } else {
            submission.copy(
                status = SubmissionStatus.APPROVED,
                decidedAt = decidedAt,
                decidedBy = decidedBy,
                rejectionReason = null,
            )
        }

    /** A parent rejects [submission] with an optional [reason]. An approved submission cannot be rejected. */
    fun reject(
        submission: ChoreSubmission,
        decidedAt: Instant,
        decidedBy: UserId?,
        reason: String? = null,
    ): ChoreSubmission {
        check(submission.status != SubmissionStatus.APPROVED) { "an approved submission cannot be rejected" }
        return submission.copy(
            status = SubmissionStatus.REJECTED,
            decidedAt = decidedAt,
            decidedBy = decidedBy,
            rejectionReason = reason,
        )
    }

    /**
     * The `ChoreRewarded` events an approved [submission] of [chore] pays: one per reward currency, money in the
     * kid's [fiat] MAIN currency and minutes in [Currency.Minutes].
     */
    fun rewardIntents(chore: Chore, submission: ChoreSubmission, fiat: Currency): List<ChoreRewardIntent> {
        require(submission.choreId == chore.id) { "the submission belongs to another chore" }
        require(submission.status == SubmissionStatus.APPROVED) { "only an approved submission is rewarded" }
        require(fiat is Currency.Fiat) { "money rewards are paid in a fiat currency" }
        fun intent(currency: Currency, amount: Money) =
            ChoreRewardIntent(submission.id, chore.id, submission.dueOn, submission.kidId, currency, amount)
        return listOfNotNull(
            chore.reward.money?.let { intent(fiat, it) },
            chore.reward.minutes?.let { intent(Currency.Minutes, it) },
        )
    }
}
