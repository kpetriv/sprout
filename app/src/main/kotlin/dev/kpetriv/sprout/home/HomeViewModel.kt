package dev.kpetriv.sprout.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kpetriv.sprout.core.database.KidRepository
import dev.kpetriv.sprout.core.model.AgeBand
import dev.kpetriv.sprout.core.model.Kid
import dev.kpetriv.sprout.core.model.YearMonth
import dev.kpetriv.sprout.core.time.Clock
import dev.kpetriv.sprout.startup.StartupState
import dev.kpetriv.sprout.startup.StartupStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject

/**
 * The home kid list from [KidRepository.observeKids], combined with the process-start state ([StartupStatus]).
 *
 * Age bands are computed per row for the current month in [zone]; a row whose band cannot be computed gets null
 * ("Unknown") instead of failing the whole list. A failing kid read gives [HomeUiState.Error], and
 * [HomeEvent.Retry] re-subscribes (and retries the startup work if that failed). Tapping a kid is navigation,
 * handled by the screen's callback.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel internal constructor(
    private val kids: KidRepository,
    private val startup: StartupStatus,
    private val clock: Clock,
    private val zone: () -> ZoneId,
) : ViewModel() {
    @Inject
    constructor(kids: KidRepository, startup: StartupStatus, clock: Clock) :
        this(kids, startup, clock, ZoneId::systemDefault)

    private val retries = MutableStateFlow(0)

    val state: StateFlow<HomeUiState> = combine(startup.state, kidRows()) { startupState, rows ->
        val failure = (startupState as? StartupState.Failed)?.kind
        when {
            rows == null -> HomeUiState.Error(failure)
            startupState == StartupState.Running && rows.isEmpty() -> HomeUiState.Loading
            else -> HomeUiState.Ready(rows, failure)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), HomeUiState.Loading)

    fun onEvent(event: HomeEvent) {
        when (event) {
            HomeEvent.Retry -> {
                startup.retry()
                retries.update { it + 1 }
            }
        }
    }

    /** The rows, or null once the kid read has failed (until the next retry). */
    private fun kidRows(): Flow<List<KidRow>?> = retries.flatMapLatest {
        kids.observeKids()
            .map<List<Kid>, List<KidRow>?> { list ->
                val today = todayOrNull()
                list.map { kid -> KidRow(kid.id, kid.firstName, today?.let { ageBandOrNull(kid, it) }, kid.uiTier) }
            }
            .catch { emit(null) }
    }

    private fun ageBandOrNull(kid: Kid, today: YearMonth): AgeBand? = try {
        kid.ageBandOn(today)
    } catch (expected: IllegalArgumentException) {
        // A birth month after today (wrong device clock, bad profile): show "Unknown" for this row only.
        null
    }

    private fun todayOrNull(): YearMonth? = try {
        val date = Instant.ofEpochMilli(clock.now().toEpochMilliseconds()).atZone(zone())
        YearMonth(date.year, date.monthValue)
    } catch (expected: DateTimeException) {
        null
    } catch (expected: IllegalArgumentException) {
        null
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
