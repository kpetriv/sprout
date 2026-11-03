package dev.kpetriv.sprout.core.database.usecase

import dev.kpetriv.sprout.core.database.AllowanceRepository
import dev.kpetriv.sprout.core.database.HouseholdPolicyRepository
import dev.kpetriv.sprout.core.database.KidRepository
import dev.kpetriv.sprout.core.database.PendingActionDescriber
import dev.kpetriv.sprout.core.database.PendingParentActionRepository
import dev.kpetriv.sprout.core.database.RoomLedgerRepository
import dev.kpetriv.sprout.core.database.entity.PendingActionStatus
import dev.kpetriv.sprout.core.domainhousehold.AllowancePlanner
import dev.kpetriv.sprout.core.domainhousehold.PendingParentAction
import dev.kpetriv.sprout.core.domainledger.AllowancePaid
import dev.kpetriv.sprout.core.domainledger.AppendResult
import dev.kpetriv.sprout.core.domainledger.Envelope
import dev.kpetriv.sprout.core.domainledger.EventId
import dev.kpetriv.sprout.core.domainledger.InterestAccrued
import dev.kpetriv.sprout.core.domainledger.LedgerEvent
import dev.kpetriv.sprout.core.domainledger.LedgerWriter
import dev.kpetriv.sprout.core.domainledger.ParentAuthorization
import dev.kpetriv.sprout.core.domainledger.ParentAuthorizationRequester
import dev.kpetriv.sprout.core.domainledger.PeriodClaim
import kotlinx.coroutines.sync.Mutex
import javax.inject.Inject
import javax.inject.Singleton

/**
 * An action that was not signed while draining. [permanent] when it is now FAILED (it breaks a ledger rule, or the
 * ledger refused it the third time in a row) and will not be tried again; otherwise it stays pending.
 */
data class FailedAction(val action: PendingParentAction, val result: AppendResult, val permanent: Boolean = false)

/** Outcome of [DrainPendingParentActionsUseCase]. */
sealed interface DrainResult {
    /**
     * The queue was drained (or draining stopped early, see [signatureRequired] and [keyInvalidated]).
     *
     * @property signed the events parent-signed and appended, in queue order.
     * @property alreadyPaid actions whose period was already in the ledger (marked done without signing).
     * @property failed actions not signed: invalid ones (FAILED before any prompt) and ledger refusals.
     * @property unsigned the events appended without a parent signature (no parent key registered, or D-M2-1), in
     *   queue order; the UI shows the unsigned warning.
     * @property cancelled actions withdrawn because their kid was archived or their schedule deactivated or changed.
     * @property replanned replacements queued (and drained in the same run) for allowance actions whose schedule
     *   changed: the same period with the schedule's current amount, currency and account.
     * @property signatureRequired true when draining stopped because this device cannot sign and the household policy
     *   requires parent signatures; that action and every later one stay pending.
     * @property keyInvalidated true when draining stopped because the parent key was invalidated by a biometric
     *   enrolment change; that action and every later one stay pending until the key is registered again.
     */
    data class Drained(
        val signed: List<EventId>,
        val alreadyPaid: Int,
        val failed: List<FailedAction>,
        val unsigned: List<EventId> = emptyList(),
        val cancelled: List<PendingParentAction> = emptyList(),
        val replanned: List<PendingParentAction> = emptyList(),
        val signatureRequired: Boolean = false,
        val keyInvalidated: Boolean = false,
    ) : DrainResult

    /** Another drain is running (R8); this call did nothing. */
    data object AlreadyRunning : DrainResult
}

/**
 * Signs the queued allowance and interest payments when the parent taps "Sign now" on the dashboard (ADR-0004
 * amendments, D-M2-6), one parent authorisation per event (D-M2-2), oldest first, and marks each done.
 *
 * For each pending action, in order:
 * 1. a period the ledger already holds is marked done without a prompt;
 * 2. (R7) an action whose kid is archived, or whose allowance schedule is gone or inactive, is cancelled; one whose
 *    schedule changed (amount, currency, account or cadence) is cancelled and re-planned from the current schedule
 *    ([AllowancePlanner.paymentFor]), and the replacement is drained instead;
 * 3. (R3) the event is validated before any prompt: an invalid one is FAILED with the reason and never prompted for;
 * 4. the parent is prompted (the provider gets the action, so the prompt can name it: [drainWith] uses the
 *    [PendingActionDescriber], R6) and the event appended. A ledger refusal counts towards the action's
 *    [dev.kpetriv.sprout.core.database.entity.PendingParentActionEntity.MAX_REFUSALS]; the third one in a row FAILs it.
 *
 * No parent key registered: events go in unsigned without prompting; a device without a strong biometric appends
 * unsigned when the household policy allows it and stops otherwise (D-M2-1); an invalidated parent key stops the drain
 * with [DrainResult.Drained.keyInvalidated] (see [ParentEventAppender]). Only one drain runs at a time (R8): the use
 * case is a singleton and a concurrent call returns [DrainResult.AlreadyRunning].
 */
@Singleton
@Suppress("LongParameterList") // The queue, the ledger, its writer, the policy, the schedules, kids and the describer.
class DrainPendingParentActionsUseCase @Inject constructor(
    private val pending: PendingParentActionRepository,
    private val ledger: RoomLedgerRepository,
    private val writer: LedgerWriter,
    private val policies: HouseholdPolicyRepository,
    private val allowances: AllowanceRepository,
    private val kids: KidRepository,
    private val describer: PendingActionDescriber,
) {
    private val running = Mutex()

    /**
     * Drains the queue. [authorizationProvider] is called once per event to sign, right before it is signed (one
     * strong-biometric prompt each), with the action it is for. If it throws (the parent cancelled), draining stops and
     * the exception propagates; actions done before stay done, the rest stay queued for the next "Sign now".
     */
    suspend operator fun invoke(
        authorizationProvider: suspend (PendingParentAction) -> ParentAuthorization,
    ): DrainResult {
        if (!running.tryLock()) return DrainResult.AlreadyRunning
        return try {
            drain(authorizationProvider)
        } finally {
            running.unlock()
        }
    }

    /** [invoke] prompting through [requester] with [describe]'s reason for each action. */
    suspend fun drainWith(requester: ParentAuthorizationRequester): DrainResult =
        invoke { action -> requester.request(describe(action)) }

    /** What the prompt for [action] says it signs ([PendingActionDescriber]). */
    suspend fun describe(action: PendingParentAction): String = describer.describe(action)

    @Suppress("ReturnCount") // The empty queue, the two early stops and the normal end.
    private suspend fun drain(provider: suspend (PendingParentAction) -> ParentAuthorization): DrainResult.Drained {
        val actions = pending.pending()
        val tally = Tally()
        if (actions.isEmpty()) return tally.result()
        val claims = ledger.projection().claimedPeriods
        val appender = ParentEventAppender(writer, policies)
        for (queued in actions) {
            when (drainQueued(queued, claims, appender, provider, tally)) {
                Stop.NO -> Unit

                // This device cannot sign and the household requires it: this action and the rest stay queued.
                Stop.SIGNATURE_REQUIRED -> return tally.result(signatureRequired = true)

                // The parent key must be registered again first: this action and the rest stay queued.
                Stop.KEY_INVALIDATED -> return tally.result(keyInvalidated = true)
            }
        }
        return tally.result()
    }

    /** Runs the lifecycle above for one queued action and records what happened in [tally]. */
    @Suppress("ReturnCount") // One return per step that ends the action's turn.
    private suspend fun drainQueued(
        queued: PendingParentAction,
        claims: Map<PeriodClaim, EventId>,
        appender: ParentEventAppender,
        provider: suspend (PendingParentAction) -> ParentAuthorization,
        tally: Tally,
    ): Stop {
        if (markDoneIfPaid(queued, claims, tally)) return Stop.NO
        val action = when (val refreshed = refresh(queued)) {
            Refresh.Unchanged -> queued

            is Refresh.Cancel -> {
                pending.cancel(queued.key, refreshed.reason)
                tally.cancelled += queued
                return Stop.NO
            }

            is Refresh.Replan -> {
                pending.cancel(queued.key, SCHEDULE_CHANGED)
                tally.cancelled += queued
                pending.enqueue(refreshed.action)
                tally.replanned += refreshed.action
                if (markDoneIfPaid(refreshed.action, claims, tally)) return Stop.NO
                refreshed.action
            }
        }
        when (val outcome = drainOne(action, appender, provider)) {
            is Outcome.Paid -> {
                pending.markDone(action.key, outcome.eventId)
                tally.paid(outcome)
            }

            is Outcome.Invalid -> {
                pending.markFailed(action.key, reasonOf(outcome.result))
                tally.failed += FailedAction(action, outcome.result, permanent = true)
            }

            is Outcome.Refused -> {
                val status = pending.recordRefusal(action.key, reasonOf(outcome.result))
                tally.failed += FailedAction(action, outcome.result, status == PendingActionStatus.FAILED)
            }

            Outcome.SignatureRequired -> return Stop.SIGNATURE_REQUIRED

            Outcome.KeyInvalidated -> return Stop.KEY_INVALIDATED
        }
        return Stop.NO
    }

    private suspend fun markDoneIfPaid(action: PendingParentAction, claims: Map<PeriodClaim, EventId>, tally: Tally) =
        claims[action.claim()]?.let { holder ->
            pending.markDone(action.key, holder)
            tally.alreadyPaid += 1
            true
        } ?: false

    /** Re-reads what [action] was planned from (R7). */
    @Suppress("ReturnCount") // One early return per reason to withdraw the action.
    private suspend fun refresh(action: PendingParentAction): Refresh {
        val kid = kids.getKid(action.kidId)
        if (kid == null || kid.archived) return Refresh.Cancel(KID_ARCHIVED)
        if (action !is PendingParentAction.PayAllowance) return Refresh.Unchanged
        val schedule = allowances.get(action.scheduleId)
        if (schedule == null || !schedule.active) return Refresh.Cancel(SCHEDULE_INACTIVE)
        val current = AllowancePlanner.paymentFor(schedule, action.dueOn)
        return if (current == action) Refresh.Unchanged else Refresh.Replan(current)
    }

    private suspend fun drainOne(
        action: PendingParentAction,
        appender: ParentEventAppender,
        provider: suspend (PendingParentAction) -> ParentAuthorization,
    ): Outcome {
        var eventId: EventId? = null
        val appended = appender.append(action.kidId, action.accountId, { provider(action) }) { envelope ->
            eventId = envelope.eventId
            eventFor(action, envelope)
        }
        return when (appended) {
            ParentAppend.SignatureRequired -> Outcome.SignatureRequired

            ParentAppend.KeyInvalidated -> Outcome.KeyInvalidated

            is ParentAppend.Invalid -> Outcome.Invalid(appended.asRejection)

            is ParentAppend.Appended -> {
                val result = appended.result
                val holder = result.duplicatePeriodHolder()
                when {
                    result == AppendResult.Accepted -> Outcome.Paid(checkNotNull(eventId), signed = appended.signed)
                    holder != null -> Outcome.Paid(holder, signed = null)
                    else -> Outcome.Refused(result)
                }
            }
        }
    }

    private fun eventFor(action: PendingParentAction, envelope: Envelope): LedgerEvent = when (action) {
        is PendingParentAction.PayAllowance -> AllowancePaid(
            envelope,
            action.currency,
            action.amount,
            action.scheduleId.value,
            action.periodKey,
        )

        is PendingParentAction.AccrueInterest -> InterestAccrued(
            envelope,
            action.currency,
            action.amount,
            principal = action.principal,
            annualRateBasisPoints = action.annualRateBasisPoints,
            days = action.days,
            periodKey = action.periodKey,
        )
    }

    /** Whether draining must stop after an action, and why. */
    private enum class Stop { NO, SIGNATURE_REQUIRED, KEY_INVALIDATED }

    private sealed interface Refresh {
        data object Unchanged : Refresh

        data class Cancel(val reason: String) : Refresh

        data class Replan(val action: PendingParentAction.PayAllowance) : Refresh
    }

    private sealed interface Outcome {
        /** In the ledger as [eventId]: appended now ([signed] true or false) or already there ([signed] null). */
        data class Paid(val eventId: EventId, val signed: Boolean?) : Outcome

        /** Refused by validation before any prompt. */
        data class Invalid(val result: AppendResult) : Outcome

        /** Refused by the ledger after signing. */
        data class Refused(val result: AppendResult) : Outcome

        data object SignatureRequired : Outcome

        data object KeyInvalidated : Outcome
    }

    private class Tally {
        val signed = mutableListOf<EventId>()
        val unsigned = mutableListOf<EventId>()
        val failed = mutableListOf<FailedAction>()
        val cancelled = mutableListOf<PendingParentAction>()
        val replanned = mutableListOf<PendingParentAction>()
        var alreadyPaid = 0

        fun paid(outcome: Outcome.Paid) {
            when (outcome.signed) {
                true -> signed += outcome.eventId
                false -> unsigned += outcome.eventId
                null -> alreadyPaid += 1
            }
        }

        fun result(signatureRequired: Boolean = false, keyInvalidated: Boolean = false) = DrainResult.Drained(
            signed = signed.toList(),
            alreadyPaid = alreadyPaid,
            failed = failed.toList(),
            unsigned = unsigned.toList(),
            cancelled = cancelled.toList(),
            replanned = replanned.toList(),
            signatureRequired = signatureRequired,
            keyInvalidated = keyInvalidated,
        )
    }

    companion object {
        /** Cancellation reason: the action's kid was archived (or is gone). */
        const val KID_ARCHIVED: String = "kid archived"

        /** Cancellation reason: the allowance schedule was deleted or deactivated. */
        const val SCHEDULE_INACTIVE: String = "schedule inactive"

        /** Cancellation reason: the allowance schedule changed; the period was re-planned from it. */
        const val SCHEDULE_CHANGED: String = "schedule changed"
    }
}

/** The ledger period [this] action pays for. */
internal fun PendingParentAction.claim(): PeriodClaim = when (this) {
    is PendingParentAction.PayAllowance -> PeriodClaim.Allowance(kidId, periodKey, currency)
    is PendingParentAction.AccrueInterest -> PeriodClaim.Interest(accountId, periodKey)
}

/** A short, data-free description of why [result] is not an acceptance: the reason and the violation names. */
internal fun reasonOf(result: AppendResult): String = when (result) {
    is AppendResult.Rejected -> if (result.violations.isEmpty()) {
        result.reason
    } else {
        result.reason + ": " + result.violations.joinToString(", ") { it::class.simpleName.orEmpty() }
    }

    is AppendResult.Conflict -> "conflicting duplicate"

    AppendResult.Duplicate -> "duplicate"

    AppendResult.Accepted -> "accepted"
}
