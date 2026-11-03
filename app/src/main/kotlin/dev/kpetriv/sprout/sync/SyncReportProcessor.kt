package dev.kpetriv.sprout.sync

import dev.kpetriv.sprout.core.database.SessionStore
import dev.kpetriv.sprout.core.database.entity.RegistrationStatus
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.sync.PushRejection
import dev.kpetriv.sprout.core.sync.SyncError
import dev.kpetriv.sprout.core.sync.SyncReport
import dev.kpetriv.sprout.core.sync.SyncReportHandler
import dev.kpetriv.sprout.core.time.Clock
import dev.kpetriv.sprout.work.ScheduledActionsPlanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Instant

/**
 * What the parent dashboard's sync line shows (the "sync blocked" state comes from `RoomOutboxSource.observeBlocked`).
 *
 * @property lastSyncedAt when the last sync finished without an error (this process); null before.
 * @property lastFailed the latest sync ended with an error (offline, signed out, server trouble).
 */
data class SyncStatus(val lastSyncedAt: Instant? = null, val lastFailed: Boolean = false)

/** Records `SYNC_REJECTED` audit rows for refused outbox items (`RoomOutboxSource.auditRejections`). */
fun interface RejectionAuditor {
    suspend fun audit(rejections: List<PushRejection>)
}

/**
 * Handles every [SyncReport] where it is produced: the engine passes each one here, in order, whoever ran the sync
 * (the foreground loop, a [SyncWorker], onboarding's poll), so no report is lost to conflation (review M2, D-M3-43):
 * - refused outbox items are audited ([RejectionAuditor], idempotent per `seq` and code);
 * - the status line is updated;
 * - `householdGone` is a removal ([HouseholdRemovals]), stored at once and wiped by [RemovalHandler] (also from a
 *   worker, with no UI);
 * - an unauthenticated sync asks the "Sign in again" gate to check the credentials ([CredentialCheck.verify]);
 * - the first successful sync of an ACTIVE PARENT session is recorded ([ShellPreferences.recordFirstPull]; the daily
 *   planner waits for it) and plans allowance and interest up to today once per process (planning before the first
 *   pull could queue payments another parent device already made).
 *
 * Runs under the engine's sync mutex: nothing here syncs. Failures are dropped unlogged.
 */
@Singleton
@Suppress("LongParameterList") // One collaborator per concern; all small seams.
class SyncReportProcessor @Inject constructor(
    private val auditor: RejectionAuditor,
    private val sessions: SessionStore,
    private val planner: ScheduledActionsPlanner,
    private val preferences: ShellPreferences,
    private val removals: HouseholdRemovals,
    private val credentials: CredentialCheck,
    private val clock: Clock,
) : SyncReportHandler {
    private val mutableStatus = MutableStateFlow(SyncStatus())
    private val planned: MutableSet<HouseholdId> = ConcurrentHashMap.newKeySet()

    val status: StateFlow<SyncStatus> = mutableStatus.asStateFlow()

    override suspend fun handle(report: SyncReport) {
        if (report.rejections.isNotEmpty()) guarded { auditor.audit(report.rejections) }
        mutableStatus.update { current ->
            current.copy(
                lastSyncedAt = if (report.error == null) clock.now() else current.lastSyncedAt,
                lastFailed = report.error != null,
            )
        }
        when {
            report.householdGone -> removals.remove(RemovalReason.HOUSEHOLD_GONE)
            report.error == SyncError.Unauthenticated -> credentials.verify()
            report.error == null -> guarded { afterSuccess() }
        }
    }

    /** A new session starts with an empty status line. */
    fun resetStatus() {
        mutableStatus.value = SyncStatus()
    }

    private suspend fun afterSuccess() {
        val session = sessions.get() ?: return
        if (session.isLocalTrial || session.role != Role.PARENT || session.deviceStatus != RegistrationStatus.ACTIVE) {
            return
        }
        val householdId = session.householdId
        if (!preferences.firstPullDone(householdId)) preferences.recordFirstPull(householdId)
        if (planned.add(householdId)) planner.planUpToToday()
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // A failing store only skips this step.
    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            // Dropped on purpose: it may carry personal data.
        }
    }
}
