package dev.kpetriv.sprout.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kpetriv.sprout.core.database.AccountRepository
import dev.kpetriv.sprout.core.database.AllowanceRepository
import dev.kpetriv.sprout.core.database.KidRepository
import dev.kpetriv.sprout.core.domainhousehold.AllowancePlanner
import dev.kpetriv.sprout.core.domainhousehold.AllowanceSchedule
import dev.kpetriv.sprout.core.domainledger.Account
import dev.kpetriv.sprout.core.domainledger.AccountKind
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.LedgerPolicy
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.model.AccountId
import dev.kpetriv.sprout.core.model.AllowanceCadence
import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.model.DayOfWeek
import dev.kpetriv.sprout.core.model.Kid
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.MAX_SCHEDULE_DAY_OF_MONTH
import dev.kpetriv.sprout.core.model.ScheduleId
import dev.kpetriv.sprout.core.time.HouseholdCalendar
import dev.kpetriv.sprout.core.ui.MinorUnitsParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/** The allowance section: one row per kid, and the edit sheet. */
data class AllowanceSettingsUiState(
    val isLoading: Boolean = true,
    val loadFailed: Boolean = false,
    val rows: List<AllowanceRowUi> = emptyList(),
    val editor: AllowanceEditorUi? = null,
    val message: SettingsMessage? = null,
)

/** A kid's allowance. [schedule] is null when the kid has none; [nextDue] is null when inactive. */
data class AllowanceRowUi(val kid: Kid, val schedule: AllowanceSchedule?, val nextDue: CalendarDate?)

/** How often the allowance in the editor is paid. */
enum class CadenceKind {
    WEEKLY,
    MONTHLY,
}

/**
 * The allowance sheet for one kid. [amountText] is parsed with [MinorUnitsParser] in [currency]'s decimals;
 * [nextDue] previews the next payment for the form as it stands (null while the amount is invalid or the
 * allowance is paused).
 */
data class AllowanceEditorUi(
    val kidId: KidId,
    val kidName: String,
    val scheduleId: ScheduleId?,
    val currency: Currency.Fiat,
    val amountText: String,
    val cadence: CadenceKind,
    val dayOfWeek: DayOfWeek,
    val dayOfMonth: Int,
    val startsOn: CalendarDate,
    val active: Boolean,
    val nextDue: CalendarDate?,
    val amountError: AmountError? = null,
    val isSaving: Boolean = false,
    val saveFailed: Boolean = false,
)

sealed interface AllowanceSettingsEvent {
    data class Edit(val kidId: KidId) : AllowanceSettingsEvent

    data class AmountChanged(val text: String) : AllowanceSettingsEvent

    data class CadenceChanged(val cadence: CadenceKind) : AllowanceSettingsEvent

    data class DayOfWeekChanged(val day: DayOfWeek) : AllowanceSettingsEvent

    /** 1 to [MAX_SCHEDULE_DAY_OF_MONTH]; anything else is clamped. */
    data class DayOfMonthChanged(val day: Int) : AllowanceSettingsEvent

    data class StartsOnChanged(val date: CalendarDate) : AllowanceSettingsEvent

    data class ActiveChanged(val active: Boolean) : AllowanceSettingsEvent

    data object Save : AllowanceSettingsEvent

    data object Dismiss : AllowanceSettingsEvent

    data object MessageShown : AllowanceSettingsEvent
}

/**
 * Allowance per kid: amount, weekly or monthly cadence, start date and active toggle, written through
 * [AllowanceRepository.upsert] (outbox + audit). The next due date is [AllowancePlanner.nextDueDate] on or after
 * today. One schedule per kid is edited here; the first (oldest) is shown when a kid has several.
 */
@HiltViewModel
class AllowanceSettingsViewModel @Inject constructor(
    private val kids: KidRepository,
    private val accounts: AccountRepository,
    private val allowances: AllowanceRepository,
    private val household: HouseholdContext,
    private val calendar: HouseholdCalendar,
) : ViewModel() {
    private val local = MutableStateFlow(AllowanceSettingsUiState())

    val state: StateFlow<AllowanceSettingsUiState> = combine(rows(), local) { rows, localState ->
        localState.copy(
            isLoading = rows is Load.Loading,
            loadFailed = rows is Load.Failed,
            rows = (rows as? Load.Ready)?.value.orEmpty(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), AllowanceSettingsUiState())

    fun onEvent(event: AllowanceSettingsEvent) {
        when (event) {
            is AllowanceSettingsEvent.Edit -> openEditor(event.kidId)

            is AllowanceSettingsEvent.AmountChanged ->
                updateEditor { it.copy(amountText = event.text, amountError = null) }

            is AllowanceSettingsEvent.CadenceChanged -> updateEditor { it.copy(cadence = event.cadence) }

            is AllowanceSettingsEvent.DayOfWeekChanged -> updateEditor { it.copy(dayOfWeek = event.day) }

            is AllowanceSettingsEvent.DayOfMonthChanged ->
                updateEditor { it.copy(dayOfMonth = event.day.coerceIn(1, MAX_SCHEDULE_DAY_OF_MONTH)) }

            is AllowanceSettingsEvent.StartsOnChanged -> updateEditor { it.copy(startsOn = event.date) }

            is AllowanceSettingsEvent.ActiveChanged -> updateEditor { it.copy(active = event.active) }

            AllowanceSettingsEvent.Save -> save()

            AllowanceSettingsEvent.Dismiss -> local.update { it.copy(editor = null) }

            AllowanceSettingsEvent.MessageShown -> local.update { it.copy(message = null) }
        }
    }

    private fun rows() = combine(kids.observeKids(), allowances.observeSchedules(null)) { kidList, schedules ->
        val fromDate = calendar.today().plusDays(-1)
        kidList.map { kid ->
            val schedule = schedules.firstOrNull { it.kidId == kid.id }
            AllowanceRowUi(kid, schedule, schedule?.let { AllowancePlanner.nextDueDate(it, fromDate) })
        }
    }
        .map<List<AllowanceRowUi>, Load<List<AllowanceRowUi>>> { Load.Ready(it) }
        .onStart { emit(Load.Loading) }
        .catch { emit(Load.Failed) }

    private fun openEditor(kidId: KidId) {
        viewModelScope.launch {
            val row = state.value.rows.firstOrNull { it.kid.id == kidId } ?: return@launch
            val schedule = row.schedule
            val currency = (schedule?.currency as? Currency.Fiat) ?: mainAccount(kidId)?.currency as? Currency.Fiat
                ?: household.fiatCurrency()
            val today = calendar.today()
            val cadence = schedule?.cadence
            val editor = AllowanceEditorUi(
                kidId = kidId,
                kidName = row.kid.firstName,
                scheduleId = schedule?.id,
                currency = currency,
                amountText = schedule?.let {
                    MinorUnitsParser.toInputText(it.amount.minorUnits, currency.fractionDigits)
                }
                    .orEmpty(),
                cadence = if (cadence is AllowanceCadence.Monthly) CadenceKind.MONTHLY else CadenceKind.WEEKLY,
                dayOfWeek = (cadence as? AllowanceCadence.Weekly)?.dayOfWeek ?: DayOfWeek.SATURDAY,
                dayOfMonth = (cadence as? AllowanceCadence.Monthly)?.dayOfMonth ?: 1,
                startsOn = schedule?.startsOn ?: today,
                active = schedule?.active ?: true,
                nextDue = null,
            )
            local.update { it.copy(editor = editor.withPreview()) }
        }
    }

    private fun save() {
        val editor = local.value.editor?.takeUnless { it.isSaving } ?: return
        val (amount, error) = parsePositiveAmount(editor.amountText, editor.currency, MAX_AMOUNT)
        if (amount == null) {
            local.update { current -> current.copy(editor = current.editor?.copy(amountError = error)) }
        } else {
            persist(editor, amount)
        }
    }

    private fun persist(editor: AllowanceEditorUi, amount: Long) {
        updateEditor { it.copy(isSaving = true, saveFailed = false) }
        viewModelScope.launch {
            val saved = attempt {
                allowances.upsert(editor.toSchedule(amount, accountFor(editor).id))
            }
            local.update { current ->
                if (saved) {
                    current.copy(editor = null, message = SettingsMessage.SAVED)
                } else {
                    current.copy(editor = current.editor?.copy(isSaving = false, saveFailed = true))
                }
            }
        }
    }

    /** The schedule's account, or the kid's MAIN account in the editor's currency (created if missing). */
    private suspend fun accountFor(editor: AllowanceEditorUi): Account {
        val existing = editor.scheduleId?.let { allowances.get(it) }
        val accountList = accounts.accountsFor(editor.kidId).first()
        existing?.let { schedule -> accountList.firstOrNull { it.id == schedule.accountId } }?.let { return it }
        return accountList.mainIn(editor.currency)
            ?: accounts.ensureDefaultAccounts(editor.kidId, editor.currency).mainIn(editor.currency)
            ?: error("no main account in ${editor.currency.isoCode}")
    }

    private suspend fun mainAccount(kidId: KidId): Account? = accounts.accountsFor(kidId).first()
        .firstOrNull { it.kind == AccountKind.MAIN && it.currency is Currency.Fiat }

    private fun List<Account>.mainIn(currency: Currency) =
        firstOrNull { it.kind == AccountKind.MAIN && it.currency == currency }

    private fun AllowanceEditorUi.toSchedule(amount: Long, accountId: AccountId) = AllowanceSchedule(
        id = scheduleId ?: ScheduleId(UUID.randomUUID().toString()),
        kidId = kidId,
        accountId = accountId,
        amount = Money(amount),
        currency = currency,
        cadence = cadenceValue(),
        startsOn = startsOn,
        active = active,
    )

    /** Recomputes [AllowanceEditorUi.nextDue] for the form as it now stands. */
    private fun AllowanceEditorUi.withPreview(): AllowanceEditorUi {
        val (amount, _) = parsePositiveAmount(amountText, currency, MAX_AMOUNT)
        val nextDue = if (amount != null && active) {
            val preview = AllowanceSchedule(
                id = PREVIEW_ID,
                kidId = kidId,
                accountId = PREVIEW_ACCOUNT,
                amount = Money(amount),
                currency = currency,
                cadence = cadenceValue(),
                startsOn = startsOn,
                active = true,
            )
            AllowancePlanner.nextDueDate(preview, calendar.today().plusDays(-1))
        } else {
            null
        }
        return copy(nextDue = nextDue)
    }

    private fun updateEditor(transform: (AllowanceEditorUi) -> AllowanceEditorUi) = local.update { current ->
        current.editor?.let { current.copy(editor = transform(it).withPreview()) } ?: current
    }

    internal companion object {
        /** The ledger's per-event cap; an allowance above it could never be paid. */
        val MAX_AMOUNT: Long = LedgerPolicy.DEFAULT_MAX_AMOUNT.minorUnits

        private val PREVIEW_ID = ScheduleId("preview")
        private val PREVIEW_ACCOUNT = AccountId("preview")
    }
}

internal fun AllowanceEditorUi.cadenceValue(): AllowanceCadence = when (cadence) {
    CadenceKind.WEEKLY -> AllowanceCadence.Weekly(dayOfWeek)
    CadenceKind.MONTHLY -> AllowanceCadence.Monthly(dayOfMonth)
}
