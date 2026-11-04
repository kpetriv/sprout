package dev.kpetriv.sprout.feature.parenthome

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kpetriv.sprout.core.database.AccountRepository
import dev.kpetriv.sprout.core.database.AllowanceRepository
import dev.kpetriv.sprout.core.database.ChoreRepository
import dev.kpetriv.sprout.core.database.FailedParentAction
import dev.kpetriv.sprout.core.database.KidRepository
import dev.kpetriv.sprout.core.database.PendingParentActionRepository
import dev.kpetriv.sprout.core.database.SubmissionFilter
import dev.kpetriv.sprout.core.database.usecase.DrainResult
import dev.kpetriv.sprout.core.domainhousehold.AllowancePlanner
import dev.kpetriv.sprout.core.domainhousehold.AllowanceSchedule
import dev.kpetriv.sprout.core.domainhousehold.PendingParentAction
import dev.kpetriv.sprout.core.domainledger.Account
import dev.kpetriv.sprout.core.domainledger.AccountKind
import dev.kpetriv.sprout.core.domainledger.Balance
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.LedgerRepository
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.domainledger.ParentAuthorizationRequester
import dev.kpetriv.sprout.core.model.Kid
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.time.HouseholdCalendar
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
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The parent dashboard. Reads every kid's balances, anomalies and next allowance, the chore submissions awaiting
 * review, the queue of payments waiting for the parent's signature, and the payments that need attention (failed or
 * skipped). "Sign now" drains the queue through [PendingActionSigner] with the requester the app passes in: one
 * fingerprint prompt per payment, each naming the kid, the amount and the period (ADR-0004 amendments).
 *
 * The on-device weekly summary ([WeeklySummarySource], M6) is collected only while the dashboard is, for the week the
 * card's toggle picks; it starts as [WeeklySummaryUi.Loading] and a failing source becomes
 * [WeeklySummaryUi.Unavailable] without touching the rest of the dashboard.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
@Suppress("LongParameterList") // One repository or source per dashboard card, plus the signer and the calendar.
class ParentHomeViewModel @Inject constructor(
    private val kids: KidRepository,
    private val accounts: AccountRepository,
    private val ledger: LedgerRepository,
    private val chores: ChoreRepository,
    private val allowances: AllowanceRepository,
    private val pendingActions: PendingParentActionRepository,
    private val signer: PendingActionSigner,
    private val calendar: HouseholdCalendar,
    private val householdStatus: HouseholdStatusSource,
    private val summaries: WeeklySummarySource,
) : ViewModel() {
    private val local = MutableStateFlow(LocalState())
    private val summaryWeek = MutableStateFlow(SummaryWeek.THIS_WEEK)

    val state: StateFlow<ParentHomeUiState> = combine(
        dashboard(),
        local,
        household(),
        summary(),
    ) { loaded, localState, status, summary ->
        when (loaded) {
            Loaded.Loading -> ParentHomeUiState.Loading

            Loaded.Failed -> ParentHomeUiState.Error

            is Loaded.Ready -> loaded.data.copy(
                signing = localState.signing,
                signResult = localState.signResult,
                household = status,
                summaryWeek = summary.first,
                summary = summary.second,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), ParentHomeUiState.Loading)

    fun onEvent(event: ParentHomeEvent) {
        when (event) {
            is ParentHomeEvent.SignNow -> signNow(event.requester)
            ParentHomeEvent.SignResultShown -> local.update { it.copy(signResult = null) }
            is ParentHomeEvent.Dismiss -> dismiss(event.key)
            is ParentHomeEvent.SummaryWeekChanged -> summaryWeek.value = event.week
        }
    }

    /** The summary of the picked week; a new pick starts again from Loading. Never fails the dashboard. */
    private fun summary(): Flow<Pair<SummaryWeek, WeeklySummaryUi>> = summaryWeek.flatMapLatest { week ->
        summaries.observe(week)
            .onStart { emit(WeeklySummaryUi.Loading) }
            .catch { emit(WeeklySummaryUi.Unavailable) }
            .map { week to it }
    }

    /** The household cards are extras: a failing source hides them instead of failing the dashboard. */
    private fun household(): Flow<HouseholdStatus> =
        householdStatus.observe().onStart { emit(HouseholdStatus()) }.catch { emit(HouseholdStatus()) }

    private fun dashboard(): Flow<Loaded> = combine(
        kidSummaries(),
        chores.observeSubmissions(SubmissionFilter.PendingForParent),
        pendingActions.observePending(),
        pendingActions.observeFailed(),
        pendingActions.observeSkipped(),
    ) { kidList, submissions, actions, failed, skipped ->
        val pending = PendingActionsUi(
            allowances = actions.count { it is PendingParentAction.PayAllowance },
            interest = actions.count { it is PendingParentAction.AccrueInterest },
        )
        val names = kidList.associate { it.kid.id to it.kid.firstName }
        val ready = ParentHomeUiState.Ready(
            pendingApprovals = submissions.size,
            pendingActions = pending,
            kids = kidList,
            attention = attentionItems(failed, skipped, names),
        )
        Loaded.Ready(ready) as Loaded
    }
        .onStart { emit(Loaded.Loading) }
        .catch { emit(Loaded.Failed) }

    private fun kidSummaries(): Flow<List<KidSummaryUi>> =
        combine(kids.observeKids(), allowances.observeSchedules(null)) { kidList, schedules -> kidList to schedules }
            .flatMapLatest { (kidList, schedules) ->
                if (kidList.isEmpty()) {
                    flowOf(emptyList())
                } else {
                    combine(kidList.map { kid -> kidSummary(kid, schedules.filter { it.kidId == kid.id }) }) {
                        it.toList()
                    }
                }
            }

    private fun kidSummary(kid: Kid, schedules: List<AllowanceSchedule>): Flow<KidSummaryUi> = combine(
        accounts.accountsFor(kid.id),
        ledger.observeBalances(kid.id),
        ledger.observeAnomalies(kid.id).map { it.size },
    ) { accountList, balances, anomalies ->
        val after = calendar.today().plusDays(-1)
        KidSummaryUi(
            kid = kid,
            money = accountList.mainFiat()?.let { balanceOf(it, balances) },
            minutes = accountList.mainMinutes()?.let { balanceOf(it, balances) }?.amount,
            nextAllowance = schedules.mapNotNull { AllowancePlanner.nextDueDate(it, after) }.minOrNull(),
            anomalies = anomalies,
        )
    }

    private fun attentionItems(
        failed: List<FailedParentAction>,
        skipped: List<PendingParentAction>,
        names: Map<KidId, String>,
    ): List<AttentionItemUi> = failed.map { it.action.toAttention(AttentionReason.FAILED, names) } +
        skipped.map { it.toAttention(AttentionReason.SKIPPED, names) }

    private fun PendingParentAction.toAttention(reason: AttentionReason, names: Map<KidId, String>) = AttentionItemUi(
        key = key,
        kidName = names[kidId].orEmpty(),
        kind = when (this) {
            is PendingParentAction.PayAllowance -> PaymentKind.ALLOWANCE
            is PendingParentAction.AccrueInterest -> PaymentKind.INTEREST
        },
        amount = MoneyUi(amount, currency),
        period = periodKey,
        reason = reason,
    )

    private fun balanceOf(account: Account, balances: List<Balance>): MoneyUi =
        MoneyUi(balances.firstOrNull { it.accountId == account.id }?.amount ?: Money.ZERO, account.currency)

    private fun List<Account>.mainFiat() = firstOrNull { it.kind == AccountKind.MAIN && it.currency is Currency.Fiat }

    private fun List<Account>.mainMinutes() = firstOrNull {
        it.kind == AccountKind.MAIN &&
            it.currency == Currency.Minutes
    }

    private fun signNow(requester: ParentAuthorizationRequester) {
        val total = (state.value as? ParentHomeUiState.Ready)?.pendingActions?.total ?: 0
        if (local.value.signing != null || total == 0) return
        local.update { it.copy(signing = SigningProgress(0, total), signResult = null) }
        viewModelScope.launch {
            var prompts = 0
            // Each prompt comes right before its payment is signed, so the prompts before it are the signed ones.
            val counting = ParentAuthorizationRequester { reason ->
                local.update { it.copy(signing = SigningProgress(prompts, total)) }
                prompts += 1
                requester.request(reason)
            }
            val outcome = try {
                signer.signAll(counting).toOutcome(total)
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
                // The parent cancelled a prompt (or it failed): what was signed stays signed, the rest stay queued.
                SignResult.Stopped(signed = (prompts - 1).coerceAtLeast(0), total = total)
            }
            local.update { it.copy(signing = null, signResult = outcome) }
        }
    }

    private fun dismiss(key: String) {
        viewModelScope.launch {
            try {
                pendingActions.dismiss(key)
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") _: Exception) {
                // The row stays listed; the parent can try again.
            }
        }
    }

    private fun DrainResult.toOutcome(total: Int): SignResult = when (this) {
        DrainResult.AlreadyRunning -> SignResult.AlreadyRunning

        is DrainResult.Drained -> {
            val paid = signed.size + unsigned.size
            when {
                keyInvalidated -> SignResult.KeyInvalidated(signed = paid + alreadyPaid, total = total)

                signatureRequired -> SignResult.SignatureRequired(signed = paid + alreadyPaid, total = total)

                else -> SignResult.Finished(
                    signed = paid,
                    alreadyPaid = alreadyPaid,
                    failed = failed.count { !it.permanent },
                    total = total,
                    unsigned = unsigned.size,
                    needsAttention = failed.count { it.permanent },
                    cancelled = cancelled.size,
                    replanned = replanned.size,
                )
            }
        }
    }

    private data class LocalState(val signing: SigningProgress? = null, val signResult: SignResult? = null)

    private sealed interface Loaded {
        data object Loading : Loaded

        data object Failed : Loaded

        data class Ready(val data: ParentHomeUiState.Ready) : Loaded
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
