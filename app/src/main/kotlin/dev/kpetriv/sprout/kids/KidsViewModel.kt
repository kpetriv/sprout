package dev.kpetriv.sprout.kids

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kpetriv.sprout.core.database.KidRepository
import dev.kpetriv.sprout.core.model.AgeBand
import dev.kpetriv.sprout.core.model.Kid
import dev.kpetriv.sprout.core.model.YearMonth
import dev.kpetriv.sprout.core.time.HouseholdCalendar
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
import javax.inject.Inject

/**
 * The app shell's kid list (the "switch to kid mode" and shop pickers) from [KidRepository.observeKids], combined with
 * the process-start state ([StartupStatus]), whose failure the shell shows as a full-screen error with retry.
 *
 * Age bands are computed per row for the current household month ([HouseholdCalendar]); a row whose band cannot be
 * computed gets null ("Unknown") instead of failing the whole list. A failing kid read gives [KidsUiState.Error], and
 * [KidsEvent.Retry] re-subscribes (and retries the startup work if that failed). Tapping a kid is navigation,
 * handled by the screen's callback.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class KidsViewModel @Inject constructor(
    private val kids: KidRepository,
    private val startup: StartupStatus,
    private val calendar: HouseholdCalendar,
) : ViewModel() {
    private val retries = MutableStateFlow(0)

    val state: StateFlow<KidsUiState> = combine(startup.state, kidRows()) { startupState, rows ->
        val failure = (startupState as? StartupState.Failed)?.kind
        when {
            rows == null -> KidsUiState.Error(failure)
            startupState == StartupState.Running && rows.isEmpty() -> KidsUiState.Loading
            else -> KidsUiState.Ready(rows, failure)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), KidsUiState.Loading)

    fun onEvent(event: KidsEvent) {
        when (event) {
            KidsEvent.Retry -> {
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
        calendar.today().yearMonth
    } catch (expected: DateTimeException) {
        null
    } catch (expected: IllegalArgumentException) {
        null
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
