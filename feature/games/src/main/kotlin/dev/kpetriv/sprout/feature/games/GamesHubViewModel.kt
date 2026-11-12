package dev.kpetriv.sprout.feature.games

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.games.Difficulty
import dev.kpetriv.sprout.core.games.GameCatalogue
import dev.kpetriv.sprout.core.games.GameKind
import dev.kpetriv.sprout.core.games.TablesSetup
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.KidUiTier
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
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
     * today. Playing is allowed when [capReached]; the play is then "for fun" (D-M5-7). [tablesSetup] is the kid's
     * Times tables setup for the tile's subtitle ("3, 4, 6"), the difficulty's default until one is saved; null when
     * the tier has no Times tables.
     */
    data class Ready(
        val tier: KidUiTier,
        val games: List<GameKind>,
        val remaining: Money,
        val currency: Currency.Fiat,
        val tablesSetup: TablesSetup? = null,
    ) : GamesHubUiState {
        val capReached: Boolean get() = !remaining.isPositive
    }
}

sealed interface GamesHubEvent {
    data object Retry : GamesHubEvent
}

/**
 * The games hub for a kid: the tier's games, today's remaining game credit from [GameCapSource] and the kid's Times
 * tables setup from [TablesSetupStore]. Arguments from the [SavedStateHandle]: [KID_ID_KEY] and [TIER_KEY] (a
 * [KidUiTier] name).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class GamesHubViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val cap: GameCapSource,
    private val currency: GameCurrencySource,
    private val setups: TablesSetupStore,
) : ViewModel() {
    private val kidId = KidId(checkNotNull(savedStateHandle.get<String>(KID_ID_KEY)) { "kidId argument missing" })
    private val tier = savedStateHandle.get<String>(TIER_KEY)?.let(KidUiTier::valueOf) ?: KidUiTier.SEEDLING
    private val retries = MutableStateFlow(0)
    private val games = GameCatalogue.forTier(tier)

    /** The tile's setup: the stored one, else the default; an unreadable store shows the default too. */
    private val tablesSetup = if (GameKind.TABLES in games) {
        val default = TablesSetup.defaultFor(Difficulty.forTier(tier))
        setups.observe(kidId).map { it ?: default }.catch { emit(default) }
    } else {
        flowOf(null)
    }

    val state: StateFlow<GamesHubUiState> = retries.flatMapLatest {
        // The household policy's currency, again when a pull brings it (D-REL-10).
        combine(cap.observeRemaining(kidId), currency.observeFiatCurrency(), tablesSetup) { remaining, fiat, setup ->
            GamesHubUiState.Ready(
                tier = tier,
                games = games,
                remaining = remaining,
                currency = fiat,
                tablesSetup = setup,
            ) as GamesHubUiState
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
