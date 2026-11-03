package dev.kpetriv.sprout.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.kpetriv.sprout.core.domainhousehold.PendingParentAction
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.EventId
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.model.AccountId
import dev.kpetriv.sprout.core.model.KidId

/** What a [PendingParentActionEntity] becomes once signed. */
enum class PendingActionKind {
    /** An `AllowancePaid`. */
    ALLOWANCE,

    /** An `InterestAccrued`. */
    INTEREST,
}

/** Where a [PendingParentActionEntity] stands. Stored by name. */
enum class PendingActionStatus {
    /** Waiting for the parent's signature; drained when the parent taps "Sign now" on the dashboard. */
    PENDING,

    /** Carried out by [PendingParentActionEntity.eventId] (or that event already held its period). */
    DONE,

    /**
     * Will not be signed: it fails [dev.kpetriv.sprout.core.domainledger.LedgerRules] (checked before prompting), or
     * the ledger refused it [PendingParentActionEntity.MAX_REFUSALS] drains in a row. Shown on the parent dashboard
     * with [PendingParentActionEntity.failureReason].
     */
    FAILED,

    /** Withdrawn: its schedule was deactivated or changed, or its kid archived, before it was signed. */
    CANCELLED,

    /**
     * Never queued: an allowance period older than the catch-up window (D-M2-4), recorded so the parent dashboard can
     * list what was skipped.
     */
    SKIPPED,
}

/**
 * A planned parent-only ledger event waiting for the parent's signature (ADR-0004 amendments: signed from "Sign now").
 * Device-local, never synced: the event it becomes is what syncs.
 *
 * @property key the action's idempotency key ([PendingParentAction.key]); unique, so planning twice queues once.
 * @property actionJson the whole [PendingParentAction] in its kotlinx-serialization JSON form; the source of truth.
 * @property doneAtEpochMs when the row left [PendingActionStatus.PENDING] (done, failed, cancelled; creation time for a
 *   skipped period); null while pending.
 * @property eventId the ledger event that carried the action out (or that already held its period), once done.
 * @property status see [PendingActionStatus].
 * @property refusals how many drains in a row the ledger refused the signed event; [MAX_REFUSALS] fails the action.
 * @property failureReason why the action failed or was cancelled, or the last refusal: rejection reasons and
 *   violation names only, never personal data.
 */
@Entity(
    tableName = "pending_parent_actions",
    indices = [Index(value = ["key"], unique = true), Index(value = ["status"])],
)
data class PendingParentActionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val key: String,
    val kind: PendingActionKind,
    val kidId: KidId,
    val accountId: AccountId,
    val amount: Money,
    val currency: Currency,
    val periodKey: String,
    val actionJson: String,
    val createdAtEpochMs: Long,
    val doneAtEpochMs: Long? = null,
    val eventId: EventId? = null,
    val status: PendingActionStatus = PendingActionStatus.PENDING,
    val refusals: Int = 0,
    val failureReason: String? = null,
) {
    fun toDomain(): PendingParentAction =
        DocumentJson.json.decodeFromString(PendingParentAction.serializer(), actionJson)

    companion object {
        /** Consecutive ledger refusals after which an action is [PendingActionStatus.FAILED]. */
        const val MAX_REFUSALS: Int = 3

        fun from(
            action: PendingParentAction,
            createdAtEpochMs: Long,
            status: PendingActionStatus = PendingActionStatus.PENDING,
        ) = PendingParentActionEntity(
            key = action.key,
            kind = when (action) {
                is PendingParentAction.PayAllowance -> PendingActionKind.ALLOWANCE
                is PendingParentAction.AccrueInterest -> PendingActionKind.INTEREST
            },
            kidId = action.kidId,
            accountId = action.accountId,
            amount = action.amount,
            currency = action.currency,
            periodKey = action.periodKey,
            actionJson = DocumentJson.json.encodeToString(PendingParentAction.serializer(), action),
            createdAtEpochMs = createdAtEpochMs,
            doneAtEpochMs = createdAtEpochMs.takeIf { status != PendingActionStatus.PENDING },
            status = status,
        )
    }
}
