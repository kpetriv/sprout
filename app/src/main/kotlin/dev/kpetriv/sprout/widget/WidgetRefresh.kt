package dev.kpetriv.sprout.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.widget.RemoteViews
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.glance.appwidget.updateAll
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.kpetriv.sprout.core.database.SproutDatabase
import dev.kpetriv.sprout.di.ApplicationScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Asks the home screen widget to redraw (M7 §4). Called after a successful sync (`SyncReportProcessor`), on local
 * ledger, chore and kid writes ([WidgetDataObserver]), after a time bank stop (`UseCaseTimeBankControl`), after a
 * removal's work cancel (`WorkManagerBackgroundWork`), when the "Sign in again" gate goes up or down
 * (`CredentialGate`), and by the 6-hourly [WidgetRefreshWorker]. A removal also blanks it at once ([WidgetBlanker]).
 */
fun interface WidgetRefresher {
    fun refresh()

    companion object {
        val None: WidgetRefresher = WidgetRefresher { }
    }
}

/**
 * [WidgetRefresher] over Glance: requests are conflated and debounced ([DEBOUNCE_MS]: a sync applies many rows) and
 * then every placed Sprout widget is updated on the app scope ([updateNow]). Failures are dropped unlogged; the next
 * request or the periodic work tries again.
 */
@Singleton
@OptIn(FlowPreview::class)
class GlanceWidgetRefresher internal constructor(
    private val scope: CoroutineScope,
    private val update: suspend () -> Unit,
    private val debounceMs: Long = DEBOUNCE_MS,
) : WidgetRefresher {
    @Inject
    constructor(@ApplicationScope scope: CoroutineScope, @ApplicationContext context: Context) :
        this(scope, { appSproutWidget().updateAll(context) })

    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val started = AtomicBoolean(false)

    override fun refresh() {
        if (started.compareAndSet(false, true)) {
            scope.launch {
                requests.consumeAsFlow().debounce(debounceMs).catch { }.collect { updateNow() }
            }
        }
        requests.trySend(Unit)
    }

    /** Updates every placed widget now (the periodic worker); best effort. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // A failed update waits for the next request.
    suspend fun updateNow() {
        try {
            update()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            // Dropped on purpose: nothing about the household is logged.
        }
    }

    companion object {
        const val DEBOUNCE_MS: Long = 500
    }
}

/**
 * Draws every placed Sprout widget blank at once (audit 2 N1, D-M7-81), for a removal: the wipe's last step before a
 * background process exits, and process start while a removal is stored. Unlike [WidgetRefresher] it neither waits
 * for a quiet period nor goes through Glance's session work, so the blank drawing reaches the launcher even when the
 * process ends right after.
 */
fun interface WidgetBlanker {
    suspend fun blankNow()

    companion object {
        val None: WidgetBlanker = WidgetBlanker { }
    }
}

/**
 * [WidgetBlanker] over [AppWidgetManager]: Glance composes [SproutWidgetContent] for [WidgetState.Blank] into
 * [RemoteViews] ([GlanceRemoteViews], the same content a Glance update draws for Blank, a tap still opens the app),
 * and the views are pushed to every placed widget id in one synchronous call. Nothing when no widget is placed.
 */
class RemoteViewsWidgetBlanker internal constructor(
    private val placedIds: () -> IntArray,
    private val compose: suspend () -> RemoteViews,
    private val push: (IntArray, RemoteViews) -> Unit,
) : WidgetBlanker {
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        placedIds = {
            AppWidgetManager.getInstance(context)
                .getAppWidgetIds(ComponentName(context, SproutWidgetReceiver::class.java))
        },
        compose = { blankWidgetViews(context) },
        push = { ids, views -> AppWidgetManager.getInstance(context).updateAppWidget(ids, views) },
    )

    override suspend fun blankNow() {
        val ids = placedIds()
        if (ids.isEmpty()) return
        push(ids, compose())
    }
}

/** The widget's Blank drawing as [RemoteViews] (no household data; a tap opens the app). */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
internal suspend fun blankWidgetViews(context: Context): RemoteViews =
    GlanceRemoteViews().compose(context, SproutWidget.SMALL) {
        SproutWidgetContent(WidgetState.Blank, SproutWidget.openAppAction(context))
    }.remoteViews

/** The widget's periodic fallback refresh ([WidgetRefreshWorker]). */
interface WidgetRefreshScheduler {
    /** Every [WidgetRefreshWorker.PERIOD_HOURS] hours while a widget is placed; idempotent. */
    fun schedulePeriodic()

    fun cancel()

    /** Whether a Sprout widget is on any home screen. */
    suspend fun widgetsPlaced(): Boolean
}

class WorkManagerWidgetRefreshScheduler @Inject constructor(@ApplicationContext private val context: Context) :
    WidgetRefreshScheduler {
    override fun schedulePeriodic() {
        val request = PeriodicWorkRequestBuilder<WidgetRefreshWorker>(WidgetRefreshWorker.PERIOD_HOURS, TimeUnit.HOURS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(WidgetRefreshWorker.UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    override fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(WidgetRefreshWorker.UNIQUE_NAME)
    }

    override suspend fun widgetsPlaced(): Boolean =
        GlanceAppWidgetManager(context).getGlanceIds(SproutWidget::class.java).isNotEmpty()
}

/**
 * The 6-hourly fallback (M7 §4): the date moves on at midnight and a phone that never syncs still shows today's
 * chores. Cancelled with the rest of the household's work on a removal (`WorkManagerBackgroundWork`).
 */
@HiltWorker
class WidgetRefreshWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val refresher: GlanceWidgetRefresher,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        refresher.updateNow()
        return Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "widget-refresh"
        const val PERIOD_HOURS = 6L
    }
}

/**
 * Refreshes the widget whenever a table it reads changes ([TABLES]: the ledger and balances, accounts, kids, chores
 * and submissions), whoever wrote it: this device's own writes and the rows a sync applied. Started once by
 * `AppInitializer` after the session check; Room's invalidation tracker needs no hook in `core/database`. Also keeps
 * the periodic fallback scheduled while a widget is placed.
 */
@Singleton
class WidgetDataObserver @Inject constructor(
    private val database: SproutDatabase,
    private val refresher: WidgetRefresher,
    private val scheduler: WidgetRefreshScheduler,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val started = AtomicBoolean(false)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            @Suppress("TooGenericExceptionCaught", "SwallowedException") // No widget host: nothing to schedule.
            try {
                if (scheduler.widgetsPlaced()) scheduler.schedulePeriodic()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                // Best effort.
            }
            // A database closed by a wipe ends the stream; the wipe's own refresh blanks the widget.
            @Suppress("SpreadOperator") // Six table names, once per process.
            database.invalidationTracker.createFlow(*TABLES).catch { }.collect { refresher.refresh() }
        }
    }

    companion object {
        val TABLES: Array<String> =
            arrayOf("ledger_events", "balances", "accounts", "kids", "chores", "chore_submissions")
    }
}

/**
 * The widget's receiver, registered in the manifest with `res/xml/sprout_widget_info.xml` (`widget/`). Placing the
 * first widget schedules the periodic refresh; removing the last cancels it.
 */
class SproutWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = appSproutWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        widgetEntryPoint(context).widgetRefreshScheduler().schedulePeriodic()
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        widgetEntryPoint(context).widgetRefreshScheduler().cancel()
    }
}
