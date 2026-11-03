package dev.kpetriv.sprout.sync

import dev.kpetriv.sprout.core.database.Session
import dev.kpetriv.sprout.core.database.SessionStore
import dev.kpetriv.sprout.core.database.entity.RegistrationStatus
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.model.UserId
import dev.kpetriv.sprout.core.sync.SyncReport
import dev.kpetriv.sprout.di.ApplicationScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** Whether the app is in the foreground (`ProcessLifecycleOwner`). */
interface ForegroundState {
    fun isForeground(): Boolean

    /** True while started, false while stopped; emits on every change (and the current state first). */
    fun changes(): Flow<Boolean>
}

/**
 * Runs sync for the signed-in session (M3 §4, §6). Started once by `AppInitializer`; stopped for good by
 * [RemovalHandler] before a wipe.
 *
 * While the session is a real household's (not the trial session) and this device is not revoked, the
 * [SyncRunner]'s foreground loop runs, fed by the [SyncTrigger] (outbox inserts and the head's next attempt time;
 * while the app is in the background each request also enqueues an expedited [SyncWorker] run), by the app coming to
 * the foreground and by the transport's live listeners; the periodic worker is scheduled. A session change restarts
 * it; a cleared session stops it; a REVOKED session is a removal. Anything that fails the loop (a throwing trigger, a
 * store error) restarts it after a delay that doubles up to [MAX_RESTART_DELAY_MS] (review M3).
 *
 * Reports are handled by [SyncReportProcessor], which the engine calls for every report; [status] is its line.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
@Suppress("LongParameterList") // One collaborator per concern; all small seams.
class SyncController @Inject constructor(
    @param:ApplicationScope private val scope: CoroutineScope,
    private val sessions: SessionStore,
    private val runner: SyncRunner,
    private val trigger: SyncTrigger,
    private val reports: SyncReportProcessor,
    private val scheduler: SyncScheduler,
    private val preferences: ShellPreferences,
    private val foreground: ForegroundState,
    private val removals: HouseholdRemovals,
) {
    private val started = AtomicBoolean(false)

    @Volatile
    private var stopped = false

    @Volatile
    private var job: Job? = null

    val status: StateFlow<SyncStatus> get() = reports.status

    /** Starts following the session; later calls do nothing. */
    fun start() {
        if (!started.compareAndSet(false, true) || stopped) return
        job = scope.launch { supervise() }
    }

    /** Stops for good (before a wipe closes the database). */
    fun stop() {
        stopped = true
        job?.cancel()
    }

    /** Asks for a sync now, in the background (after an approval or a revocation, for example). */
    fun requestSync() {
        scope.launch { syncNow() }
    }

    /** One sync now; null when it could not run at all. Its report is processed like any other. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // A store failure is reported as "no report".
    suspend fun syncNow(): SyncReport? = if (stopped) {
        null
    } else {
        try {
            runner.syncOnce()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            null
        }
    }

    /**
     * [followSessions] until it ends normally (the session stream completed), restarting it after a failure with a
     * doubling delay; a run that stayed up for [MAX_RESTART_DELAY_MS] resets the delay.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // Dropped unlogged: it may carry personal data.
    private suspend fun supervise() {
        var failures = 0
        while (!stopped) {
            try {
                coroutineScope {
                    val healthy = launch {
                        delay(MAX_RESTART_DELAY_MS)
                        failures = 0
                    }
                    followSessions()
                    healthy.cancel()
                }
                return
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                // Restarted below.
            }
            delay(restartDelayMs(failures++))
        }
    }

    private suspend fun followSessions() {
        sessions.observe()
            .map { it?.let(SyncedSession::of) }
            .distinctUntilChanged()
            .collectLatest { session ->
                reports.resetStatus()
                when {
                    session == null -> Unit
                    session.revoked -> removals.remove(RemovalReason.DEVICE_REVOKED)
                    else -> runFor(session)
                }
            }
    }

    private suspend fun runFor(session: SyncedSession) {
        preferences.markLinked()
        // Backfill for sessions saved before the local rows' account was recorded.
        if (preferences.linkedAccount() == null) {
            preferences.setLinkedAccount(ShellPreferences.digestOf(session.householdId, session.uid))
        }
        scheduler.schedulePeriodic()
        coroutineScope {
            runner.run(this, requests())
        }
    }

    /**
     * Outbox inserts and backoff expiries (each also enqueuing an expedited worker while in the background), plus one
     * sync whenever the app comes to the foreground (and at start): a registry change such as this device's
     * revocation, or the household's deletion, has no outbox insert to trigger on and may not reach a listener.
     */
    private fun requests(): Flow<Unit> = merge(
        trigger.requests().onEach { if (!foreground.isForeground()) scheduler.requestExpedited() },
        foreground.changes().filter { it }.map { },
    )

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

    companion object {
        const val BASE_RESTART_DELAY_MS: Long = 1_000
        const val MAX_RESTART_DELAY_MS: Long = 300_000

        /** 1 s, 2 s, 4 s, ... capped at 5 minutes. */
        fun restartDelayMs(failures: Int): Long =
            (BASE_RESTART_DELAY_MS shl failures.coerceIn(0, MAX_SHIFT)).coerceAtMost(MAX_RESTART_DELAY_MS)

        private const val MAX_SHIFT = 20
    }
}
