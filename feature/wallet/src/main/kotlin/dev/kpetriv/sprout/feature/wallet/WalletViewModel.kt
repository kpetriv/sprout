package dev.kpetriv.sprout.feature.wallet

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kpetriv.sprout.core.domainledger.Account
import dev.kpetriv.sprout.core.domainledger.AccountKind
import dev.kpetriv.sprout.core.domainledger.AppendResult
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.LedgerEvent
import dev.kpetriv.sprout.core.domainledger.LedgerRepository
import dev.kpetriv.sprout.core.domainledger.LedgerWriter
import dev.kpetriv.sprout.core.domainledger.ManualAdjustment
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.domainledger.Violation
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.ui.MoneyFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Wallet screen state for one kid. Reads the kid ([WalletDataSource]), balances, anomalies and recent events
 * ([LedgerRepository]); the history length follows the kid's tier and the viewer ([WalletStateMapper.eventLimitFor]).
 * Manual adjustments go through [LedgerWriter] as [ManualAdjustment] events on the main money account. A failed
 * adjustment shows its error in the sheet, or in a snackbar if the sheet was dismissed while it was saving.
 *
 * Arguments come from the [SavedStateHandle]: [KID_ID_KEY] (required) and [MODE_KEY] (a [WalletMode] name,
 * default [WalletMode.KID], the safe choice); [WalletRoute] sets both.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class WalletViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val dataSource: WalletDataSource,
    private val ledgerRepository: LedgerRepository,
    private val ledgerWriter: LedgerWriter,
    private val formatter: MoneyFormatter,
) : ViewModel() {
    private val kidId = KidId(checkNotNull(savedStateHandle.get<String>(KID_ID_KEY)) { "kidId argument missing" })
    private val mode = savedStateHandle.get<String>(MODE_KEY)?.let(WalletMode::valueOf) ?: WalletMode.KID
    private val mapper = WalletStateMapper(formatter)
    private val refreshes = MutableStateFlow(0)
    private val local = MutableStateFlow(WalletLocalState())

    val state: StateFlow<WalletUiState> = combine(snapshots(), local) { snapshot, localState ->
        when (snapshot) {
            is Loaded -> mapper.ready(snapshot.data, localState, mode)
            Missing -> WalletUiState.Error(WalletError.KID_NOT_FOUND)
            Failed -> WalletUiState.Error(WalletError.LOAD_FAILED)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), WalletUiState.Loading)

    fun onEvent(event: WalletEvent) {
        when (event) {
            WalletEvent.Refresh -> refreshes.update { it + 1 }
            WalletEvent.OpenAdjust -> openAdjust()
            WalletEvent.DismissAdjust -> local.update { it.copy(adjustment = null) }
            is WalletEvent.SubmitAdjust -> submitAdjust(event)
            is WalletEvent.FilterChanged -> local.update { it.copy(filter = event.category) }
            WalletEvent.MessageShown -> local.update { it.copy(message = null) }
        }
    }

    private fun snapshots(): Flow<SnapshotResult> = refreshes.flatMapLatest {
        dataSource.observeKid(kidId).flatMapLatest { kid ->
            if (kid == null) {
                flowOf(Missing)
            } else {
                combine(
                    dataSource.accountsFor(kidId),
                    ledgerRepository.observeBalances(kidId),
                    eventsFor(kid.uiTier),
                    ledgerRepository.observeAnomalies(kidId),
                ) { accounts, balances, events, anomalies ->
                    Loaded(WalletSnapshot(kid, accounts, balances, events, anomalies.size))
                }
            }
        }.catch { emit(Failed) }
    }

    private fun eventsFor(tier: KidUiTier): Flow<List<LedgerEvent>> {
        val limit = WalletStateMapper.eventLimitFor(tier, mode)
        return if (limit == 0) flowOf(emptyList()) else ledgerRepository.observeEvents(kidId, limit)
    }

    private fun openAdjust() {
        if (mode != WalletMode.PARENT) return
        viewModelScope.launch {
            val currency = mainAccount()?.currency as? Currency.Fiat ?: return@launch
            local.update { it.copy(adjustment = AdjustmentUi(currency)) }
        }
    }

    private fun submitAdjust(event: WalletEvent.SubmitAdjust) {
        val current = local.value.adjustment
        if (current == null || current.isSubmitting) return
        val invalid = when {
            mode != WalletMode.PARENT -> AdjustmentError.NotAllowed
            event.amountMinor <= 0 -> AdjustmentError.InvalidAmount
            event.reason.isBlank() -> AdjustmentError.ReasonRequired
            else -> null
        }
        setAdjustment(current.copy(isSubmitting = invalid == null, error = invalid))
        if (invalid == null) {
            viewModelScope.launch {
                val failure = appendAdjustment(event)
                local.update { state -> state.afterSubmit(failure) }
            }
        }
    }

    /** Appends the adjustment; returns null on success or the error to show. */
    @Suppress("TooGenericExceptionCaught") // Any storage failure must surface in the sheet, not crash the app.
    private suspend fun appendAdjustment(event: WalletEvent.SubmitAdjust): AdjustmentError? = try {
        val account = mainAccount()
        if (account == null) AdjustmentError.NoAccount else append(account, event)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        AdjustmentError.Failed
    }

    private suspend fun append(account: Account, event: WalletEvent.SubmitAdjust): AdjustmentError? {
        val amount = Money(event.amountMinor).let { if (event.sign == AdjustmentSign.DEBIT) -it else it }
        val result = ledgerWriter.append(kidId, account.id) { envelope ->
            ManualAdjustment(envelope, account.currency, amount, event.reason.trim())
        }
        return when (result) {
            AppendResult.Accepted, AppendResult.Duplicate -> null
            is AppendResult.Conflict -> AdjustmentError.Conflict
            is AppendResult.Rejected -> errorFor(result.violations, account.currency)
        }
    }

    /**
     * Closes the sheet on success. On failure the error goes to the sheet that is still saving, or, if that sheet was
     * dismissed meanwhile (or replaced by a fresh one), to a snackbar so it is never lost.
     */
    private fun WalletLocalState.afterSubmit(failure: AdjustmentError?): WalletLocalState {
        val sheet = adjustment
        return when {
            failure == null -> copy(adjustment = null, message = WalletMessage.AdjustmentSaved)
            sheet != null && sheet.isSubmitting -> copy(adjustment = sheet.copy(isSubmitting = false, error = failure))
            else -> copy(message = WalletMessage.AdjustmentFailed(failure))
        }
    }

    private fun errorFor(violations: List<Violation>, currency: Currency): AdjustmentError {
        val first = violations.firstOrNull()
        return when (first) {
            is Violation.InsufficientFunds -> AdjustmentError.InsufficientFunds(
                formatter.format(first.available, currency),
            )

            is Violation.ParentOnly -> AdjustmentError.NotAllowed

            is Violation.ZeroAdjustment, is Violation.NonPositiveAmount -> AdjustmentError.InvalidAmount

            is Violation.BlankText -> AdjustmentError.ReasonRequired

            is Violation.UnknownAccount, is Violation.WrongAccountKind -> AdjustmentError.NoAccount

            else -> AdjustmentError.Failed
        }
    }

    private suspend fun mainAccount() = dataSource.accountsFor(kidId).first()
        .firstOrNull { it.kind == AccountKind.MAIN && it.currency is Currency.Fiat }

    private fun setAdjustment(adjustment: AdjustmentUi) = local.update { it.copy(adjustment = adjustment) }

    private sealed interface SnapshotResult

    private data class Loaded(val data: WalletSnapshot) : SnapshotResult

    private data object Missing : SnapshotResult

    private data object Failed : SnapshotResult

    companion object {
        /** [SavedStateHandle] key of the kid id string. */
        const val KID_ID_KEY = "kidId"

        /** [SavedStateHandle] key of the [WalletMode] name. */
        const val MODE_KEY = "walletMode"

        private const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
