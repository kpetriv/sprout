package dev.kpetriv.sprout.feature.wallet

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kpetriv.sprout.core.database.AllowanceRepository
import dev.kpetriv.sprout.core.database.RewardRepository
import dev.kpetriv.sprout.core.domainledger.LedgerRepository
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.model.RewardId
import dev.kpetriv.sprout.core.time.HouseholdCalendar
import dev.kpetriv.sprout.core.ui.MoneyFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The rewards shop for one kid: the catalogue the kid can see ([RewardRepository.observeRewards]), priced against
 * the kid's money balance ([LedgerRepository.observeBalances]) with "days until" estimates from the kid's active
 * allowance ([AllowanceRepository]) and today's date ([HouseholdCalendar]).
 *
 * Buying goes through [RewardPurchaser] (`PurchaseRewardUseCase`: a kid-authored `RewardPurchased`, plus the time bank
 * credit for a screen-time reward) after the kid confirms in a dialog; only [WalletMode.KID] may buy. The Sapling
 * wishlist is a set of reward ids kept in the [SavedStateHandle] under [WISHLIST_KEY] (M2: not persisted beyond the
 * saved state).
 *
 * Arguments come from the [SavedStateHandle]: [KID_ID_KEY] (required) and [MODE_KEY] (a [WalletMode] name, default
 * [WalletMode.KID]); [ShopRoute] sets both.
 */
@Suppress("LongParameterList") // One dependency per data source the shop combines; no natural grouping.
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ShopViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val dataSource: WalletDataSource,
    private val ledgerRepository: LedgerRepository,
    private val rewards: RewardRepository,
    private val allowances: AllowanceRepository,
    private val purchaser: RewardPurchaser,
    formatter: MoneyFormatter,
    private val calendar: HouseholdCalendar,
) : ViewModel() {
    private val kidId = KidId(checkNotNull(savedStateHandle.get<String>(KID_ID_KEY)) { "kidId argument missing" })
    private val mode = savedStateHandle.get<String>(MODE_KEY)?.let(WalletMode::valueOf) ?: WalletMode.KID
    private val mapper = ShopStateMapper(formatter)
    private val refreshes = MutableStateFlow(0)
    private val local = MutableStateFlow(ShopLocalState())
    private val wishlist = savedStateHandle.getStateFlow(WISHLIST_KEY, arrayListOf<String>())

    val state: StateFlow<ShopUiState> = combine(snapshots(), local, wishlist) { snapshot, localState, wished ->
        when (snapshot) {
            is Loaded -> mapper.ready(snapshot.data, localState, wished.toSet(), mode, calendar.today())
            Missing -> ShopUiState.Error(ShopError.KID_NOT_FOUND)
            Failed -> ShopUiState.Error(ShopError.LOAD_FAILED)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), ShopUiState.Loading)

    fun onEvent(event: ShopEvent) {
        when (event) {
            ShopEvent.Refresh -> refreshes.update { it + 1 }
            is ShopEvent.Select -> select(event.rewardId)
            ShopEvent.DismissConfirm -> local.update { if (it.isPurchasing) it else it.copy(selected = null) }
            ShopEvent.ConfirmPurchase -> confirm()
            is ShopEvent.ToggleWishlist -> toggleWishlist(event.rewardId)
            ShopEvent.MessageShown -> local.update { it.copy(message = null) }
        }
    }

    /**
     * Re-subscribes to the catalogue and balances only when the kid appears, disappears or changes tier; other profile
     * edits (a new name) flow through the kid's own subscription without restarting the rest.
     */
    private fun snapshots(): Flow<SnapshotResult> = refreshes.flatMapLatest {
        val kid = dataSource.observeKid(kidId)
        kid.map { it?.uiTier }.distinctUntilChanged().flatMapLatest { tier ->
            if (tier == null) {
                flowOf(Missing)
            } else {
                combine(
                    kid.filterNotNull(),
                    dataSource.accountsFor(kidId),
                    ledgerRepository.observeBalances(kidId),
                    rewards.observeRewards(kidId),
                    allowances.observeSchedules(kidId),
                ) { current, accounts, balances, catalogue, schedules ->
                    Loaded(ShopSnapshot(current, accounts, balances, catalogue, schedules))
                }
            }
        }.catch { emit(Failed) }
    }

    private fun select(rewardId: RewardId) {
        val ready = state.value as? ShopUiState.Ready ?: return
        if (ready.items.any { it.rewardId == rewardId }) {
            local.update { if (it.isPurchasing) it else it.copy(selected = rewardId) }
        }
    }

    private fun confirm() {
        val current = local.value
        val ready = state.value as? ShopUiState.Ready
        // The dialog on screen: a selection whose reward left the catalogue is no longer shown and cannot be bought.
        val rewardId = ready?.selected?.rewardId?.takeIf { it == current.selected }
        val tier = ready?.tier
        when {
            rewardId == null || tier == null || current.isPurchasing -> Unit

            mode != WalletMode.KID -> local.update {
                it.copy(selected = null, message = ShopMessage.Failed(PurchaseFailure.NotAllowed))
            }

            else -> {
                local.update { it.copy(isPurchasing = true) }
                viewModelScope.launch {
                    val message = purchase(rewardId, tier)
                    local.update { it.copy(selected = null, isPurchasing = false, message = message) }
                }
            }
        }
    }

    /** Buys [rewardId] as it is in the catalogue now; a reward that is gone is [PurchaseFailure.CatalogueChanged]. */
    @Suppress("TooGenericExceptionCaught") // Any storage failure must surface as a message, not crash the shop.
    private suspend fun purchase(rewardId: RewardId, tier: KidUiTier): ShopMessage = try {
        val reward = rewards.get(rewardId)
        if (reward == null) {
            ShopMessage.Failed(PurchaseFailure.CatalogueChanged)
        } else {
            mapper.messageFor(purchaser.purchase(kidId, rewardId), reward, tier)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        ShopMessage.Failed(PurchaseFailure.Failed)
    }

    private fun toggleWishlist(rewardId: RewardId) {
        val current = wishlist.value
        val updated = if (rewardId.value in current) current - rewardId.value else current + rewardId.value
        savedStateHandle[WISHLIST_KEY] = ArrayList(updated)
    }

    private sealed interface SnapshotResult

    private data class Loaded(val data: ShopSnapshot) : SnapshotResult

    private data object Missing : SnapshotResult

    private data object Failed : SnapshotResult

    companion object {
        /** [SavedStateHandle] key of the kid id string. */
        const val KID_ID_KEY = "kidId"

        /** [SavedStateHandle] key of the [WalletMode] name. */
        const val MODE_KEY = "walletMode"

        /** [SavedStateHandle] key of the wishlisted reward ids (`ArrayList<String>`). */
        const val WISHLIST_KEY = "wishlist"

        private const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
