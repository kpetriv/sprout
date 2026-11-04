package dev.kpetriv.sprout.feature.wallet

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kpetriv.sprout.core.crypto.ParentAuthUnavailableException
import dev.kpetriv.sprout.core.database.HouseholdPolicyRepository
import dev.kpetriv.sprout.core.database.SavingsGoalRepository
import dev.kpetriv.sprout.core.database.usecase.ManualAdjustmentResult
import dev.kpetriv.sprout.core.database.usecase.NO_ACCOUNT_REJECTION
import dev.kpetriv.sprout.core.database.usecase.RecordManualAdjustmentUseCase
import dev.kpetriv.sprout.core.domainledger.Account
import dev.kpetriv.sprout.core.domainledger.AccountKind
import dev.kpetriv.sprout.core.domainledger.AppendResult
import dev.kpetriv.sprout.core.domainledger.Balance
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.LedgerAnomaly
import dev.kpetriv.sprout.core.domainledger.LedgerEvent
import dev.kpetriv.sprout.core.domainledger.LedgerRepository
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.domainledger.ParentAuthorization
import dev.kpetriv.sprout.core.domainledger.ParentAuthorizationRequester
import dev.kpetriv.sprout.core.domainledger.Violation
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.ui.MoneyFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

/**
 * Wallet screen state for one kid. Reads the kid ([WalletDataSource]), balances, anomalies and recent events
 * ([LedgerRepository]), the kid's savings goals ([SavingsGoalRepository]) and the household policy
 * ([HouseholdPolicyRepository]); the history length follows the kid's tier and the viewer
 * ([WalletStateMapper.eventLimitFor]).
 *
 * Manual adjustments are parent-signed `ManualAdjustment` events ([RecordManualAdjustmentUseCase]): each submit asks
 * [ParentAuthorizationRequester] for one authorisation first. On a device without a strong biometric
 * ([ParentAuthUnavailableException]) the use case falls back per D-M2-1 to an unsigned append when the household
 * policy has parent signatures off (the sheet warns about it up front), and refuses otherwise. A failed adjustment
 * shows its error in the sheet, or in a snackbar if the sheet was dismissed while it was saving.
 *
 * "Scan a receipt" (M6 §3.4) hands the picked image to [ReceiptScanner] (within [SCAN_TIMEOUT_MILLIS]) and puts what it
 * found into [AdjustmentUi.prefill] for the form to take; the parent still reviews and signs.
 *
 * Arguments come from the [SavedStateHandle]: [KID_ID_KEY] (required) and [MODE_KEY] (a [WalletMode] name,
 * default [WalletMode.KID], the safe choice); [WalletRoute] sets both.
 */
@Suppress("LongParameterList") // One dependency per data source the wallet combines; no natural grouping.
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class WalletViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val dataSource: WalletDataSource,
    private val ledgerRepository: LedgerRepository,
    private val savingsGoals: SavingsGoalRepository,
    private val policies: HouseholdPolicyRepository,
    private val recordAdjustment: RecordManualAdjustmentUseCase,
    private val authorizations: ParentAuthorizationRequester,
    private val promptText: AdjustmentPromptText,
    private val formatter: MoneyFormatter,
    private val receiptScanner: ReceiptScanner,
) : ViewModel() {
    private val kidId = KidId(checkNotNull(savedStateHandle.get<String>(KID_ID_KEY)) { "kidId argument missing" })
    private val mode = savedStateHandle.get<String>(MODE_KEY)?.let(WalletMode::valueOf) ?: WalletMode.KID
    private val mapper = WalletStateMapper(formatter)
    private val refreshes = MutableStateFlow(0)
    private val local = MutableStateFlow(WalletLocalState())

    val state: StateFlow<WalletUiState> = combine(snapshots(), local, policyState()) { snapshot, localState, policy ->
        when (snapshot) {
            is Loaded -> mapper.ready(snapshot.data, localState, mode, policy)
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
            is WalletEvent.ReceiptPicked -> scanReceipt(event.uri)
            WalletEvent.PrefillApplied -> local.update { it.copy(adjustment = it.adjustment?.copy(prefill = null)) }
            is WalletEvent.FilterChanged -> local.update { it.copy(filter = event.category) }
            WalletEvent.MessageShown -> local.update { it.copy(message = null) }
        }
    }

    /**
     * Re-subscribes to the ledger only when the kid appears, disappears or changes tier (the tier sets the history
     * length); other profile edits (a new name) flow through the kid's own subscription without restarting the rest.
     */
    private fun snapshots(): Flow<SnapshotResult> = refreshes.flatMapLatest {
        val kid = dataSource.observeKid(kidId)
        kid.map { it?.uiTier }.distinctUntilChanged().flatMapLatest { tier ->
            if (tier == null) {
                flowOf(Missing)
            } else {
                val ledger = combine(
                    dataSource.accountsFor(kidId),
                    ledgerRepository.observeBalances(kidId),
                    eventsFor(tier),
                    ledgerRepository.observeAnomalies(kidId),
                ) { accounts, balances, events, anomalies -> LedgerPart(accounts, balances, events, anomalies) }
                combine(kid.filterNotNull(), ledger, savingsGoals.observeGoals(kidId)) { current, part, goals ->
                    Loaded(
                        WalletSnapshot(
                            current,
                            part.accounts,
                            part.balances,
                            part.events,
                            part.anomalies.size,
                            goals,
                            WalletStateMapper.excludedGames(part.anomalies),
                        ),
                    )
                }
            }
        }.catch { emit(Failed) }
    }

    private class LedgerPart(
        val accounts: List<Account>,
        val balances: List<Balance>,
        val events: List<LedgerEvent>,
        val anomalies: List<LedgerAnomaly>,
    )

    /**
     * Only parents see the policy (it drives the sheet's unsigned warning). No policy row yet means the ledger default:
     * no parent signatures. A failed read shows no warning rather than failing the wallet.
     */
    private fun policyState(): Flow<WalletPolicyState> = if (mode != WalletMode.PARENT) {
        flowOf(WalletPolicyState())
    } else {
        policies.observe()
            .map { WalletPolicyState(parentSignaturesOff = it?.parentSignaturesOff ?: true) }
            .catch { emit(WalletPolicyState()) }
    }

    private fun eventsFor(tier: KidUiTier): Flow<List<LedgerEvent>> {
        val limit = WalletStateMapper.eventLimitFor(tier, mode)
        return if (limit == 0) flowOf(emptyList()) else ledgerRepository.observeEvents(kidId, limit)
    }

    private fun openAdjust() {
        if (mode != WalletMode.PARENT) return
        viewModelScope.launch {
            val currency = mainAccount()?.currency as? Currency.Fiat ?: return@launch
            local.update { it.copy(adjustment = AdjustmentUi(currency, canScan = scanAvailable())) }
        }
    }

    private fun scanReceipt(uri: String) {
        if (mode != WalletMode.PARENT) return
        val sheet = local.value.adjustment?.takeIf { it.canScan && !it.scanning && !it.isSubmitting } ?: return
        setAdjustment(sheet.copy(scanning = true, scanSlow = false, scanFailed = false, prefill = null))
        viewModelScope.launch {
            var found: AdjustmentPrefill? = null
            // D-M6-54: the first scan on a device can take over 10 s while Play services loads text recognition.
            val slow = launch {
                delay(SCAN_SLOW_MILLIS.milliseconds)
                local.update { state ->
                    state.copy(adjustment = state.adjustment?.takeIf { it.scanning }?.copy(scanSlow = true))
                }
            }
            try {
                found = scanned(uri)
            } finally {
                slow.cancel()
                // Review M6: however the scan ends (even with this coroutine cancelled), the sheet stops scanning.
                // "No total found" (scanFailed) also when only a merchant was read (review L14); a merchant still
                // prefills the reason.
                val usable = found?.takeIf { it.hasTotal() || !it.merchant.isNullOrBlank() }
                val noTotal = usable?.hasTotal() != true
                local.update { state ->
                    val open = state.adjustment ?: return@update state // The sheet was dismissed meanwhile.
                    val done = open.copy(scanning = false, scanSlow = false, scanFailed = noTotal, prefill = usable)
                    state.copy(adjustment = done)
                }
            }
        }
    }

    /**
     * The scanner's prefill, or null when it fails, times out or throws a [CancellationException] of its own (an ML Kit
     * task cancelled by Play services while this coroutine is still active, review M6). Cancellation of this coroutine
     * itself still propagates.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // The parent types the amount instead; nothing logged.
    private suspend fun scanned(uri: String): AdjustmentPrefill? = try {
        withTimeoutOrNull(SCAN_TIMEOUT_MILLIS.milliseconds) { receiptScanner.scan(uri) }
    } catch (e: CancellationException) {
        currentCoroutineContext().ensureActive()
        null
    } catch (e: Exception) {
        null // An unreadable image or a recogniser failure.
    }

    /** [ReceiptScanner.isAvailable]; false when it fails (the sheet then just has no scan button). */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // Not offering a scan is the safe answer.
    private suspend fun scanAvailable(): Boolean = try {
        receiptScanner.isAvailable()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    private fun AdjustmentPrefill.hasTotal(): Boolean = (amount?.minorUnits ?: 0) > 0

    private fun submitAdjust(event: WalletEvent.SubmitAdjust) {
        val current = local.value.adjustment
        // Audit L5: nothing is saved while a receipt is being read (the sheet's Save is disabled too).
        if (current == null || current.isSubmitting || current.scanning) return
        val invalid = when {
            mode != WalletMode.PARENT -> AdjustmentError.NotAllowed
            event.amountMinor <= 0 -> AdjustmentError.InvalidAmount
            event.reason.isBlank() -> AdjustmentError.ReasonRequired
            else -> null
        }
        setAdjustment(current.copy(isSubmitting = invalid == null, error = invalid))
        if (invalid == null) {
            viewModelScope.launch {
                val failure = appendAdjustment(event, current.currency)
                local.update { state -> state.afterSubmit(failure) }
            }
        }
    }

    /**
     * Authorises and appends the adjustment through [RecordManualAdjustmentUseCase]; returns null on success or the
     * error to show. A dismissed or failed prompt is [AdjustmentError.AuthorizationFailed]; a device that cannot sign
     * while the household requires signatures is [AdjustmentError.ParentAuthUnavailable] (D-M2-1).
     */
    @Suppress("TooGenericExceptionCaught") // Any prompt or storage failure must surface in the sheet, not crash.
    private suspend fun appendAdjustment(event: WalletEvent.SubmitAdjust, currency: Currency.Fiat): AdjustmentError? =
        try {
            val amount = Money(event.amountMinor).let { if (event.sign == AdjustmentSign.DEBIT) -it else it }
            val prompt = promptFor(amount, currency)
            when (val result = recordAdjustment(kidId, amount, event.reason.trim()) { authorize(prompt) }) {
                is ManualAdjustmentResult.Signed -> resultError(result.result, currency)

                is ManualAdjustmentResult.Unsigned -> resultError(result.result, currency)

                ManualAdjustmentResult.SignatureRequired -> AdjustmentError.ParentAuthUnavailable

                ManualAdjustmentResult.NoAccount -> AdjustmentError.NoAccount

                // Refused before the prompt: the parent was not asked, so map the rule it broke.
                is ManualAdjustmentResult.Refused -> errorFor(result.result.violations, currency)

                ManualAdjustmentResult.KeyInvalidated -> AdjustmentError.KeyInvalidated
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: AuthorizationAborted) {
            AdjustmentError.AuthorizationFailed
        } catch (_: Exception) {
            AdjustmentError.Failed
        }

    /**
     * One parent prompt. `ParentAuthUnavailableException` passes through untouched (the use case falls back per
     * D-M2-1); anything else means the parent did not authorise and is wrapped so it is told apart from a storage
     * failure.
     */
    @Suppress("TooGenericExceptionCaught") // Whatever the prompt throws means "not authorised".
    private suspend fun authorize(reason: String): ParentAuthorization = try {
        authorizations.request(reason)
    } catch (e: CancellationException) {
        throw e
    } catch (e: ParentAuthUnavailableException) {
        throw e
    } catch (e: Exception) {
        throw AuthorizationAborted(e)
    }

    private class AuthorizationAborted(cause: Throwable) : Exception(cause)

    private suspend fun promptFor(amount: Money, currency: Currency.Fiat): String {
        val name = dataSource.observeKid(kidId).first()?.firstName.orEmpty()
        return promptText.adjust(name, formatter.format(amount, currency, signed = true))
    }

    private fun resultError(result: AppendResult, currency: Currency): AdjustmentError? = when (result) {
        AppendResult.Accepted, AppendResult.Duplicate -> null

        is AppendResult.Conflict -> AdjustmentError.Conflict

        is AppendResult.Rejected -> if (result.reason == NO_ACCOUNT_REJECTION && result.violations.isEmpty()) {
            AdjustmentError.NoAccount
        } else {
            errorFor(result.violations, currency)
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

            is Violation.ParentSignatureMissing -> AdjustmentError.ParentAuthUnavailable

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

        /**
         * How long reading a receipt may take before the sheet says it found nothing: the first scan on a device waits
         * for Play services to load its text recognition (about 13 s on the emulator walk, D-M6-54).
         */
        const val SCAN_TIMEOUT_MILLIS = 25_000L

        /** After this long the sheet says "Preparing the scanner…" ([AdjustmentUi.scanSlow], D-M6-54). */
        const val SCAN_SLOW_MILLIS = 5_000L

        private const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
