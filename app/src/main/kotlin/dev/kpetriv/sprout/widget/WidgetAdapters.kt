package dev.kpetriv.sprout.widget

import android.content.Context
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.kpetriv.sprout.core.database.KidTodaySummary
import dev.kpetriv.sprout.core.database.KidTodaySummaryUseCase
import dev.kpetriv.sprout.core.database.Session
import dev.kpetriv.sprout.core.database.SessionStore
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.ui.MoneyFormatter
import dev.kpetriv.sprout.navigation.ShellSignals
import dev.kpetriv.sprout.startup.StartupState
import dev.kpetriv.sprout.startup.StartupStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/** Whose data the widget may show (D-M7-1): nobody's, a shortcut, or one kid's on that kid's own phone. */
internal sealed interface WidgetTarget {
    data object Blank : WidgetTarget

    data object OpenApp : WidgetTarget

    data class KidOf(val kidId: KidId) : WidgetTarget
}

/**
 * The widget's target for the shell's state (M7 §4, D-M7-1, ADR-0012), the same order of precedence as the app's
 * own mode (`appModeOf`):
 * - startup failed (the data cannot be read), a removal stored (wiped or not), or the "Sign in again" gate of a real
 *   session: [WidgetTarget.Blank], so nothing of the household stays on the home screen;
 * - a KID session, which exists only on that kid's own linked phone: that kid's day ([WidgetTarget.KidOf]);
 * - anything else (no session, a parent's phone, the trial household, kid mode on a parent's phone):
 *   [WidgetTarget.OpenApp]. The home screen is not `FLAG_SECURE`, so a parent's phone never shows a kid's data.
 */
internal fun widgetTargetOf(startup: StartupState, session: Session?, removal: Boolean, gate: Boolean): WidgetTarget =
    when {
        startup is StartupState.Failed || removal -> WidgetTarget.Blank
        session == null -> WidgetTarget.OpenApp
        !session.isLocalTrial && gate -> WidgetTarget.Blank
        session.role == Role.KID -> session.kidId?.let(WidgetTarget::KidOf) ?: WidgetTarget.OpenApp
        else -> WidgetTarget.OpenApp
    }

/** [summary] as the widget shows it: amounts formatted for the kid's tier (Seedling sees whole coins). */
internal fun KidTodaySummary.toWidgetState(formatter: MoneyFormatter): WidgetState.Kid = WidgetState.Kid(
    name = kidName,
    balance = fiatCurrency?.let { currency -> fiatBalance?.let { formatter.formatForTier(it, currency, tier) } },
    minutes = minutes?.let { formatter.formatForTier(it, Currency.Minutes, tier) },
    dueToday = dueToday,
    toDo = toDo,
    firstToDo = firstToDoTitles,
)

/**
 * The widget's [WidgetStateSource] over the shell: waits for the process-start work ([StartupStatus]), then follows
 * the session, the removal and the "Sign in again" gate ([widgetTargetOf]) and, on a linked kid phone, the kid's
 * [KidTodaySummaryUseCase] (a kid that is gone shows "Open Sprout"). Any failure (a database closed by a wipe) is
 * [WidgetState.Blank]. Nothing is logged.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionWidgetStateSource @Inject constructor(
    private val startup: StartupStatus,
    private val sessions: SessionStore,
    private val signals: ShellSignals,
    private val summaries: KidTodaySummaryUseCase,
) : WidgetStateSource {
    private val formatter = MoneyFormatter()

    override fun observe(): Flow<WidgetState> = combine(
        startup.state.filter { it != StartupState.Running },
        sessions.observe(),
        signals.removal,
        signals.gate,
    ) { state, session, removal, gate -> widgetTargetOf(state, session, removal != null, gate != null) }
        .distinctUntilChanged()
        .flatMapLatest { target ->
            when (target) {
                WidgetTarget.Blank -> flowOf(WidgetState.Blank)

                WidgetTarget.OpenApp -> flowOf(WidgetState.OpenApp)

                is WidgetTarget.KidOf -> summaries.observe(target.kidId).map { summary ->
                    summary?.toWidgetState(formatter) ?: WidgetState.OpenApp
                }
            }
        }
        .distinctUntilChanged()
        .catch { emit(WidgetState.Blank) }
}

/** How the widget receiver, which Hilt cannot inject, reaches the app's singletons. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun widgetStateSource(): WidgetStateSource

    fun widgetRefreshScheduler(): WidgetRefreshScheduler
}

internal fun widgetEntryPoint(context: Context): WidgetEntryPoint =
    EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java)

/** The app's [SproutWidget]: its state comes from [WidgetEntryPoint]. */
internal fun appSproutWidget(): SproutWidget = SproutWidget { context -> widgetEntryPoint(context).widgetStateSource() }
