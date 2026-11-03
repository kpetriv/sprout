package dev.kpetriv.sprout.feature.chores

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kpetriv.sprout.core.database.ChoreRepository
import dev.kpetriv.sprout.core.database.FiatCurrencyProvider
import dev.kpetriv.sprout.core.database.KidRepository
import dev.kpetriv.sprout.core.domainhousehold.Chore
import dev.kpetriv.sprout.core.domainhousehold.ChoreDraft
import dev.kpetriv.sprout.core.domainhousehold.ChoreReward
import dev.kpetriv.sprout.core.domainhousehold.ChoreValidation
import dev.kpetriv.sprout.core.domainhousehold.ChoreViolation
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.model.ChoreId
import dev.kpetriv.sprout.core.model.ChoreSchedule
import dev.kpetriv.sprout.core.model.ChoreState
import dev.kpetriv.sprout.core.model.DayOfWeek
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.MAX_DESCRIPTION_LENGTH
import dev.kpetriv.sprout.core.model.MAX_SCHEDULE_DAY_OF_MONTH
import dev.kpetriv.sprout.core.model.MAX_TITLE_LENGTH
import dev.kpetriv.sprout.core.time.Clock
import dev.kpetriv.sprout.core.time.HouseholdCalendar
import dev.kpetriv.sprout.core.ui.MinorUnitsParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/** How often a chore repeats, as picked in the editor. */
enum class ScheduleKind { ONCE, DAILY, WEEKLY, MONTHLY }

/**
 * The editor's schedule fields. Every kind keeps its own values, so switching kinds and back loses nothing.
 * [dailyDays] may be empty while editing; saving then fails with [EditorError.NO_DAYS].
 */
data class ScheduleForm(
    val kind: ScheduleKind = ScheduleKind.DAILY,
    val onceDate: CalendarDate? = null,
    val dailyDays: Set<DayOfWeek> = DayOfWeek.entries.toSet(),
    val weeklyDay: DayOfWeek = DayOfWeek.SATURDAY,
    val monthlyDay: Int = 1,
) {
    /** The schedule, or null when it cannot be built yet (a daily schedule with no day). */
    fun toSchedule(): ChoreSchedule? = when (kind) {
        ScheduleKind.ONCE -> ChoreSchedule.Once(onceDate)
        ScheduleKind.DAILY -> dailyDays.takeIf { it.isNotEmpty() }?.let { ChoreSchedule.Daily(it) }
        ScheduleKind.WEEKLY -> ChoreSchedule.Weekly(weeklyDay)
        ScheduleKind.MONTHLY -> ChoreSchedule.Monthly(monthlyDay)
    }

    companion object {
        fun of(schedule: ChoreSchedule): ScheduleForm = when (schedule) {
            is ChoreSchedule.Once -> ScheduleForm(kind = ScheduleKind.ONCE, onceDate = schedule.dueOn)
            is ChoreSchedule.Daily -> ScheduleForm(kind = ScheduleKind.DAILY, dailyDays = schedule.daysOfWeek)
            is ChoreSchedule.Weekly -> ScheduleForm(kind = ScheduleKind.WEEKLY, weeklyDay = schedule.dayOfWeek)
            is ChoreSchedule.Monthly -> ScheduleForm(kind = ScheduleKind.MONTHLY, monthlyDay = schedule.dayOfMonth)
        }
    }
}

/** Something wrong with the form; [ChoreViolation]s from `ChoreValidation` plus the editor's own input checks. */
enum class EditorError {
    NO_KID,
    TITLE_BLANK,
    TITLE_TOO_LONG,
    DESCRIPTION_TOO_LONG,
    MONEY_INVALID,
    MINUTES_INVALID,
    NO_REWARD,
    REWARD_NOT_POSITIVE,
    NO_DAYS,
    DUE_DATE_IN_PAST,
}

data class KidOption(val id: KidId, val name: String)

/** The chore editor. [errors] are shown once the parent has tried to save, and then follow every change. */
data class ChoreEditorUiState(
    val isLoading: Boolean = true,
    val isNew: Boolean = true,
    val notFound: Boolean = false,
    val kids: List<KidOption> = emptyList(),
    val kidId: KidId? = null,
    val title: String = "",
    val description: String = "",
    val moneyText: String = "",
    val minutesText: String = "",
    val schedule: ScheduleForm = ScheduleForm(),
    val currency: Currency.Fiat = Currency.Fiat("EUR"),
    val today: CalendarDate = CalendarDate.MIN,
    val errors: Set<EditorError> = emptySet(),
    val isSaving: Boolean = false,
    val saveFailed: Boolean = false,
    val saved: Boolean = false,
)

sealed interface ChoreEditorEvent {
    data class KidChanged(val kidId: KidId) : ChoreEditorEvent

    data class TitleChanged(val text: String) : ChoreEditorEvent

    data class DescriptionChanged(val text: String) : ChoreEditorEvent

    data class MoneyChanged(val text: String) : ChoreEditorEvent

    data class MinutesChanged(val text: String) : ChoreEditorEvent

    data class ScheduleKindChanged(val kind: ScheduleKind) : ChoreEditorEvent

    data class OnceDateChanged(val date: CalendarDate?) : ChoreEditorEvent

    data class DailyDayToggled(val day: DayOfWeek) : ChoreEditorEvent

    data class WeeklyDayChanged(val day: DayOfWeek) : ChoreEditorEvent

    data class MonthlyDayChanged(val day: Int) : ChoreEditorEvent

    data object Save : ChoreEditorEvent

    data object SaveFailedShown : ChoreEditorEvent
}

/**
 * Creates or edits a chore. Arguments from the [SavedStateHandle]: [CHORE_ID_KEY] (edit that chore; absent for a
 * new one) and [KID_ID_KEY] (the kid a new chore starts assigned to). Amounts are typed text parsed with
 * [MinorUnitsParser] (money in the household currency's decimals, minutes whole); the form is checked with
 * [ChoreValidation] against today. An existing chore keeps its id, kid, creation time and state.
 */
@HiltViewModel
class ChoreEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val chores: ChoreRepository,
    private val kids: KidRepository,
    private val fiat: FiatCurrencyProvider,
    private val clock: Clock,
    private val calendar: HouseholdCalendar,
) : ViewModel() {
    private val choreId = savedStateHandle.get<String>(CHORE_ID_KEY)?.let(::ChoreId)
    private val initialKid = savedStateHandle.get<String>(KID_ID_KEY)?.let(::KidId)
    private val mutableState = MutableStateFlow(ChoreEditorUiState(isNew = choreId == null))
    private var existing: Chore? = null
    private var attempted = false

    val state: StateFlow<ChoreEditorUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    fun onEvent(event: ChoreEditorEvent) {
        when (event) {
            ChoreEditorEvent.Save -> save()

            ChoreEditorEvent.SaveFailedShown -> mutableState.update { it.copy(saveFailed = false) }

            is ChoreEditorEvent.ScheduleKindChanged,
            is ChoreEditorEvent.OnceDateChanged,
            is ChoreEditorEvent.DailyDayToggled,
            is ChoreEditorEvent.WeeklyDayChanged,
            is ChoreEditorEvent.MonthlyDayChanged,
            -> edit { it.copy(schedule = scheduleEdit(it.schedule, event)) }

            else -> edit { fieldEdit(it, event) }
        }
    }

    private fun fieldEdit(state: ChoreEditorUiState, event: ChoreEditorEvent): ChoreEditorUiState = when (event) {
        is ChoreEditorEvent.KidChanged -> if (state.isNew) state.copy(kidId = event.kidId) else state
        is ChoreEditorEvent.TitleChanged -> state.copy(title = event.text.take(MAX_TITLE_LENGTH + 1))
        is ChoreEditorEvent.DescriptionChanged -> state.copy(description = event.text.take(MAX_DESCRIPTION_LENGTH + 1))
        is ChoreEditorEvent.MoneyChanged -> state.copy(moneyText = event.text.take(MAX_AMOUNT_CHARS))
        is ChoreEditorEvent.MinutesChanged -> state.copy(minutesText = event.text.take(MAX_AMOUNT_CHARS))
        else -> state
    }

    private fun scheduleEdit(form: ScheduleForm, event: ChoreEditorEvent): ScheduleForm = when (event) {
        is ChoreEditorEvent.ScheduleKindChanged -> form.copy(kind = event.kind)

        is ChoreEditorEvent.OnceDateChanged -> form.copy(onceDate = event.date)

        is ChoreEditorEvent.DailyDayToggled -> form.copy(
            dailyDays = if (event.day in form.dailyDays) form.dailyDays - event.day else form.dailyDays + event.day,
        )

        is ChoreEditorEvent.WeeklyDayChanged -> form.copy(weeklyDay = event.day)

        is ChoreEditorEvent.MonthlyDayChanged -> form.copy(
            monthlyDay = event.day.coerceIn(1, MAX_SCHEDULE_DAY_OF_MONTH),
        )

        else -> form
    }

    private suspend fun load() {
        val kidList = attempt { kids.observeKids().first() }.getOrDefault(emptyList())
        val options = kidList.map { KidOption(it.id, it.firstName) }
        val currency = fiat.fiatCurrency()
        val today = calendar.today()
        val chore = choreId?.let { id -> attempt { chores.get(id) }.getOrNull() }
        existing = chore
        mutableState.update { current ->
            val base = current.copy(isLoading = false, kids = options, currency = currency, today = today)
            when {
                choreId != null && chore == null -> base.copy(notFound = true)

                chore != null -> base.copy(
                    kidId = chore.kidId,
                    title = chore.title,
                    description = chore.description.orEmpty(),
                    moneyText = chore.reward.money?.let { amountText(it, currency.fractionDigits) }.orEmpty(),
                    minutesText = chore.reward.minutes?.let { amountText(it, 0) }.orEmpty(),
                    schedule = ScheduleForm.of(chore.schedule),
                )

                else -> {
                    val preselected = initialKid?.takeIf { id -> options.any { it.id == id } }
                    base.copy(kidId = preselected ?: options.singleOrNull()?.id)
                }
            }
        }
    }

    private fun edit(transform: (ChoreEditorUiState) -> ChoreEditorUiState) = mutableState.update { current ->
        val next = transform(current)
        if (attempted) next.copy(errors = errorsOf(next)) else next
    }

    private fun save() {
        val current = mutableState.value
        val busy = current.isLoading || current.isSaving
        if (busy || current.saved || current.notFound) return
        attempted = true
        val errors = errorsOf(current)
        mutableState.update { it.copy(errors = errors) }
        if (errors.isNotEmpty()) return
        mutableState.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            val chore = buildChore(current)
            val ok = chore != null && attempt { chores.upsert(chore) }.isSuccess
            mutableState.update { it.copy(isSaving = false, saved = ok, saveFailed = !ok) }
        }
    }

    @Suppress("ReturnCount") // Guard clauses: each early return is a precondition that is not met.
    private suspend fun buildChore(form: ChoreEditorUiState): Chore? {
        val kidId = form.kidId ?: return null
        val draft = draftOf(form) ?: return null
        val previous = existing
        val householdId = previous?.householdId ?: attempt { kids.getKid(kidId) }.getOrNull()?.householdId
        if (householdId == null) return null
        return Chore(
            id = previous?.id ?: ChoreId("chore-${UUID.randomUUID()}"),
            householdId = householdId,
            kidId = previous?.kidId ?: kidId,
            title = draft.title,
            description = draft.description,
            reward = ChoreReward(draft.money, draft.minutes),
            schedule = draft.schedule,
            createdAt = previous?.createdAt ?: clock.now(),
            state = previous?.state ?: ChoreState.ACTIVE,
        )
    }

    companion object {
        /** [SavedStateHandle] key of the edited chore's id; absent for a new chore. */
        const val CHORE_ID_KEY: String = "choreId"

        /** [SavedStateHandle] key of the kid a new chore is assigned to first. */
        const val KID_ID_KEY: String = "kidId"
    }
}

private const val MAX_AMOUNT_CHARS = 12

/** Typed amount → minor units: null when blank, [Invalid] when it does not parse. */
private sealed interface Parsed {
    data class Ok(val money: Money?) : Parsed

    data object Invalid : Parsed
}

private fun parseAmount(text: String, fractionDigits: Int): Parsed =
    when (val result = MinorUnitsParser.parse(text, fractionDigits)) {
        MinorUnitsParser.Result.Empty -> Parsed.Ok(null)
        is MinorUnitsParser.Result.Valid -> Parsed.Ok(Money(result.minorUnits))
        is MinorUnitsParser.Result.Invalid -> Parsed.Invalid
    }

private fun amountText(money: Money, fractionDigits: Int): String =
    MinorUnitsParser.toInputText(money.minorUnits.coerceAtLeast(0), fractionDigits)

/** The draft the form describes, or null when an amount or the schedule cannot be built. */
@Suppress("ReturnCount") // Guard clauses: each early return is a precondition that is not met.
internal fun draftOf(form: ChoreEditorUiState): ChoreDraft? {
    val money = parseAmount(form.moneyText, form.currency.fractionDigits) as? Parsed.Ok ?: return null
    val minutes = parseAmount(form.minutesText, 0) as? Parsed.Ok ?: return null
    val schedule = form.schedule.toSchedule() ?: return null
    return ChoreDraft(
        title = form.title.trim(),
        description = form.description.trim().ifEmpty { null },
        money = money.money,
        minutes = minutes.money,
        schedule = schedule,
    )
}

/** Every problem with [form], in [EditorError] order. */
internal fun errorsOf(form: ChoreEditorUiState): Set<EditorError> {
    val money = parseAmount(form.moneyText, form.currency.fractionDigits)
    val minutes = parseAmount(form.minutesText, 0)
    val errors = sortedSetOf<EditorError>()
    if (form.kidId == null) errors += EditorError.NO_KID
    if (money == Parsed.Invalid) errors += EditorError.MONEY_INVALID
    if (minutes == Parsed.Invalid) errors += EditorError.MINUTES_INVALID
    if (form.schedule.toSchedule() == null) errors += EditorError.NO_DAYS
    // Validate what can be built; an unparseable amount or empty day set stands in as "nothing" / "any time".
    val draft = ChoreDraft(
        title = form.title.trim(),
        description = form.description.trim().ifEmpty { null },
        money = (money as? Parsed.Ok)?.money,
        minutes = (minutes as? Parsed.Ok)?.money,
        schedule = form.schedule.toSchedule() ?: ChoreSchedule.Once(null),
    )
    ChoreValidation.validate(draft, form.today).forEach { violation ->
        val error = violation.toEditorError()
        val unparsed = error == EditorError.NO_REWARD && (money == Parsed.Invalid || minutes == Parsed.Invalid)
        if (!unparsed) errors += error
    }
    return errors
}

private fun ChoreViolation.toEditorError(): EditorError = when (this) {
    ChoreViolation.TITLE_BLANK -> EditorError.TITLE_BLANK
    ChoreViolation.TITLE_TOO_LONG -> EditorError.TITLE_TOO_LONG
    ChoreViolation.DESCRIPTION_TOO_LONG -> EditorError.DESCRIPTION_TOO_LONG
    ChoreViolation.NO_REWARD -> EditorError.NO_REWARD
    ChoreViolation.REWARD_NOT_POSITIVE -> EditorError.REWARD_NOT_POSITIVE
    ChoreViolation.DUE_DATE_IN_PAST -> EditorError.DUE_DATE_IN_PAST
}
