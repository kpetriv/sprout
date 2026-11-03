package dev.kpetriv.sprout.startup

import android.database.SQLException
import dagger.Lazy
import dev.kpetriv.sprout.BuildConfig
import dev.kpetriv.sprout.core.database.HouseholdBootstrap
import dev.kpetriv.sprout.core.database.RoomLedgerRepository
import dev.kpetriv.sprout.core.database.Session
import dev.kpetriv.sprout.di.ApplicationScope
import dev.kpetriv.sprout.feature.chores.ChorePhotoCache
import dev.kpetriv.sprout.sync.SessionReconciler
import dev.kpetriv.sprout.sync.ShellPreferences
import dev.kpetriv.sprout.sync.SyncController
import dev.kpetriv.sprout.work.PlanScheduler
import dev.kpetriv.sprout.work.ScheduledActionsPlanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.security.GeneralSecurityException
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-time, process-start work, run off the main thread on the [ApplicationScope] (IO):
 * 0. clear the plaintext chore-photo capture cache ([ChorePhotoCache], ADR-0005), best effort: a capture a crash or a
 *    process death left behind never outlives the next start;
 * 1. initialise Firebase and install App Check (core/sync-firebase, M3 §5), best effort: the app works offline and
 *    sync retries later;
 * 2. restore the session and reconcile it with Firebase Auth ([SessionReconciler]): a session whose user signed out
 *    or whose claims name another household is cleared;
 * 3. debug builds only, and only on a device that has no session and never belonged to a real household: seed the
 *    demo trial household ([HouseholdBootstrap]; release builds never create demo kids);
 * 4. ledger maintenance: quarantine stored events whose hash or signature no longer verifies
 *    ([RoomLedgerRepository.quarantineUnverifiable]);
 * 5. for the trial session only, plan allowance and interest up to today ([ScheduledActionsPlanner]), best effort; a
 *    real household plans after its first successful pull instead ([SyncController]);
 * 6. schedule the daily planner ([PlanScheduler], kept if already scheduled);
 * 7. start sync for the session ([SyncController]).
 *
 * Dependencies are injected lazily so even building their graph (database, Keystore-backed signer) happens on the IO
 * thread, never in `Application.onCreate`. Failures other than cancellation are caught and published as
 * [StartupState.Failed] (never logged: nothing here may carry personal data); the app shell shows them with a
 * retry button that calls [retry].
 *
 * @param seedDemoHousehold the demo seeding, or null to skip it; set only when `BuildConfig.DEBUG`.
 * @param reconcileSession steps 2: returns the session to run with, or null when there is none.
 */
@Singleton
@Suppress("LongParameterList") // One step per parameter.
class AppInitializer internal constructor(
    private val scope: CoroutineScope,
    private val seedDemoHousehold: (suspend () -> Unit)?,
    private val quarantineLedger: suspend () -> Unit,
    private val planner: Lazy<ScheduledActionsPlanner>,
    private val scheduler: Lazy<PlanScheduler>,
    private val clearPhotoCache: () -> Unit = {},
    private val initialiseFirebase: () -> Unit = {},
    private val reconcileSession: suspend () -> Session? = { null },
    private val wasLinked: () -> Boolean = { false },
    private val startSync: () -> Unit = {},
) : StartupStatus {
    @Inject
    @Suppress("LongParameterList") // One lazily built dependency per startup step.
    constructor(
        @ApplicationScope scope: CoroutineScope,
        householdBootstrap: Lazy<HouseholdBootstrap>,
        ledger: Lazy<RoomLedgerRepository>,
        planner: Lazy<ScheduledActionsPlanner>,
        scheduler: Lazy<PlanScheduler>,
        photoCache: Lazy<ChorePhotoCache>,
        firebase: Lazy<FirebaseStartup>,
        reconciler: Lazy<SessionReconciler>,
        preferences: Lazy<ShellPreferences>,
        sync: Lazy<SyncController>,
    ) : this(
        scope = scope,
        seedDemoHousehold = demoSeeding(householdBootstrap, BuildConfig.DEBUG),
        quarantineLedger = { ledger.get().quarantineUnverifiable() },
        planner = planner,
        scheduler = scheduler,
        clearPhotoCache = { photoCache.get().clear() },
        initialiseFirebase = { firebase.get().initialise() },
        reconcileSession = { reconciler.get().reconcile() },
        wasLinked = { preferences.get().wasLinked() },
        startSync = { sync.get().start() },
    )

    private val started = AtomicBoolean(false)
    private val running = AtomicBoolean(false)
    private val mutableState = MutableStateFlow<StartupState>(StartupState.Running)

    override val state: StateFlow<StartupState> = mutableState.asStateFlow()

    /** Starts the work once per process; later calls do nothing. Returns immediately. */
    fun start() {
        if (started.compareAndSet(false, true)) launchWork()
    }

    override fun retry() {
        if (started.get() && mutableState.value is StartupState.Failed) launchWork()
    }

    private fun launchWork() {
        if (!running.compareAndSet(false, true)) return
        mutableState.value = StartupState.Running
        scope.launch {
            val outcome = try {
                runWork()
            } finally {
                running.set(false)
            }
            mutableState.value = outcome
        }
    }

    @Suppress("TooGenericExceptionCaught") // Any failure must reach the UI as a state instead of crashing the process.
    private suspend fun runWork(): StartupState = try {
        clearPhotoCacheBestEffort()
        initialiseFirebaseBestEffort()
        var session = reconcileSession()
        if (session == null && !wasLinked()) {
            seedDemoHousehold?.let { seed ->
                seed()
                session = reconcileSession()
            }
        }
        quarantineLedger()
        if (session?.isLocalTrial != false) planBestEffort()
        scheduler.get().scheduleDaily()
        startSync()
        StartupState.Ready
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        StartupState.Failed(failureKind(e))
    }

    /** Runs on the IO scope. A file system failure never fails startup; the next capture clears the cache too. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun clearPhotoCacheBestEffort() {
        try {
            clearPhotoCache()
        } catch (expected: Exception) {
            // Nothing to show: the capture flow clears the directory again before every capture.
        }
    }

    /** Firebase is only needed for sync, which retries later; a failure here never stops the app from opening. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun initialiseFirebaseBestEffort() {
        try {
            initialiseFirebase()
        } catch (expected: Exception) {
            // Sync stays unavailable until the next start; nothing to show or log.
        }
    }

    /** The first plan of the day. Never fails startup: the scheduled run retries, and nothing is logged. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private suspend fun planBestEffort() {
        try {
            planner.get().planUpToToday()
        } catch (e: CancellationException) {
            throw e
        } catch (expected: Exception) {
            // Left to PlanScheduledActionsWorker.
        }
    }

    private fun failureKind(error: Throwable): StartupFailure {
        val chain = generateSequence(error) { it.cause }.take(MAX_CAUSE_DEPTH).toList()
        return when {
            chain.any { it is GeneralSecurityException } -> StartupFailure.KEYS

            // SQLiteException (platform and SQLCipher) extends SQLException.
            chain.any { it is SQLException } -> StartupFailure.DATABASE

            else -> StartupFailure.UNKNOWN
        }
    }

    private companion object {
        const val MAX_CAUSE_DEPTH = 8

        /** The TEMPORARY demo seeding, debug builds only: release builds never create demo kids. */
        fun demoSeeding(bootstrap: Lazy<HouseholdBootstrap>, debug: Boolean): (suspend () -> Unit)? = if (debug) {
            { bootstrap.get().ensureLocalHousehold() }
        } else {
            null
        }
    }
}
