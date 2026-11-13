package dev.kpetriv.sprout.feature.wallet

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kpetriv.sprout.core.database.FiatCurrencyProvider
import dev.kpetriv.sprout.core.database.KidRepository
import dev.kpetriv.sprout.core.database.RewardRepository
import dev.kpetriv.sprout.core.database.SessionAuthorProvider
import dev.kpetriv.sprout.core.domainhousehold.Reward
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.LedgerPolicy
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.MAX_TITLE_LENGTH
import dev.kpetriv.sprout.core.model.RewardId
import dev.kpetriv.sprout.core.model.RewardKind
import dev.kpetriv.sprout.core.ui.AmountInputError
import dev.kpetriv.sprout.core.ui.MinorUnitsParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/** Something wrong with the reward form, shown next to its field. */
enum class RewardEditorError {
    TITLE_BLANK,
    TITLE_TOO_LONG,
    PRICE_MISSING,
    PRICE_NOT_A_NUMBER,
    PRICE_TOO_MANY_DECIMALS,
    PRICE_NOT_POSITIVE,
    PRICE_TOO_HIGH,
    MINUTES_MISSING,
    MINUTES_NOT_A_NUMBER,
    MINUTES_NOT_POSITIVE,

    /** More than [RewardEditorViewModel.MAX_SCREEN_TIME_MINUTES] (a day), however many digits. */
    MINUTES_OVER_A_DAY,
    NO_KIDS_CHOSEN,
}

/** A kid the reward can be offered to. */
data class RewardKidOption(val id: KidId, val name: String)

/**
 * The reward editor. [errors] are shown once the parent has tried to save, and then follow every change.
 *
 * @property priceText the price as typed, in [currency]: the household's for a new reward, the reward's own for an
 *   edit.
 * @property minutesText the screen time granted, as typed; only used (and required) for [RewardKind.SCREEN_TIME].
 * @property forAllKids every kid sees the reward; otherwise only [chosenKids].
 * @property chosenKids the kids picked under "Chosen kids"; may name a kid who has since left (dropped on save).
 * @property active "Show in the shop".
 */
data class RewardEditorUiState(
    val isLoading: Boolean = true,
    val isNew: Boolean = true,
    val notFound: Boolean = false,
    val kids: List<RewardKidOption> = emptyList(),
    val title: String = "",
    val kind: RewardKind = RewardKind.TREAT,
    val priceText: String = "",
    val minutesText: String = "",
    val forAllKids: Boolean = true,
    val chosenKids: Set<KidId> = emptySet(),
    val active: Boolean = true,
    val currency: Currency.Fiat = Currency.Fiat("EUR"),
    val errors: Set<RewardEditorError> = emptySet(),
    val isSaving: Boolean = false,
    val saveFailed: Boolean = false,
    val saved: Boolean = false,
) {
    /** The minutes field is shown (and required) only for screen time. */
    val showsMinutes: Boolean get() = kind == RewardKind.SCREEN_TIME
}

sealed interface RewardEditorEvent {
    data class TitleChanged(val text: String) : RewardEditorEvent

    /** Switching away from screen time clears the minutes; switching to it asks for them. */
    data class KindChanged(val kind: RewardKind) : RewardEditorEvent

    data class PriceChanged(val text: String) : RewardEditorEvent

    data class MinutesChanged(val text: String) : RewardEditorEvent

    /** "All kids" (true) or "Chosen kids" (false). */
    data class AllKidsChanged(val allKids: Boolean) : RewardEditorEvent

    /** Adds [kidId] to, or removes it from, the chosen kids. */
    data class KidToggled(val kidId: KidId) : RewardEditorEvent

    /** "Show in the shop". */
    data class ActiveChanged(val active: Boolean) : RewardEditorEvent

    data object Save : RewardEditorEvent

    data object SaveFailedShown : RewardEditorEvent
}

/**
 * Creates or edits a shop reward. Argument from the [SavedStateHandle]: [REWARD_ID_KEY] (edit that reward; absent for
 * a new one). The price is typed text parsed straight into minor units of the form's currency with [MinorUnitsParser]
 * (never through a floating-point number); minutes are whole.
 *
 * Validation ([rewardErrorsOf]) mirrors what the reward and a purchase of it must satisfy, so a parent cannot save a
 * reward no kid could ever buy: [Reward]'s own checks (title, positive cost, minutes exactly for screen time) and the
 * ledger's amount bound ([LedgerPolicy.maxAmount], via [MAX_PRICE_MINOR] and [MAX_MINUTES]) that the `RewardPurchased`
 * and its `TimeBankCredited` must stay within. Screen time is also capped at a day ([MAX_SCREEN_TIME_MINUTES]).
 *
 * A new reward is priced in the household's fiat currency ([FiatCurrencyProvider]) and follows it while the form is
 * open; an existing reward keeps its own currency, whatever the household's is now.
 *
 * Saving writes through [RewardRepository.upsert]: a new reward gets a fresh id in the signed-in household; an edit
 * keeps its id and household. Chosen kids who have left the household are dropped; "Chosen kids" with none left is an
 * error rather than silently becoming every kid. There is no delete: hiding ("Show in the shop" off) takes a reward out
 * of the shop, and rewards already bought stay in the kids' history.
 */
@Suppress("TooManyFunctions") // One small function per event, plus loading and following the kids and currency.
@HiltViewModel
class RewardEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val rewards: RewardRepository,
    private val kids: KidRepository,
    private val fiat: FiatCurrencyProvider,
    private val authors: SessionAuthorProvider,
) : ViewModel() {
    private val rewardId = savedStateHandle.get<String>(REWARD_ID_KEY)?.let(::RewardId)
    private val mutableState = MutableStateFlow(RewardEditorUiState(isNew = rewardId == null))
    private var existing: Reward? = null
    private var attempted = false

    val state: StateFlow<RewardEditorUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            load()
            launch { followKids() }
            // Only a new reward is priced in the household's currency; an edit keeps the reward's own.
            if (rewardId == null) followCurrency()
        }
    }

    fun onEvent(event: RewardEditorEvent) {
        when (event) {
            RewardEditorEvent.Save -> save()
            RewardEditorEvent.SaveFailedShown -> mutableState.update { it.copy(saveFailed = false) }
            else -> edit { fieldEdit(it, event) }
        }
    }

    private fun fieldEdit(state: RewardEditorUiState, event: RewardEditorEvent): RewardEditorUiState = when (event) {
        is RewardEditorEvent.TitleChanged -> state.copy(title = event.text.truncatedTo(MAX_TITLE_LENGTH + 1))

        is RewardEditorEvent.KindChanged -> state.copy(
            kind = event.kind,
            minutesText = if (event.kind == RewardKind.SCREEN_TIME) state.minutesText else "",
        )

        is RewardEditorEvent.PriceChanged -> state.copy(priceText = event.text.take(MAX_AMOUNT_CHARS))

        is RewardEditorEvent.MinutesChanged -> state.copy(minutesText = event.text.take(MAX_AMOUNT_CHARS))

        is RewardEditorEvent.AllKidsChanged -> state.copy(forAllKids = event.allKids)

        is RewardEditorEvent.KidToggled -> state.copy(
            chosenKids = if (event.kidId in state.chosenKids) {
                state.chosenKids - event.kidId
            } else {
                state.chosenKids + event.kidId
            },
        )

        is RewardEditorEvent.ActiveChanged -> state.copy(active = event.active)

        RewardEditorEvent.Save, RewardEditorEvent.SaveFailedShown -> state
    }

    private suspend fun load() {
        val kidList = attempt { kids.observeKids().first() }.getOrDefault(emptyList())
        val reward = rewardId?.let { id -> attempt { rewards.get(id) }.getOrNull() }
        existing = reward
        // An existing reward keeps its own currency (label, decimals, parsing and what is saved), so an edit never
        // reprices it in another currency; a new one takes the household's.
        val currency = (reward?.currency as? Currency.Fiat)
            ?: attempt { fiat.currentFiatCurrency() }.getOrElse { fiat.fiatCurrency() }
        mutableState.update { current ->
            val base = current.copy(
                isLoading = false,
                kids = kidList.map { RewardKidOption(it.id, it.firstName) },
                currency = currency,
            )
            when {
                rewardId != null && reward == null -> base.copy(notFound = true)

                reward != null -> base.copy(
                    title = reward.title,
                    kind = reward.kind,
                    priceText = amountText(reward.cost, currency.fractionDigits),
                    minutesText = reward.minutesGranted?.toString().orEmpty(),
                    forAllKids = reward.forKids.isEmpty(),
                    chosenKids = reward.forKids,
                    active = reward.active,
                )

                else -> base
            }
        }
    }

    /** The kid chips follow the household: a kid added or removed on another phone appears or goes. */
    private suspend fun followKids() {
        attempt {
            kids.observeKids().collect { list ->
                edit { it.copy(kids = list.map { kid -> RewardKidOption(kid.id, kid.firstName) }) }
            }
        }
    }

    /**
     * A new reward's form follows the household's currency after loading (as the chore editor does, D-REL-19): a pull
     * that brings the policy switches it, and the typed price is read in the new currency's units. Never called for an
     * edit, which keeps the reward's own currency.
     */
    private suspend fun followCurrency() {
        attempt {
            fiat.observeFiatCurrency().collect { currency ->
                edit { if (it.currency == currency) it else it.copy(currency = currency) }
            }
        }
    }

    private fun edit(transform: (RewardEditorUiState) -> RewardEditorUiState) = mutableState.update { current ->
        val next = transform(current)
        if (attempted) next.copy(errors = rewardErrorsOf(next)) else next
    }

    private fun save() {
        val current = mutableState.value
        val busy = current.isLoading || current.isSaving
        if (busy || current.saved || current.notFound) return
        attempted = true
        val errors = rewardErrorsOf(current)
        mutableState.update { it.copy(errors = errors) }
        if (errors.isNotEmpty()) return
        mutableState.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            val ok = attempt {
                val reward = checkNotNull(buildReward(current)) { "the form does not describe a reward" }
                rewards.upsert(reward)
            }.isSuccess
            mutableState.update { it.copy(isSaving = false, saved = ok, saveFailed = !ok) }
        }
    }

    private suspend fun buildReward(form: RewardEditorUiState): Reward? {
        val previous = existing
        val householdId: HouseholdId = previous?.householdId ?: authors.currentAuthor().householdId
        return rewardOf(
            form = form,
            id = previous?.id ?: RewardId(UUID.randomUUID().toString()),
            householdId = householdId,
        )
    }

    companion object {
        /** [SavedStateHandle] key of the edited reward's id; absent for a new reward. */
        const val REWARD_ID_KEY: String = "rewardId"

        /**
         * The highest price, in minor units: the ledger's bound on any single event's amount
         * ([LedgerPolicy.maxAmount], the default every device applies), which the kid's `RewardPurchased` must stay
         * within.
         */
        val MAX_PRICE_MINOR: Long = LedgerPolicy.DEFAULT_MAX_AMOUNT.minorUnits

        /**
         * The most minutes the ledger lets a screen-time reward grant: the same bound, which applies to the purchase's
         * granted minutes and the `TimeBankCredited` they become, capped by [Reward.minutesGranted]'s `Int`. The
         * editor's own limit, [MAX_SCREEN_TIME_MINUTES], is far inside it.
         */
        val MAX_MINUTES: Long = minOf(LedgerPolicy.DEFAULT_MAX_AMOUNT.minorUnits, Int.MAX_VALUE.toLong())

        /**
         * The most screen time one reward may give: a day (1,440 minutes). The editor's own sanity limit, inside the
         * ledger's hard bound [MAX_MINUTES]; nothing in the time bank caps a single credit lower. Anything above it,
         * however long the number typed, is [RewardEditorError.MINUTES_OVER_A_DAY].
         */
        const val MAX_SCREEN_TIME_MINUTES: Long = 1_440L
    }
}

/** Longest typed amount kept: enough for any amount up to the bounds, plus a little to show "too high". */
private const val MAX_AMOUNT_CHARS = 18

private fun amountText(money: Money, fractionDigits: Int): String =
    MinorUnitsParser.toInputText(money.minorUnits.coerceAtLeast(0), fractionDigits)

/** Every problem with [form], in [RewardEditorError] order. Kids are checked against [RewardEditorUiState.kids]. */
internal fun rewardErrorsOf(form: RewardEditorUiState): Set<RewardEditorError> {
    val errors = sortedSetOf<RewardEditorError>()
    val title = form.title.trim()
    if (title.isEmpty()) errors += RewardEditorError.TITLE_BLANK
    if (title.length > MAX_TITLE_LENGTH) errors += RewardEditorError.TITLE_TOO_LONG
    priceError(form)?.let { errors += it }
    if (form.showsMinutes) minutesError(form.minutesText)?.let { errors += it }
    if (!form.forAllKids && forKidsOf(form).isEmpty()) errors += RewardEditorError.NO_KIDS_CHOSEN
    return errors
}

private fun priceError(form: RewardEditorUiState): RewardEditorError? = when (
    val parsed = MinorUnitsParser.parse(
        form.priceText,
        form.currency.fractionDigits,
        RewardEditorViewModel.MAX_PRICE_MINOR,
    )
) {
    MinorUnitsParser.Result.Empty -> RewardEditorError.PRICE_MISSING

    is MinorUnitsParser.Result.Valid -> RewardEditorError.PRICE_NOT_POSITIVE.takeIf { parsed.minorUnits <= 0 }

    is MinorUnitsParser.Result.Invalid -> when (parsed.error) {
        AmountInputError.NOT_A_NUMBER -> RewardEditorError.PRICE_NOT_A_NUMBER
        AmountInputError.TOO_MANY_DECIMALS -> RewardEditorError.PRICE_TOO_MANY_DECIMALS
        AmountInputError.TOO_LARGE -> RewardEditorError.PRICE_TOO_HIGH
    }
}

private fun minutesError(text: String): RewardEditorError? =
    // Parsed against the day's cap, so any longer number (even beyond a Long) is "too large", never an overflow.
    when (val parsed = MinorUnitsParser.parse(text, 0, RewardEditorViewModel.MAX_SCREEN_TIME_MINUTES)) {
        MinorUnitsParser.Result.Empty -> RewardEditorError.MINUTES_MISSING

        is MinorUnitsParser.Result.Valid -> RewardEditorError.MINUTES_NOT_POSITIVE.takeIf { parsed.minorUnits <= 0 }

        is MinorUnitsParser.Result.Invalid -> when (parsed.error) {
            AmountInputError.NOT_A_NUMBER, AmountInputError.TOO_MANY_DECIMALS -> RewardEditorError.MINUTES_NOT_A_NUMBER
            AmountInputError.TOO_LARGE -> RewardEditorError.MINUTES_OVER_A_DAY
        }
    }

/** The kids a reward from [form] is for: empty for every kid; otherwise the chosen kids still in the household. */
private fun forKidsOf(form: RewardEditorUiState): Set<KidId> = if (form.forAllKids) {
    emptySet()
} else {
    val present = form.kids.mapTo(mutableSetOf()) { it.id }
    form.chosenKids.filterTo(mutableSetOf()) { it in present }
}

/** The reward [form] describes, or null while it has errors ([rewardErrorsOf]). */
internal fun rewardOf(form: RewardEditorUiState, id: RewardId, householdId: HouseholdId): Reward? {
    if (rewardErrorsOf(form).isNotEmpty()) return null
    val price = MinorUnitsParser.parse(form.priceText, form.currency.fractionDigits) as MinorUnitsParser.Result.Valid
    val minutes = if (form.showsMinutes) {
        (MinorUnitsParser.parse(form.minutesText, 0) as MinorUnitsParser.Result.Valid).minorUnits.toInt()
    } else {
        null
    }
    return Reward(
        id = id,
        householdId = householdId,
        title = form.title.trim(),
        kind = form.kind,
        cost = Money(price.minorUnits),
        currency = form.currency,
        minutesGranted = minutes,
        active = form.active,
        forKids = forKidsOf(form),
    )
}

/** Runs [block]; any failure other than cancellation becomes a failed [Result] (the caller shows a message). */
@Suppress("TooGenericExceptionCaught") // Any storage failure must surface as a message, not crash the editor.
private suspend fun <T> attempt(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}
