package dev.kpetriv.sprout.feature.timebank

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.KidUiTier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import javax.inject.Inject
import kotlin.time.Instant

/**
 * One kid's time bank. The running state, start time and balance come from [TimeBankStateSource] (the ledger
 * projection); while a session runs the screen ticks with [TimeBankClock.ticks] and computes elapsed time as
 * `now - startedAt`, so a restarted process shows the right time. Start and Stop go through [TimeBankControl]; the
 * ledger, not this ViewModel, decides how many minutes a session debits.
 *
 * Arguments from the [SavedStateHandle]: [KID_ID_KEY] and [TIER_KEY] (a [KidUiTier] name); [TimeBankRoute] sets both.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TimeBankViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val source: TimeBankStateSource,
    private val control: TimeBankControl,
    private val clock: TimeBankClock,
) : ViewModel() {
    private val kidId = KidId(checkNotNull(savedStateHandle.get<String>(KID_ID_KEY)) { "kidId argument missing" })
    private val tier = KidUiTier.valueOf(checkNotNull(savedStateHandle.get<String>(TIER_KEY)) { "tier missing" })
    private val refreshes = MutableStateFlow(0)
    private val local = MutableStateFlow(LocalState())

    val state: StateFlow<TimeBankUiState> = refreshes
        .flatMapLatest { snapshots() }
        .flatMapLatest { result ->
            when (result) {
                is Loaded -> {
                    val now = if (result.snapshot.running) clock.ticks() else flowOf(clock.now())
                    combine(now, local) { instant, localState -> ready(result.snapshot, instant, localState) }
                }

                Failed -> flowOf(TimeBankUiState.Error)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), TimeBankUiState.Loading)

    fun onEvent(event: TimeBankEvent) {
        when (event) {
            TimeBankEvent.Start -> start()
            TimeBankEvent.Stop -> stop()
            TimeBankEvent.MessageShown -> local.update { it.copy(message = null) }
            TimeBankEvent.Retry -> refreshes.update { it + 1 }
        }
    }

    private fun snapshots(): Flow<SnapshotResult> = source.observe(kidId)
        .map<TimeBankSnapshot?, SnapshotResult> { Loaded(it ?: EMPTY) }
        .catch { emit(Failed) }

    private fun ready(snapshot: TimeBankSnapshot, now: Instant, localState: LocalState): TimeBankUiState.Ready {
        val startedAt = snapshot.startedAt
        val elapsed = if (snapshot.running && startedAt != null) {
            (now - startedAt).inWholeSeconds.coerceAtLeast(0)
        } else {
            0L
        }
        val balanceSeconds = snapshot.balanceMinutes.coerceAtLeast(0) * TimeBankUiState.Ready.SECONDS_PER_MINUTE
        val zone = clock.zone()
        val today = LocalDateTime.ofInstant(now.toJava(), zone).toLocalDate()
        val sessions = snapshot.sessions
            .sortedByDescending { it.startedAt }
            .map { SessionUi(LocalDateTime.ofInstant(it.startedAt.toJava(), zone), it.minutesUsed) }
        return TimeBankUiState.Ready(
            tier = tier,
            balanceMinutes = snapshot.balanceMinutes,
            running = snapshot.running,
            elapsedSeconds = elapsed,
            remainingSeconds = (balanceSeconds - elapsed).coerceAtLeast(0),
            today = sessions.filter { it.startedAt.toLocalDate() == today },
            history = sessions.take(TimeBankUiState.Ready.HISTORY_SIZE),
            isBusy = localState.isBusy,
            message = localState.message,
        )
    }

    private fun start() {
        val ready = state.value as? TimeBankUiState.Ready ?: return
        if (!ready.canStart || !claim()) return
        viewModelScope.launch {
            val message = attempt(control::start).fold(
                onSuccess = { null },
                onFailure = { TimeBankMessage.StartFailed((it as? TimeBankRefusedException)?.reason) },
            )
            local.update { it.copy(isBusy = false, message = message ?: it.message) }
        }
    }

    private fun stop() {
        val ready = state.value as? TimeBankUiState.Ready ?: return
        if (!ready.canStop || !claim()) return
        viewModelScope.launch {
            val message = attempt(control::stop).fold(
                onSuccess = { TimeBankMessage.Stopped(it) },
                onFailure = { TimeBankMessage.StopFailed },
            )
            local.update { it.copy(isBusy = false, message = message) }
        }
    }

    /** Marks a write in flight; false when one already is. */
    private fun claim(): Boolean {
        var claimed = false
        local.update {
            claimed = !it.isBusy
            if (claimed) it.copy(isBusy = true) else it
        }
        return claimed
    }

    /** [call] for this kid; a thrown exception (not cancellation) becomes a failed result. */
    @Suppress("TooGenericExceptionCaught") // A storage failure must surface as a message, not crash the screen.
    private suspend fun <T> attempt(call: suspend (KidId) -> Result<T>): Result<T> = try {
        call(kidId)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    private data class LocalState(val isBusy: Boolean = false, val message: TimeBankMessage? = null)

    private sealed interface SnapshotResult

    private data class Loaded(val snapshot: TimeBankSnapshot) : SnapshotResult

    private data object Failed : SnapshotResult

    companion object {
        /** [SavedStateHandle] key of the kid id string. */
        const val KID_ID_KEY = "kidId"

        /** [SavedStateHandle] key of the [KidUiTier] name. */
        const val TIER_KEY = "tier"

        private const val STOP_TIMEOUT_MILLIS = 5_000L
        private val EMPTY = TimeBankSnapshot(running = false, startedAt = null, balanceMinutes = 0)
    }
}

private fun Instant.toJava(): java.time.Instant = java.time.Instant.ofEpochMilli(toEpochMilliseconds())
