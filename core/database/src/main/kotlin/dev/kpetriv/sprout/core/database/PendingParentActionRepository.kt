package dev.kpetriv.sprout.core.database

import androidx.room.withTransaction
import dev.kpetriv.sprout.core.database.entity.AuditAction
import dev.kpetriv.sprout.core.database.entity.PendingActionStatus
import dev.kpetriv.sprout.core.database.entity.PendingParentActionEntity
import dev.kpetriv.sprout.core.domainhousehold.PendingParentAction
import dev.kpetriv.sprout.core.domainledger.EventId
import dev.kpetriv.sprout.core.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.put
import javax.inject.Inject

/**
 * A queued action that will not be signed ([PendingActionStatus.FAILED]), for the parent dashboard.
 *
 * @property reason the ledger's rejection reason and violation names (never personal data).
 * @property refusals how many drains in a row the ledger refused it.
 */
data class FailedParentAction(val action: PendingParentAction, val reason: String?, val refusals: Int)

/**
 * The queue of planned parent-only ledger events waiting for the parent's signature (ADR-0004, "signed at next
 * parent unlock"). Device-local; the events they become are what syncs.
 *
 * Each action is [PendingActionStatus.PENDING] until it is done, failed (invalid, or refused
 * [PendingParentActionEntity.MAX_REFUSALS] times in a row) or cancelled. Allowance periods the planner skipped (D-M2-4)
 * are recorded as [PendingActionStatus.SKIPPED] so the dashboard can list them.
 */
@Suppress("TooManyFunctions") // One function per queue transition and dashboard list.
interface PendingParentActionRepository {
    /**
     * Queues [action]. Idempotent by [PendingParentAction.key]: returns false if that key was ever queued, done,
     * failed or skipped. A key whose action was [cancel]led can be queued again (with the new content).
     */
    suspend fun enqueue(action: PendingParentAction): Boolean

    /** Actions waiting for a signature, oldest first. Failed, cancelled, skipped and done ones are excluded. */
    fun observePending(): Flow<List<PendingParentAction>>

    suspend fun pending(): List<PendingParentAction>

    /**
     * Marks the action [key] done by ledger event [eventId] (the event that carried it out, or the one that already
     * held its period). False if there is no such pending action.
     */
    suspend fun markDone(key: String, eventId: EventId): Boolean

    /**
     * Fails the pending action [key] for [reason] (it breaks a ledger rule, so prompting for it would be pointless).
     * False if there is no such pending action.
     */
    suspend fun markFailed(key: String, reason: String): Boolean

    /**
     * Records that the ledger refused the signed event of the pending action [key] for [reason]. The
     * [PendingParentActionEntity.MAX_REFUSALS]th refusal in a row fails it. Returns the action's status afterwards,
     * or null if there is no such pending action.
     */
    suspend fun recordRefusal(key: String, reason: String): PendingActionStatus?

    /**
     * Withdraws the pending action [key] (its schedule changed or was deactivated, or its kid archived); [reason] is
     * a short code for the audit row. It can be [enqueue]d again. False if there is no such pending action.
     */
    suspend fun cancel(key: String, reason: String? = null): Boolean

    /** Failed actions, oldest first, for the parent dashboard. */
    fun observeFailed(): Flow<List<FailedParentAction>>

    /**
     * Records [action] as skipped (an allowance period older than the catch-up window, D-M2-4). Idempotent by key;
     * false if the key is already stored.
     */
    suspend fun recordSkipped(action: PendingParentAction): Boolean

    /** Skipped allowance periods, oldest first, for the parent dashboard. */
    fun observeSkipped(): Flow<List<PendingParentAction>>

    /**
     * The parent dismissed the FAILED or SKIPPED action [key] from the dashboard: it is withdrawn (cancelled) and no
     * longer listed. Nothing is paid; the parent pays it by hand (a manual adjustment) if they still want to. False if
     * there is no such failed or skipped action.
     */
    suspend fun dismiss(key: String): Boolean
}

/**
 * [PendingParentActionRepository] on Room. Every change of status is audited (ids, amounts, keys and reason codes
 * only).
 */
@Suppress("TooManyFunctions") // Implements the queue's transitions one by one.
class RoomPendingParentActionRepository @Inject constructor(
    private val database: SproutDatabase,
    private val authors: SessionAuthorProvider,
    private val clock: Clock,
) : PendingParentActionRepository {
    private val actions = database.pendingParentActionDao()
    private val writes = LocalAudit(database, clock)

    override suspend fun enqueue(action: PendingParentAction): Boolean =
        insert(action, PendingActionStatus.PENDING, AuditAction.PARENT_ACTION_QUEUED)

    override fun observePending(): Flow<List<PendingParentAction>> =
        actions.observeWithStatus(PendingActionStatus.PENDING)
            .map { rows -> rows.map(PendingParentActionEntity::toDomain) }

    override suspend fun pending(): List<PendingParentAction> =
        actions.pending().map(PendingParentActionEntity::toDomain)

    override suspend fun markDone(key: String, eventId: EventId): Boolean {
        val author = authors.currentAuthor()
        return database.withTransaction {
            val done = actions.markDone(key, eventId, now()) > 0
            if (done) writes.auditOnly(author, AuditAction.PARENT_ACTION_DONE, key) { put("eventId", eventId.value) }
            done
        }
    }

    override suspend fun markFailed(key: String, reason: String): Boolean =
        close(key, PendingActionStatus.FAILED, reason, AuditAction.PARENT_ACTION_FAILED)

    override suspend fun recordRefusal(key: String, reason: String): PendingActionStatus? {
        val author = authors.currentAuthor()
        return database.withTransaction {
            if (actions.addRefusal(key, reason) == 0) return@withTransaction null
            val row = checkNotNull(actions.get(key))
            writes.auditOnly(author, AuditAction.PARENT_ACTION_REFUSED, key) {
                put("refusals", row.refusals)
                put("reason", reason)
            }
            if (row.refusals >= PendingParentActionEntity.MAX_REFUSALS) {
                actions.close(key, PendingActionStatus.FAILED, reason, now())
                writes.auditOnly(author, AuditAction.PARENT_ACTION_FAILED, key) { put("reason", reason) }
                PendingActionStatus.FAILED
            } else {
                PendingActionStatus.PENDING
            }
        }
    }

    override suspend fun cancel(key: String, reason: String?): Boolean =
        close(key, PendingActionStatus.CANCELLED, reason, AuditAction.PARENT_ACTION_CANCELLED)

    override fun observeFailed(): Flow<List<FailedParentAction>> =
        actions.observeWithStatus(PendingActionStatus.FAILED).map { rows ->
            rows.map { FailedParentAction(it.toDomain(), it.failureReason, it.refusals) }
        }

    override suspend fun recordSkipped(action: PendingParentAction): Boolean =
        insert(action, PendingActionStatus.SKIPPED, AuditAction.PARENT_ACTION_SKIPPED)

    override fun observeSkipped(): Flow<List<PendingParentAction>> =
        actions.observeWithStatus(PendingActionStatus.SKIPPED)
            .map { rows -> rows.map(PendingParentActionEntity::toDomain) }

    override suspend fun dismiss(key: String): Boolean {
        val author = authors.currentAuthor()
        return database.withTransaction {
            val dismissed = actions.dismiss(key, now()) > 0
            if (dismissed) {
                writes.auditOnly(author, AuditAction.PARENT_ACTION_CANCELLED, key) { put("reason", DISMISSED) }
            }
            dismissed
        }
    }

    private suspend fun insert(action: PendingParentAction, status: PendingActionStatus, audit: AuditAction): Boolean {
        val author = authors.currentAuthor()
        return database.withTransaction {
            if (status == PendingActionStatus.PENDING) actions.deleteCancelled(action.key)
            val row = PendingParentActionEntity.from(action, now(), status)
            val inserted = actions.insertIfAbsent(row) != NOT_INSERTED
            if (inserted) {
                writes.auditOnly(author, audit, action.key) {
                    put("kind", row.kind.name)
                    put("kidId", action.kidId.value)
                    put("accountId", action.accountId.value)
                    put("amountMinor", action.amount.minorUnits)
                    put("periodKey", action.periodKey)
                }
            }
            inserted
        }
    }

    private suspend fun close(key: String, status: PendingActionStatus, reason: String?, audit: AuditAction): Boolean {
        val author = authors.currentAuthor()
        return database.withTransaction {
            val closed = actions.close(key, status, reason, now()) > 0
            if (closed) writes.auditOnly(author, audit, key) { reason?.let { put("reason", it) } }
            closed
        }
    }

    private fun now(): Long = clock.now().toEpochMilliseconds()

    private companion object {
        /** Room's row id for an `INSERT OR IGNORE` that was ignored. */
        const val NOT_INSERTED = -1L

        /** Audit reason of [dismiss]. */
        const val DISMISSED = "dismissed by parent"
    }
}
