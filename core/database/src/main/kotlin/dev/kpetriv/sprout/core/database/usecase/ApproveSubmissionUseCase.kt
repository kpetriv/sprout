package dev.kpetriv.sprout.core.database.usecase

import dev.kpetriv.sprout.core.database.AccountRepository
import dev.kpetriv.sprout.core.database.ChoreRepository
import dev.kpetriv.sprout.core.database.HouseholdPolicyRepository
import dev.kpetriv.sprout.core.database.RoomLedgerRepository
import dev.kpetriv.sprout.core.database.SubmissionDecision
import dev.kpetriv.sprout.core.domainhousehold.ChoreRewardIntent
import dev.kpetriv.sprout.core.domainhousehold.ChoreSubmissions
import dev.kpetriv.sprout.core.domainledger.AppendResult
import dev.kpetriv.sprout.core.domainledger.ChoreRewarded
import dev.kpetriv.sprout.core.domainledger.EventId
import dev.kpetriv.sprout.core.domainledger.LedgerWriter
import dev.kpetriv.sprout.core.domainledger.ParentAuthorization
import dev.kpetriv.sprout.core.domainledger.PeriodClaim
import dev.kpetriv.sprout.core.domainledger.Violation
import dev.kpetriv.sprout.core.model.AccountId
import dev.kpetriv.sprout.core.model.ChoreSubmission
import dev.kpetriv.sprout.core.model.SubmissionId
import dev.kpetriv.sprout.core.time.Clock
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * One reward event of an approval. [alreadyPaid] when [eventId] was already in the ledger and nothing was signed.
 * [unsigned] when it was paid now without a parent signature (D-M2-1: no strong biometric, signatures off).
 */
data class RewardOutcome(
    val intent: ChoreRewardIntent,
    val eventId: EventId,
    val alreadyPaid: Boolean,
    val unsigned: Boolean = false,
)

/** Outcome of [ApproveSubmissionUseCase]. */
sealed interface ApprovalResult {
    /** Every reward is in the ledger and the submission is approved. */
    data class Approved(val submission: ChoreSubmission, val rewards: List<RewardOutcome>) : ApprovalResult {
        /** True when a reward was paid now without a parent signature (D-M2-1); the UI shows the warning. */
        val unsigned: Boolean get() = rewards.any { it.unsigned }
    }

    /** No such submission, or its chore is gone. */
    data object NotFound : ApprovalResult

    /**
     * The kid has no MAIN account for a reward currency (or no fiat MAIN account at all). Checked before anything is
     * signed, so nothing was paid.
     */
    data object NoAccount : ApprovalResult

    /**
     * Why an approval stopped at the reward for [intent]. Returned as is when **no** reward of the submission is in
     * the ledger, and as [Partial.stoppedBy] when some are. The submission stays undecided either way, so approving
     * again pays only what is missing.
     */
    sealed interface Stopped : ApprovalResult {
        val intent: ChoreRewardIntent
    }

    /**
     * The ledger refused the reward for [intent] ([result]): before any prompt when the event breaks a ledger rule
     * ([ParentEventAppender.INVALID_BEFORE_PROMPT]), or after signing.
     */
    data class RewardRefused(override val intent: ChoreRewardIntent, val result: AppendResult) : Stopped

    /** This device has no usable strong biometric but the household policy requires parent signatures. */
    data class SignatureRequired(override val intent: ChoreRewardIntent) : Stopped

    /**
     * The parent key was invalidated by a biometric enrolment change; nothing more was signed. The key must be reset
     * and registered again (settings) before the approval can be finished.
     */
    data class KeyInvalidated(override val intent: ChoreRewardIntent) : Stopped

    /**
     * Some rewards of the submission are in the ledger ([paid], now or before) and the others ([unpaid]) are not,
     * because of [stoppedBy]. The submission stays undecided; the UI offers "finish approval", which approves again and
     * pays only [unpaid]. Rejecting it is refused while anything is paid (`ChoreRepository.decide`).
     */
    data class Partial(val paid: List<RewardOutcome>, val unpaid: List<ChoreRewardIntent>, val stoppedBy: Stopped) :
        ApprovalResult
}

/**
 * A parent approves a chore submission (M2 approvals inbox): pays one parent-signed `ChoreRewarded` per reward
 * currency ([ChoreSubmissions.rewardIntents]) and then records the approval.
 *
 * Idempotent through the ledger's period claim (kid, chore, due date, currency): a reward already in the ledger is
 * reported as [RewardOutcome.alreadyPaid] without asking for an authorisation, so approving twice (or on two devices)
 * pays each currency exactly once. Rewards are paid before the decision is written, so a refused or cancelled
 * signature leaves the submission in the inbox to retry; when that happens after some rewards were paid the result is
 * [ApprovalResult.Partial]. Each reward is validated before its prompt, so the parent is never asked to sign one the
 * ledger would refuse. A device without a strong biometric pays unsigned when the household policy allows it and
 * refuses otherwise (D-M2-1), and with no parent key registered rewards go in unsigned without a prompt (see
 * [ParentEventAppender]).
 */
@Suppress("LongParameterList") // Repositories, the ledger, its writer, the policy and the clock.
class ApproveSubmissionUseCase @Inject constructor(
    private val chores: ChoreRepository,
    private val accounts: AccountRepository,
    private val ledger: RoomLedgerRepository,
    private val writer: LedgerWriter,
    private val policies: HouseholdPolicyRepository,
    private val clock: Clock,
) {
    /**
     * Approves [submissionId]. [authorize] is called once for each reward still to pay, right before its event is
     * signed, and must return a fresh parent authorisation (one strong-biometric prompt per event, D-M2-2); it gets
     * the [ChoreRewardIntent] so the prompt can say what it signs. If it throws (the parent cancelled), nothing more
     * is signed and the exception propagates; rewards signed before stay paid. `ParentAuthUnavailableException` (from
     * it or from the authorisation) is the D-M2-1 case instead: no further prompts, unsigned or refused per policy;
     * `ParentKeyInvalidatedException` stops with [ApprovalResult.KeyInvalidated] (or [ApprovalResult.Partial]).
     */
    @Suppress("ReturnCount") // One early return per ApprovalResult outcome.
    suspend operator fun invoke(
        submissionId: SubmissionId,
        authorize: suspend (ChoreRewardIntent) -> ParentAuthorization,
    ): ApprovalResult {
        val submission = chores.getSubmission(submissionId) ?: return ApprovalResult.NotFound
        val chore = chores.get(submission.choreId) ?: return ApprovalResult.NotFound
        val kidAccounts = accounts.accountsFor(submission.kidId).first()
        val fiat = kidAccounts.fiatMain() ?: return ApprovalResult.NoAccount
        // The intents of the approval about to be recorded; the stored decision is written after payment.
        val approving = ChoreSubmissions.approve(submission, clock.now(), decidedBy = null)
        val intents = ChoreSubmissions.rewardIntents(chore, approving, fiat.currency)
        val targets = intents.map { kidAccounts.main(it.currency) ?: return ApprovalResult.NoAccount }
        val claims = ledger.projection().claimedPeriods
        val paid = mutableListOf<RewardOutcome>()
        val appender = ParentEventAppender(writer, policies)
        for ((index, intent) in intents.withIndex()) {
            val claimedBy = claims[PeriodClaim.Chore(intent.kidId, intent.choreId.value, intent.dueOn, intent.currency)]
            val step = if (claimedBy != null) {
                Step.Paid(RewardOutcome(intent, claimedBy, alreadyPaid = true))
            } else {
                pay(intent, targets[index].id, appender, authorize)
            }
            when (step) {
                is Step.Paid -> paid += step.outcome
                is Step.Stop -> return stop(step.stoppedBy, paid, intents.drop(index))
            }
        }
        return ApprovalResult.Approved(chores.decide(submissionId, SubmissionDecision.APPROVE).submission, paid)
    }

    /** Pays [intent] into [accountId]: a [RewardOutcome], or why it was not paid. */
    private suspend fun pay(
        intent: ChoreRewardIntent,
        accountId: AccountId,
        appender: ParentEventAppender,
        authorize: suspend (ChoreRewardIntent) -> ParentAuthorization,
    ): Step {
        var eventId: EventId? = null
        val appended = appender.append(intent.kidId, accountId, { authorize(intent) }) { envelope ->
            eventId = envelope.eventId
            ChoreRewarded(envelope, intent.currency, intent.amount, intent.choreId.value, intent.dueOn)
        }
        return when (appended) {
            ParentAppend.SignatureRequired -> Step.Stop(ApprovalResult.SignatureRequired(intent))

            ParentAppend.KeyInvalidated -> Step.Stop(ApprovalResult.KeyInvalidated(intent))

            is ParentAppend.Invalid -> appended.violations.duplicateHolder()
                ?.let { Step.Paid(RewardOutcome(intent, it, alreadyPaid = true)) }
                ?: Step.Stop(ApprovalResult.RewardRefused(intent, appended.asRejection))

            is ParentAppend.Appended -> {
                val result = appended.result
                val holder = result.duplicatePeriodHolder()
                when {
                    result == AppendResult.Accepted -> Step.Paid(
                        RewardOutcome(intent, checkNotNull(eventId), alreadyPaid = false, unsigned = !appended.signed),
                    )

                    holder != null -> Step.Paid(RewardOutcome(intent, holder, alreadyPaid = true))

                    else -> Step.Stop(ApprovalResult.RewardRefused(intent, result))
                }
            }
        }
    }

    private sealed interface Step {
        data class Paid(val outcome: RewardOutcome) : Step

        data class Stop(val stoppedBy: ApprovalResult.Stopped) : Step
    }

    private fun stop(
        stoppedBy: ApprovalResult.Stopped,
        paid: List<RewardOutcome>,
        unpaid: List<ChoreRewardIntent>,
    ): ApprovalResult = if (paid.isEmpty()) stoppedBy else ApprovalResult.Partial(paid.toList(), unpaid, stoppedBy)
}

/** The event already holding the period, when the only violation is a duplicate period. */
private fun List<Violation>.duplicateHolder(): EventId? = (singleOrNull() as? Violation.DuplicatePeriod)?.claimedBy
