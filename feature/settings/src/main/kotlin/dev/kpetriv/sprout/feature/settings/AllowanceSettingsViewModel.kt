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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
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
    /** The household's daily game limit (D-M5-4); null until the policy is read. */
    val gameCap: GameCapUi? = null,
    /** The game limit dialog; null while closed. */
    val gameCapEditor: GameCapEditorUi? = null,
    val message: SettingsMessage? = null,
)

/** The daily game limit in [currency]'s minor units; [isDefault] when the policy leaves it unset. */
data class GameCapUi(val capMinor: Long, val isDefault: Boolean, val currency: Currency.Fiat)

/** The game limit dialog: [text] parsed with [MinorUnitsParser] in [currency]'s decimals, 0 allowed. */
data class GameCapEditorUi(
    val text: String,
    val currency: Currency.Fiat,
    val error: AmountError? = null,
    val isSaving: Boolean = false,
    val saveFailed: Boolean = false,
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

    data object EditGameCap : AllowanceSettingsEvent

    data class GameCapChanged(val text: String) : AllowanceSettingsEvent

    data object SaveGameCap : AllowanceSettingsEvent

    /** Back to the default limit (the policy field unset). */
    data object ResetGameCap : AllowanceSettingsEvent

    data object DismissGameCap : AllowanceSettingsEvent
}

/**
 * Allowance per kid: amount, weekly or monthly cadence, start date and active toggle, written through
 * [AllowanceRepository.upsert] (outbox + audit). The next due date is [AllowancePlanner.nextDueDate] on or after
 * today. One schedule per kid is edited here; the first (oldest) is shown when a kid has several.
 *
 * The section also holds the household's daily game limit (D-M5-4, M7 §10): the most brain games can earn a kid per
 * day, in the household currency, written to the synced policy through [HouseholdRules] after [ParentConfirmation]
 * (0 to [HouseholdRulesState.MAX_DAILY_GAME_CAP_MINOR]; unset is the default of 50 minor units). Lowering it also
 * re-decides earlier days (D-M7-11), which the dialog says.
 */
@HiltViewModel
@Suppress("TooManyFunctions", "LongParameterList") // One handler per editor action; the injected sources.
class AllowanceSettingsViewModel @Inject constructor(
    private val kids: KidRepository,
    private val accounts: AccountRepository,
    private val allowances: AllowanceRepository,
    private val household: HouseholdContext,
    private val calendar: HouseholdCalendar,
    private val rules: HouseholdRules,
    private val confirmation: ParentConfirmation,
) : ViewModel() {
    private val local = MutableStateFlow(AllowanceSettingsUiState())

    val state: StateFlow<AllowanceSettingsUiState> = combine(rows(), gameCap(), local) { rows, cap, localState ->
        localState.copy(
            isLoading = rows is Load.Loading,
            loadFailed = rows is Load.Failed,
            rows = (rows as? Load.Ready)?.value.orEmpty(),
            gameCap = cap,
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

            else -> onGameCapEvent(event)
        }
    }

    private fun onGameCapEvent(event: AllowanceSettingsEvent) {
        when (event) {
            AllowanceSettingsEvent.EditGameCap -> editGameCap()

            is AllowanceSettingsEvent.GameCapChanged -> local.update { current ->
                current.copy(gameCapEditor = current.gameCapEditor?.copy(text = event.text, error = null))
            }

            AllowanceSettingsEvent.SaveGameCap -> saveGameCap(reset = false)

            AllowanceSettingsEvent.ResetGameCap -> saveGameCap(reset = true)

            AllowanceSettingsEvent.DismissGameCap -> local.update {
                if (it.gameCapEditor?.isSaving == true) it else it.copy(gameCapEditor = null)
            }

            else -> Unit
        }
    }

    private fun gameCap(): Flow<GameCapUi?> =
        combine(rules.observe(), flow { emit(household.fiatCurrency()) }) { policy, currency ->
            GameCapUi(policy.effectiveDailyGameCapMinor, policy.dailyGameCapMinor == null, currency)
        }
            .map<GameCapUi, GameCapUi?> { it }
            .onStart { emit(null) }
            .catch { emit(null) }

    private fun editGameCap() {
        val cap = state.value.gameCap ?: return
        val text = MinorUnitsParser.toInputText(cap.capMinor, cap.currency.fractionDigits)
        local.update { it.copy(gameCapEditor = GameCapEditorUi(text, cap.currency)) }
    }

    private fun saveGameCap(reset: Boolean) {
        val editor = local.value.gameCapEditor?.takeUnless { it.isSaving } ?: return
        val value: Long? = if (reset) {
            null
        } else {
            when (val parsed = parseGameCap(editor.text, editor.currency)) {
                is GameCapParse.Valid -> parsed.minorUnits

                is GameCapParse.Invalid -> {
                    local.update { it.copy(gameCapEditor = editor.copy(error = parsed.error)) }
                    return
                }
            }
        }
        local.update { it.copy(gameCapEditor = editor.copy(isSaving = true, saveFailed = false, error = null)) }
        viewModelScope.launch {
            when (confirm()) {
                ConfirmationOutcome.CONFIRMED -> {
                    val written = policyWrite { rules.setDailyGameCap(value) }
                    local.update { current ->
                        when (written) {
                            PolicyWrite.SAVED -> current.copy(gameCapEditor = null, message = SettingsMessage.SAVED)

                            PolicyWrite.NO_POLICY_YET ->
                                current.copy(gameCapEditor = null, message = SettingsMessage.POLICY_NOT_SYNCED)

                            PolicyWrite.FAILED -> current.copy(
                                gameCapEditor = current.gameCapEditor?.copy(isSaving = false, saveFailed = true),
                            )
                        }
                    }
                }

                ConfirmationOutcome.CANCELLED -> local.update { current ->
                    current.copy(gameCapEditor = current.gameCapEditor?.copy(isSaving = false))
                }

                ConfirmationOutcome.FAILED -> local.update { current ->
                    current.copy(
                        gameCapEditor = current.gameCapEditor?.copy(isSaving = false),
                        message = SettingsMessage.NOT_CONFIRMED,
                    )
                }
            }
        }
    }

    /** [ParentConfirmation] for a game limit change; a throwing adapter counts as failed. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun confirm(): ConfirmationOutcome = try {
        confirmation.confirm(ConfirmationPurpose.CHANGE_GAME_CAP)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        ConfirmationOutcome.FAILED
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

/** A typed game limit: 0 to [HouseholdRulesState.MAX_DAILY_GAME_CAP_MINOR] minor units. Never floating point. */
internal sealed interface GameCapParse {
    data class Valid(val minorUnits: Long) : GameCapParse

    data class Invalid(val error: AmountError) : GameCapParse
}

internal fun parseGameCap(text: String, currency: Currency.Fiat): GameCapParse {
    val max = HouseholdRulesState.MAX_DAILY_GAME_CAP_MINOR
    return when (val parsed = MinorUnitsParser.parse(text, currency.fractionDigits, max)) {
        MinorUnitsParser.Result.Empty -> GameCapParse.Invalid(AmountError.REQUIRED)
        is MinorUnitsParser.Result.Invalid -> GameCapParse.Invalid(parsed.error.toAmountError())
        is MinorUnitsParser.Result.Valid -> GameCapParse.Valid(parsed.minorUnits)
    }
}

internal fun AllowanceEditorUi.cadenceValue(): AllowanceCadence = when (cadence) {
    CadenceKind.WEEKLY -> AllowanceCadence.Weekly(dayOfWeek)
    CadenceKind.MONTHLY -> AllowanceCadence.Monthly(dayOfMonth)
}
