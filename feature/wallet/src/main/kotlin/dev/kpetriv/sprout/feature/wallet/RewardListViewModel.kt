package dev.kpetriv.sprout.feature.wallet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kpetriv.sprout.core.database.KidRepository
import dev.kpetriv.sprout.core.database.RewardRepository
import dev.kpetriv.sprout.core.domainhousehold.Reward
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.model.Kid
import dev.kpetriv.sprout.core.model.RewardId
import dev.kpetriv.sprout.core.model.RewardKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/** The parent's rewards list (the household's shop catalogue). */
sealed interface RewardListUiState {
    data object Loading : RewardListUiState

    data object Error : RewardListUiState

    /** Every reward, those in the shop first and the hidden ones after, each group cheapest first. */
    data class Ready(val rewards: List<RewardRowUi>) : RewardListUiState
}

/**
 * One reward in the parent's list.
 *
 * @property minutes the screen time a [RewardKind.SCREEN_TIME] reward grants; null for the other kinds.
 * @property forKids who sees it in the shop.
 * @property active shown in the shop; false is "Hidden".
 */
data class RewardRowUi(
    val id: RewardId,
    val title: String,
    val kind: RewardKind,
    val cost: Money,
    val currency: Currency,
    val minutes: Int?,
    val forKids: RewardAudience,
    val active: Boolean,
)

/** Who a reward is for, as the list shows it. */
sealed interface RewardAudience {
    /** Every kid in the household ([Reward.forKids] empty). */
    data object AllKids : RewardAudience

    /**
     * The chosen kids still in the household, by first name in the household's order. Empty when every chosen kid has
     * left the household: then no kid sees the reward until the parent picks again.
     */
    data class Kids(val names: List<String>) : RewardAudience
}

sealed interface RewardListEvent {
    data object Retry : RewardListEvent
}

/**
 * Every reward in the household's catalogue for the parent ([RewardRepository.observeRewards] with no kid and the
 * hidden ones included), with the kids' names from [KidRepository.observeKids]. Adding and editing happen in the
 * editor ([RewardEditorViewModel]); this list only reads.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class RewardListViewModel @Inject constructor(private val rewards: RewardRepository, private val kids: KidRepository) :
    ViewModel() {
    private val retries = MutableStateFlow(0)

    val state: StateFlow<RewardListUiState> = retries.flatMapLatest {
        val catalogue = rewards.observeRewards(kidId = null, includeInactive = true)
        combine(catalogue, kids.observeKids()) { all, kidList ->
            RewardListUiState.Ready(rows(all, kidList)) as RewardListUiState
        }.catch { emit(RewardListUiState.Error) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), RewardListUiState.Loading)

    fun onEvent(event: RewardListEvent) {
        when (event) {
            RewardListEvent.Retry -> retries.update { it + 1 }
        }
    }

    internal companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        /**
         * [catalogue] as rows: rewards in the shop before hidden ones; within each, cheapest first, then by title.
         * Chosen kids are named in [kids]' order; a chosen kid no longer among [kids] is left out.
         */
        fun rows(catalogue: List<Reward>, kids: List<Kid>): List<RewardRowUi> = catalogue
            .sortedWith(
                compareByDescending<Reward> { it.active }
                    .thenBy { it.cost.minorUnits }
                    .thenBy { it.title.lowercase() },
            )
            .map { reward ->
                RewardRowUi(
                    id = reward.id,
                    title = reward.title,
                    kind = reward.kind,
                    cost = reward.cost,
                    currency = reward.currency,
                    minutes = reward.minutesGranted,
                    forKids = if (reward.forKids.isEmpty()) {
                        RewardAudience.AllKids
                    } else {
                        RewardAudience.Kids(kids.filter { it.id in reward.forKids }.map { it.firstName })
                    },
                    active = reward.active,
                )
            }
    }
}
