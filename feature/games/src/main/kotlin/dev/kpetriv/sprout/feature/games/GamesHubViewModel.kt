package dev.kpetriv.sprout.feature.games

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.games.GameCatalogue
import dev.kpetriv.sprout.core.games.GameKind
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.KidUiTier
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import javax.inject.Inject

sealed interface GamesHubUiState {
    data object Loading : GamesHubUiState

    data object Error : GamesHubUiState

    /**
     * [games] are the tier's games ([GameCatalogue.forTier]); [remaining] is what the kid can still earn from games
     * today. Playing is allowed when [capReached]; the play is then "for fun" (D-M5-7).
     */
    data class Ready(
        val tier: KidUiTier,
        val games: List<GameKind>,
        val remaining: Money,
        val currency: Currency.Fiat,
    ) : GamesHubUiState {
        val capReached: Boolean get() = !remaining.isPositive
    }
}

sealed interface GamesHubEvent {
    data object Retry : GamesHubEvent
}

/**
 * The games hub for a kid: the tier's games and today's remaining game credit from [GameCapSource]. Arguments from
 * the [SavedStateHandle]: [KID_ID_KEY] and [TIER_KEY] (a [KidUiTier] name).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class GamesHubViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val cap: GameCapSource,
    private val currency: GameCurrencySource,
) : ViewModel() {
    private val kidId = KidId(checkNotNull(savedStateHandle.get<String>(KID_ID_KEY)) { "kidId argument missing" })
    private val tier = savedStateHandle.get<String>(TIER_KEY)?.let(KidUiTier::valueOf) ?: KidUiTier.SEEDLING
    private val retries = MutableStateFlow(0)

    val state: StateFlow<GamesHubUiState> = retries.flatMapLatest {
        cap.observeRemaining(kidId)
            .map<Money, GamesHubUiState> { remaining ->
                GamesHubUiState.Ready(
                    tier = tier,
                    games = GameCatalogue.forTier(tier),
                    remaining = remaining,
                    currency = currency.fiatCurrency(),
                )
            }
            .onStart { emit(GamesHubUiState.Loading) }
            .catch { emit(GamesHubUiState.Error) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), GamesHubUiState.Loading)

    fun onEvent(event: GamesHubEvent) {
        when (event) {
            GamesHubEvent.Retry -> retries.update { it + 1 }
        }
    }

    companion object {
        /** [SavedStateHandle] key of the kid id string. */
        const val KID_ID_KEY: String = "kidId"

        /** [SavedStateHandle] key of the [KidUiTier] name. */
        const val TIER_KEY: String = "tier"
    }
}
