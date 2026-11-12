package dev.kpetriv.sprout.startup

import android.database.SQLException
import dagger.Lazy
import dev.kpetriv.sprout.BuildConfig
import dev.kpetriv.sprout.chat.ChatMaintenance
import dev.kpetriv.sprout.chat.KeptChatKeyCheck
import dev.kpetriv.sprout.chat.VoiceTempFiles
import dev.kpetriv.sprout.core.ai.AiGateway
import dev.kpetriv.sprout.core.database.ChatIdentityKeeper
import dev.kpetriv.sprout.core.database.ChatPruneScheduler
import dev.kpetriv.sprout.core.database.HouseholdBootstrap
import dev.kpetriv.sprout.core.database.RoomLedgerRepository
import dev.kpetriv.sprout.core.database.Session
import dev.kpetriv.sprout.core.syncfirebase.AppForeground
import dev.kpetriv.sprout.di.ApplicationScope
import dev.kpetriv.sprout.feature.chores.ChorePhotoCache
import dev.kpetriv.sprout.sync.RemovalHandler
import dev.kpetriv.sprout.sync.SessionReconciler
import dev.kpetriv.sprout.sync.ShellPreferences
import dev.kpetriv.sprout.sync.SyncController
import dev.kpetriv.sprout.widget.WidgetBlanker
import dev.kpetriv.sprout.widget.WidgetDataObserver
import dev.kpetriv.sprout.work.PlanScheduler
import dev.kpetriv.sprout.work.ScheduledActionsPlanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import java.security.GeneralSecurityException
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-time, process-start work, run off the main thread on the [ApplicationScope] (IO):
 * 0. clear the plaintext chore-photo capture cache ([ChorePhotoCache], ADR-0005) and sweep leftover voice note temp
 *    files from `cacheDir` ([VoiceTempFiles], audit A2-M2), best effort: a capture or a recording a crash or a
 *    process death left behind never outlives the next start;
 * 1. initialise Firebase and install App Check (core/sync-firebase, M3 §5), best effort: the app works offline and
 *    sync retries later;
 * 2. restore the session and reconcile it with Firebase Auth ([SessionReconciler]): another account or household
 *    wipes, an account that is gone wipes, lost credentials raise the "Sign in again" gate; with no session, a chat key
 *    a sign-out kept is checked with the token-less `deviceStatus` in the background ([KeptChatKeyCheck], D-REL-28);
 *    with a real session, a sign-out's record of its own device that a process death left behind seeds the chat
 *    counters again and is deleted ([ChatIdentityKeeper.resumeSeed], best effort, D-REL-25, D-REL-33);
 * 3. debug builds only, and only on a device that has no session and never belonged to a real household: seed the
 *    demo trial household ([HouseholdBootstrap]; release builds never create demo kids);
 * 4. ledger maintenance: quarantine stored events whose hash or signature no longer verifies
 *    ([RoomLedgerRepository.quarantineUnverifiable]);
 * 4a. the M4 upkeep ([ChatMaintenance.atStartup], best effort): the orphan photo sweep, the time bank reconcile, a
 *    parent's thread key upkeep, the chore photo upload and (in the background) the encryption key registration;
 * 5. for the trial session only, plan allowance and interest up to today ([ScheduledActionsPlanner]), best effort; a
 *    real household plans after its first successful pull instead (`SyncReportProcessor`); no session never plans;
 * 6. schedule the daily planner ([PlanScheduler], kept if already scheduled) and the daily chat prune
 *    ([ChatPruneScheduler], M7 §2), and start the home screen widget's refresh on data changes ([WidgetDataObserver],
 *    M7 §4);
 * 7. start sync for the session ([SyncController]).
 *
 * Alongside the steps, once per process (not on [retry]): once the app is first in the foreground, read the on-device
 * model's status ([AiGateway.status], M6 §3.6), best effort, so AICore fetches its configuration before the first AI
 * call. It never asks for a download (that needs the parent's consent, D-M6-6) and logs only the availability and
 * reason code, at DEBUG.
 *
 * A removal ([RemovalHandler]) stops the work: one stored by an earlier process first draws the home screen widget
 * blank ([WidgetBlanker], before step 0, audit 2 N1: a process that died during the wipe, or exited before the widget
 * redrew, left the kid's last drawing there) and then resumes its wipe before step 2; one reported during startup
 * (step 2, or sync elsewhere) skips every remaining step, checked between steps. Startup
 * then ends [StartupState.Ready], never [StartupState.Failed] (a step that fails because the wipe closed the
 * database counts as removed), and the shell shows the removed screen.
 *
 * Dependencies are injected lazily so even building their graph (database, Keystore-backed signer) happens on the IO
 * thread, never in `Application.onCreate`. Failures other than cancellation are caught and published as
 * [StartupState.Failed] (never logged: nothing here may carry personal data); the app shell shows them with a
 * retry button that calls [retry].
 *
 * @param seedDemoHousehold the demo seeding, or null to skip it; set only in debug and benchmark builds
 *   ([BenchmarkSeed]).
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
    private val resumeRemoval: () -> Boolean = { false },
    private val removing: () -> Boolean = { false },
    private val chatStartup: suspend () -> Unit = {},
    private val sweepVoiceTempFiles: () -> Unit = {},
    private val refreshAiStatus: suspend () -> Unit = {},
    private val scheduleM7: () -> Unit = {},
    private val blankWidgets: suspend () -> Unit = {},
    private val checkKeptChatKey: suspend () -> Unit = {},
    private val resumeChatSeed: suspend (Session) -> Unit = {},
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
        removals: Lazy<RemovalHandler>,
        chat: Lazy<ChatMaintenance>,
        voiceTempFiles: Lazy<VoiceTempFiles>,
        ai: Lazy<AiGateway>,
        @AppForeground foreground: Flow<Boolean>,
        benchmarkSeed: Lazy<BenchmarkSeed>,
        chatPrunes: Lazy<ChatPruneScheduler>,
        widgets: Lazy<WidgetDataObserver>,
        widgetBlanker: Lazy<WidgetBlanker>,
        keptChatKey: Lazy<KeptChatKeyCheck>,
        chatIdentity: Lazy<ChatIdentityKeeper>,
    ) : this(
        scope = scope,
        seedDemoHousehold = if (BenchmarkSeed.enabled) {
            { benchmarkSeed.get().seed() }
        } else {
            demoSeeding(householdBootstrap, BuildConfig.DEBUG)
        },
        quarantineLedger = { ledger.get().quarantineUnverifiable() },
        planner = planner,
        scheduler = scheduler,
        clearPhotoCache = { photoCache.get().clear() },
        initialiseFirebase = { if (BenchmarkSeed.usesFirebase) firebase.get().initialise() },
        reconcileSession = { reconciler.get().reconcile() },
        wasLinked = { preferences.get().wasLinked() },
        startSync = { sync.get().start() },
        resumeRemoval = { removals.get().resume() },
        removing = { removals.get().isRemoving },
        chatStartup = { chat.get().atStartup() },
        sweepVoiceTempFiles = { voiceTempFiles.get().sweep() },
        refreshAiStatus = {
            foreground.first { it }
            val status = ai.get().status()
            Timber.tag("SproutAi").d("status=%s reason=%s", status.availability, status.reason)
        },
        scheduleM7 = {
            chatPrunes.get().scheduleDaily()
            widgets.get().start()
        },
        blankWidgets = { widgetBlanker.get().blankNow() },
        checkKeptChatKey = { keptChatKey.get()() },
        resumeChatSeed = { chatIdentity.get().resumeSeed(it) },
    )

    private val started = AtomicBoolean(false)
    private val running = AtomicBoolean(false)
    private val mutableState = MutableStateFlow<StartupState>(StartupState.Running)

    override val state: StateFlow<StartupState> = mutableState.asStateFlow()

    /** Starts the work once per process; later calls do nothing. Returns immediately. */
    fun start() {
        if (started.compareAndSet(false, true)) {
            launchWork()
            scope.launch { refreshAiStatusBestEffort() }
        }
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
        steps()
        StartupState.Ready
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (removing()) StartupState.Ready else StartupState.Failed(failureKind(e))
    }

    /** The steps; returns early once a removal is set. */
    @Suppress("ReturnCount") // One check between each pair of steps.
    private suspend fun steps() {
        blankWidgetsIfRemovedBestEffort()
        clearPhotoCacheBestEffort()
        sweepVoiceTempFilesBestEffort()
        initialiseFirebaseBestEffort()
        if (resumeRemoval()) return
        var session = reconcileSession()
        if (removing()) return
        val reconciled = session
        if (reconciled == null) {
            scope.launch { checkKeptChatKeyBestEffort() }
        } else if (!reconciled.isLocalTrial) {
            resumeChatSeedBestEffort(reconciled)
        }
        if (session == null && !wasLinked()) {
            seedDemoHousehold?.let { seed ->
                seed()
                session = reconcileSession()
            }
        }
        if (removing()) return
        quarantineLedger()
        if (removing()) return
        chatStartupBestEffort()
        if (removing()) return
        if (session?.isLocalTrial == true) planBestEffort()
        scheduler.get().scheduleDaily()
        scheduleM7()
        if (removing()) return
        startSync()
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

    /** Runs on the IO scope. A file system failure never fails startup; the next start sweeps again. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun sweepVoiceTempFilesBestEffort() {
        try {
            sweepVoiceTempFiles()
        } catch (expected: Exception) {
            // Nothing to show or log.
        }
    }

    /** The M4 upkeep never fails startup: each of its steps runs again after the next sync. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private suspend fun chatStartupBestEffort() {
        try {
            chatStartup()
        } catch (e: CancellationException) {
            throw e
        } catch (expected: Exception) {
            // Nothing to show or log.
        }
    }

    /**
     * With a removal stored, the widget is drawn blank first. Best effort, the removal check included: a failure here
     * never fails startup or changes what the later steps do (they read the removal again).
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private suspend fun blankWidgetsIfRemovedBestEffort() {
        try {
            if (removing()) blankWidgets()
        } catch (e: CancellationException) {
            throw e
        } catch (expected: Exception) {
            // Nothing to show or log.
        }
    }

    /** The kept chat key check (D-REL-28) never fails or delays startup; the next start without a session repeats. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private suspend fun checkKeptChatKeyBestEffort() {
        try {
            checkKeptChatKey()
        } catch (e: CancellationException) {
            throw e
        } catch (expected: Exception) {
            // Nothing to show or log.
        }
    }

    /** Before sync or chat runs; a failure never fails startup (the record stays, and the next start tries again). */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private suspend fun resumeChatSeedBestEffort(session: Session) {
        try {
            resumeChatSeed(session)
        } catch (e: CancellationException) {
            throw e
        } catch (expected: Exception) {
            // Nothing to show or log.
        }
    }

    /** The AI status read never fails or delays startup; the next AI call reads it again anyway. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private suspend fun refreshAiStatusBestEffort() {
        try {
            refreshAiStatus()
        } catch (e: CancellationException) {
            throw e
        } catch (expected: Exception) {
            // Nothing to show or log.
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
