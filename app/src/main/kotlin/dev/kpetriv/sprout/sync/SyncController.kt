package dev.kpetriv.sprout.sync

import dev.kpetriv.sprout.core.database.Session
import dev.kpetriv.sprout.core.database.SessionStore
import dev.kpetriv.sprout.core.database.entity.RegistrationStatus
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.model.UserId
import dev.kpetriv.sprout.core.sync.PushRejection
import dev.kpetriv.sprout.core.sync.SyncReport
import dev.kpetriv.sprout.core.time.Clock
import dev.kpetriv.sprout.di.ApplicationScope
import dev.kpetriv.sprout.work.ScheduledActionsPlanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Instant

/** Why this device has to wipe its household data (M3 §6). */
enum class RemovalReason {
    /** The household was deleted, or this member was removed from it (`SyncReport.householdGone`). */
    HOUSEHOLD_GONE,

    /** A parent revoked this device: its registry entry, mirrored into the session, is REVOKED. */
    DEVICE_REVOKED,
}

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

/** Whether the app is in the foreground (`ProcessLifecycleOwner`). */
interface ForegroundState {
    fun isForeground(): Boolean

    /** True while started, false while stopped; emits on every change (and the current state first). */
    fun changes(): Flow<Boolean>
}

/**
 * Runs sync for the signed-in session (M3 §4, §6). Started once by `AppInitializer`.
 *
 * While the session is a real household's (not the trial session) and this device is not revoked, the
 * [SyncRunner]'s foreground loop runs, fed by the [SyncTrigger] (outbox inserts; while the app is in the background
 * each request also enqueues an expedited [SyncWorker] run) and by the transport's live listeners; the periodic
 * worker is scheduled. A session change restarts it; a cleared session stops it.
 *
 * Every sync report (from the loop, a worker or [syncNow]) is processed here: refused outbox items are audited
 * ([RejectionAuditor], idempotent per `seq` and code), `householdGone` and a REVOKED session raise
 * [removal], and the first successful sync of a PARENT session plans allowance and interest up to today (planning
 * before the first pull could queue payments another parent device already made).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
@Suppress("LongParameterList") // One collaborator per concern; all small seams.
class SyncController @Inject constructor(
    @param:ApplicationScope private val scope: CoroutineScope,
    private val sessions: SessionStore,
    private val runner: SyncRunner,
    private val trigger: SyncTrigger,
    private val auditor: RejectionAuditor,
    private val planner: ScheduledActionsPlanner,
    private val scheduler: SyncScheduler,
    private val preferences: ShellPreferences,
    private val foreground: ForegroundState,
    private val clock: Clock,
) {
    private val started = AtomicBoolean(false)
    private val mutableStatus = MutableStateFlow(SyncStatus())
    private val mutableRemoval = MutableStateFlow<RemovalReason?>(null)

    /** The last report handled, so a report seen again after a session switch is not audited twice. */
    @Volatile
    private var lastHandled: SyncReport? = null

    val status: StateFlow<SyncStatus> = mutableStatus.asStateFlow()

    /** Set once this device must wipe; the shell then shows the blocking "removed" screen. */
    val removal: StateFlow<RemovalReason?> = mutableRemoval.asStateFlow()

    /** Starts following the session; later calls do nothing. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch { guarded { followSessions() } }
    }

    /**
     * The Firebase account is gone (deleted or disabled, `AuthState.UserGone`): positive evidence the household was
     * deleted or this kid device revoked, so the device wipes like on `householdGone`.
     */
    fun householdGone() {
        mutableRemoval.value = RemovalReason.HOUSEHOLD_GONE
    }

    /** Asks for a sync now, in the background (after an approval or a revocation, for example). */
    fun requestSync() {
        scope.launch { syncNow() }
    }

    /** One sync now; null when it could not run at all. Its report is processed like any other. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // A store failure is reported as "no report".
    suspend fun syncNow(): SyncReport? = try {
        runner.syncOnce()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Exception) {
        null
    }

    private suspend fun followSessions() {
        sessions.observe()
            .map { it?.let(SyncedSession::of) }
            .distinctUntilChanged()
            .collectLatest { session ->
                mutableStatus.value = SyncStatus()
                when {
                    session == null -> Unit
                    session.revoked -> mutableRemoval.value = RemovalReason.DEVICE_REVOKED
                    else -> runFor(session)
                }
            }
    }

    private suspend fun runFor(session: SyncedSession) {
        preferences.markLinked()
        scheduler.schedulePeriodic()
        coroutineScope {
            launch { guarded { handleReports(session) } }
            runner.run(this, requests())
        }
    }

    /**
     * Outbox inserts (each also enqueuing an expedited worker while in the background), plus one sync whenever the
     * app comes to the foreground (and at start): a registry change such as this device's revocation, or the
     * household's deletion, has no outbox insert to trigger on and may not reach a snapshot listener.
     */
    private fun requests(): Flow<Unit> = merge(
        trigger.requests().onEach { if (!foreground.isForeground()) scheduler.requestExpedited() },
        foreground.changes().filter { it }.map { },
    )

    private suspend fun handleReports(session: SyncedSession) {
        var planned = false
        runner.reports.filterNotNull().collect { report ->
            if (report === lastHandled) return@collect
            lastHandled = report
            handle(report)
            if (report.error == null && !planned && session.role == Role.PARENT) {
                planned = true
                planBestEffort()
            }
        }
    }

    private suspend fun handle(report: SyncReport) {
        // The audit is idempotent per (seq, code): a blocking event refused again on a later sync is not re-audited.
        if (report.rejections.isNotEmpty()) guarded { auditor.audit(report.rejections) }
        mutableStatus.update { current ->
            current.copy(
                lastSyncedAt = if (report.error == null) clock.now() else current.lastSyncedAt,
                lastFailed = report.error != null,
            )
        }
        if (report.householdGone) mutableRemoval.value = RemovalReason.HOUSEHOLD_GONE
    }

    private suspend fun planBestEffort() = guarded { planner.planUpToToday() }

    // Nothing here may crash the process or log: a failing store (or a database closed by a wipe) only ends the
    // current step. The exception is dropped on purpose; it may carry personal data.
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            // See above.
        }
    }

    /** The parts of a [Session] that decide whether and how sync runs; a PENDING → ACTIVE change keeps it running. */
    private data class SyncedSession(
        val uid: UserId,
        val householdId: HouseholdId,
        val role: Role,
        val deviceId: DeviceId,
        val revoked: Boolean,
    ) {
        companion object {
            fun of(session: Session): SyncedSession? = if (session.isLocalTrial) {
                null
            } else {
                SyncedSession(
                    session.uid,
                    session.householdId,
                    session.role,
                    session.deviceId,
                    revoked = session.deviceStatus == RegistrationStatus.REVOKED,
                )
            }
        }
    }
}
