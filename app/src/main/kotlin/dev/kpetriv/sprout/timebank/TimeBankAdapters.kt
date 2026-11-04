package dev.kpetriv.sprout.timebank

import dev.kpetriv.sprout.core.database.RoomLedgerRepository
import dev.kpetriv.sprout.core.database.usecase.StartTimeBankUseCase
import dev.kpetriv.sprout.core.database.usecase.StopTimeBankUseCase
import dev.kpetriv.sprout.core.database.usecase.TimeBankResult
import dev.kpetriv.sprout.core.database.usecase.TimeBankStatusReader
import dev.kpetriv.sprout.core.domainledger.LedgerEvent
import dev.kpetriv.sprout.core.domainledger.TimeBankStarted
import dev.kpetriv.sprout.core.domainledger.TimeBankStopped
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.feature.timebank.TimeBankControl
import dev.kpetriv.sprout.feature.timebank.TimeBankRefusal
import dev.kpetriv.sprout.feature.timebank.TimeBankRefusedException
import dev.kpetriv.sprout.feature.timebank.TimeBankSession
import dev.kpetriv.sprout.feature.timebank.TimeBankSnapshot
import dev.kpetriv.sprout.feature.timebank.TimeBankStateSource
import dev.kpetriv.sprout.widget.WidgetRefresher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import javax.inject.Inject
import javax.inject.Singleton
import dev.kpetriv.sprout.core.database.usecase.TimeBankRefusal as UseCaseRefusal

/**
 * [TimeBankStateSource] over the ledger projection ([TimeBankStatusReader]): re-read whenever the kid's events or
 * balances change. Finished sessions come from the kid's newest [HISTORY_LIMIT] events: each `TimeBankStopped` with
 * the `createdAt` of its `TimeBankStarted` (the stop's own time when the start is older than that window).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LedgerTimeBankStateSource @Inject constructor(
    private val ledger: RoomLedgerRepository,
    private val reader: TimeBankStatusReader,
) : TimeBankStateSource {
    override fun observe(kidId: KidId): Flow<TimeBankSnapshot?> = combine(
        ledger.observeEvents(kidId, HISTORY_LIMIT),
        ledger.observeBalances(kidId),
    ) { events, _ -> events }
        .mapLatest { events ->
            reader.status(kidId)?.let { status ->
                TimeBankSnapshot(
                    running = status.running,
                    startedAt = status.startedAt,
                    balanceMinutes = status.balance.minorUnits,
                    sessions = sessionsOf(events),
                )
            }
        }
        .distinctUntilChanged()

    companion object {
        const val HISTORY_LIMIT: Int = 200

        /** Finished sessions in [events] (newest first, as the ledger lists them). */
        internal fun sessionsOf(events: List<LedgerEvent>): List<TimeBankSession> {
            val starts = events.filterIsInstance<TimeBankStarted>().associateBy { it.sessionId }
            return events.filterIsInstance<TimeBankStopped>().map { stop ->
                TimeBankSession(
                    startedAt = starts[stop.sessionId]?.envelope?.createdAt ?: stop.envelope.createdAt,
                    minutesUsed = stop.minutesUsed.minorUnits,
                )
            }
        }
    }
}

/**
 * [TimeBankControl] over the time bank use cases (M4 §4) plus what the app adds (M4 §5.3, D-M4-9): Start posts the
 * ongoing "screen time" notification ([TimeBankNotifier]) and schedules the auto-stop at zero ([TimeBankAutoStop]);
 * Stop cancels both and refreshes the widget. A refusal is a [TimeBankRefusedException]; a ledger rejection a plain
 * failure.
 */
@Singleton
class UseCaseTimeBankControl @Inject constructor(
    private val startUseCase: StartTimeBankUseCase,
    private val stopUseCase: StopTimeBankUseCase,
    private val reader: TimeBankStatusReader,
    private val notifier: TimeBankNotifier,
    private val autoStop: TimeBankAutoStop,
    private val widgets: WidgetRefresher = WidgetRefresher.None,
) : TimeBankControl {
    override suspend fun start(kidId: KidId): Result<Unit> = attempt {
        when (val result = startUseCase(kidId)) {
            is TimeBankResult.Started -> {
                val balance = reader.status(kidId)?.balance?.minorUnits ?: 0
                notifier.show(kidId, result.startedAt, balance)
                autoStop.schedule(kidId, result.startedAt, balance)
            }

            is TimeBankResult.Refused -> throw TimeBankRefusedException(result.reason.toPort())

            is TimeBankResult.Rejected, is TimeBankResult.Stopped -> error("time bank start rejected")
        }
    }

    override suspend fun stop(kidId: KidId): Result<Long> = attempt {
        when (val result = stopUseCase(kidId)) {
            is TimeBankResult.Stopped -> {
                stopped(kidId)
                result.minutesUsed
            }

            is TimeBankResult.Refused -> {
                // Nothing runs (another device stopped it): nothing should be shown either.
                if (result.reason == UseCaseRefusal.NOT_RUNNING) stopped(kidId)
                throw TimeBankRefusedException(result.reason.toPort())
            }

            is TimeBankResult.Rejected, is TimeBankResult.Started -> error("time bank stop rejected")
        }
    }

    /**
     * The session of [kidId] ended (here, by the auto-stop, the start-up reconcile or another device): the
     * notification and the auto-stop go, and the home screen widget shows the minutes left (M7 §4).
     */
    fun stopped(kidId: KidId) {
        notifier.cancel(kidId)
        autoStop.cancel(kidId)
        widgets.refresh()
    }

    @Suppress("TooGenericExceptionCaught") // Every failure is a result for the screen.
    private inline fun <T> attempt(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Exception) {
        Result.failure(failure)
    }

    private fun UseCaseRefusal.toPort(): TimeBankRefusal = when (this) {
        UseCaseRefusal.ALREADY_RUNNING -> TimeBankRefusal.ALREADY_RUNNING

        UseCaseRefusal.NOT_RUNNING -> TimeBankRefusal.NOT_RUNNING

        // No minutes account reads as an empty balance (the screen shows it so).
        UseCaseRefusal.NO_BALANCE, UseCaseRefusal.NO_ACCOUNT -> TimeBankRefusal.NO_BALANCE
    }
}
