package dev.kpetriv.sprout.feature.chores

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kpetriv.sprout.core.database.ChoreRepository
import dev.kpetriv.sprout.core.database.FiatCurrencyProvider
import dev.kpetriv.sprout.core.database.KidRepository
import dev.kpetriv.sprout.core.database.SubmissionFilter
import dev.kpetriv.sprout.core.domainhousehold.Chore
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.model.ChoreId
import dev.kpetriv.sprout.core.model.ChoreSchedule
import dev.kpetriv.sprout.core.model.ChoreState
import dev.kpetriv.sprout.core.model.Kid
import dev.kpetriv.sprout.core.model.KidId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The parent's chore list. */
sealed interface ChoreListUiState {
    data object Loading : ChoreListUiState

    data object Error : ChoreListUiState

    /** Chores grouped by kid, in the kids' order; a kid without chores has an empty group. */
    data class Ready(
        val groups: List<KidChoresUi>,
        val showArchived: Boolean = false,
        val pendingApprovals: Int = 0,
        val message: ChoreListMessage? = null,
    ) : ChoreListUiState
}

/** One kid's chores; [tintIndex] is the kid's place in the household (their avatar tint, as on the dashboard). */
data class KidChoresUi(val kidId: KidId, val kidName: String, val chores: List<ChoreRowUi>, val tintIndex: Int = 0)

data class ChoreRowUi(
    val id: ChoreId,
    val title: String,
    val reward: RewardUi,
    val schedule: ChoreSchedule,
    val state: ChoreState,
)

sealed interface ChoreListMessage {
    data object Archived : ChoreListMessage

    data object Restored : ChoreListMessage

    data object Failed : ChoreListMessage
}

sealed interface ChoreListEvent {
    data object ToggleArchived : ChoreListEvent

    data class Archive(val id: ChoreId) : ChoreListEvent

    data class Restore(val id: ChoreId) : ChoreListEvent

    data object Retry : ChoreListEvent

    data object MessageShown : ChoreListEvent
}

/**
 * Every kid's chores for the parent ([ChoreRepository.observeChores]), grouped by kid. Archiving moves a chore to
 * [ChoreState.ARCHIVED]; archived chores are listed only with [ChoreListUiState.Ready.showArchived]. Restoring an
 * archived chore makes it active again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChoreListViewModel @Inject constructor(
    private val chores: ChoreRepository,
    private val kids: KidRepository,
    private val fiat: FiatCurrencyProvider,
) : ViewModel() {
    private val local = MutableStateFlow(Local())
    private val retries = MutableStateFlow(0)

    val state: StateFlow<ChoreListUiState> = retries.flatMapLatest {
        combine(
            kids.observeKids(),
            local.map {
                it.showArchived
            }.distinctUntilChanged().flatMapLatest { chores.observeChores(includeArchived = it) },
            chores.observeSubmissions(SubmissionFilter.PendingForParent),
            local,
            // The household policy's currency, again when a pull brings it (D-REL-10).
            fiat.observeFiatCurrency(),
        ) { kidList, choreList, pending, localState, currency ->
            ChoreListUiState.Ready(
                groups = group(kidList, choreList, currency),
                showArchived = localState.showArchived,
                pendingApprovals = pending.size,
                message = localState.message,
            ) as ChoreListUiState
        }.catch { emit(ChoreListUiState.Error) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), ChoreListUiState.Loading)

    fun onEvent(event: ChoreListEvent) {
        when (event) {
            ChoreListEvent.ToggleArchived -> local.update { it.copy(showArchived = !it.showArchived) }
            is ChoreListEvent.Archive -> setState(event.id, ChoreState.ARCHIVED, ChoreListMessage.Archived)
            is ChoreListEvent.Restore -> setState(event.id, ChoreState.ACTIVE, ChoreListMessage.Restored)
            ChoreListEvent.Retry -> retries.update { it + 1 }
            ChoreListEvent.MessageShown -> local.update { it.copy(message = null) }
        }
    }

    private fun setState(id: ChoreId, target: ChoreState, success: ChoreListMessage) {
        viewModelScope.launch {
            val ok = attempt { chores.setState(id, target) }.getOrDefault(false)
            local.update { it.copy(message = if (ok) success else ChoreListMessage.Failed) }
        }
    }

    private data class Local(val showArchived: Boolean = false, val message: ChoreListMessage? = null)

    internal companion object {
        /** Groups [chores] under [kids] (in that order); chores of kids not in [kids] are left out. */
        fun group(kids: List<Kid>, chores: List<Chore>, currency: Currency.Fiat): List<KidChoresUi> {
            val byKid = chores.groupBy { it.kidId }
            return kids.mapIndexed { index, kid ->
                KidChoresUi(
                    tintIndex = index,
                    kidId = kid.id,
                    kidName = kid.firstName,
                    chores = byKid[kid.id].orEmpty()
                        .sortedWith(compareBy<Chore> { it.state.ordinal }.thenBy { it.title.lowercase() })
                        .map { ChoreRowUi(it.id, it.title, RewardUi.of(it.reward, currency), it.schedule, it.state) },
                )
            }
        }
    }
}
