package dev.kpetriv.sprout.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kpetriv.sprout.core.database.InterestPolicyRepository
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.InterestCalculator
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.InterestPolicy
import dev.kpetriv.sprout.core.model.MAX_SCHEDULE_DAY_OF_MONTH
import dev.kpetriv.sprout.core.ui.AmountInputError
import dev.kpetriv.sprout.core.ui.MinorUnitsParser
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The interest section. The rate is typed as a percentage with up to two decimals ("2.5", "2.50") and stored in
 * basis points ([InterestRate]).
 *
 * @property saved the stored policy; null while loading.
 * @property preview what [PREVIEW_PRINCIPAL_MAJOR] in [currency] earns in [PREVIEW_DAYS] days at the typed rate;
 *   null while the rate is invalid.
 */
data class InterestSettingsUiState(
    val isLoading: Boolean = true,
    val loadFailed: Boolean = false,
    val currency: Currency.Fiat? = null,
    val saved: InterestPolicy? = null,
    val rateText: String = "",
    val accrualDay: Int = 1,
    val rateError: RateError? = null,
    val preview: InterestPreview? = null,
    val isSaving: Boolean = false,
    val message: SettingsMessage? = null,
) {
    /** The typed rate in basis points, if valid. */
    val rateBasisPoints: Int? get() = (InterestRate.parse(rateText) as? InterestRate.Parsed.Valid)?.basisPoints

    /** The form differs from the stored policy and is valid. */
    val canSave: Boolean
        get() = !isSaving && saved != null && rateError == null && rateBasisPoints.let { bps ->
            bps != null && (bps != saved.annualRateBasisPoints || accrualDay != saved.accrualDay)
        }

    companion object {
        /** The preview's principal, in major units of the household currency ("On 100.00 saved..."). */
        const val PREVIEW_PRINCIPAL_MAJOR: Long = 100

        /** The preview's period: a month, as 30 days of Actual/365 simple interest. */
        const val PREVIEW_DAYS: Int = 30
    }
}

/** "On [principal] saved for a month you'd earn about [earned]". */
data class InterestPreview(val principal: Money, val earned: Money)

/** Why the typed rate was refused. */
enum class RateError {
    REQUIRED,
    NOT_A_NUMBER,
    TOO_MANY_DECIMALS,

    /** Above 20.00% ([InterestPolicy.MAX_ANNUAL_RATE_BASIS_POINTS]). */
    TOO_HIGH,
}

/**
 * Percent text ↔ basis points without floating point: a percentage with two decimals is exactly a number of basis
 * points (2.50% = 250 bps), so the text is parsed with [MinorUnitsParser] at two fraction digits.
 */
object InterestRate {
    private const val PERCENT_DECIMALS = 2

    sealed interface Parsed {
        data class Valid(val basisPoints: Int) : Parsed

        data class Invalid(val error: RateError) : Parsed
    }

    private const val MAX_BASIS_POINTS = InterestPolicy.MAX_ANNUAL_RATE_BASIS_POINTS.toLong()

    fun parse(text: String): Parsed =
        when (val result = MinorUnitsParser.parse(text, PERCENT_DECIMALS, MAX_BASIS_POINTS)) {
            MinorUnitsParser.Result.Empty -> Parsed.Invalid(RateError.REQUIRED)

            is MinorUnitsParser.Result.Valid -> Parsed.Valid(result.minorUnits.toInt())

            is MinorUnitsParser.Result.Invalid -> Parsed.Invalid(
                when (result.error) {
                    AmountInputError.NOT_A_NUMBER -> RateError.NOT_A_NUMBER
                    AmountInputError.TOO_MANY_DECIMALS -> RateError.TOO_MANY_DECIMALS
                    AmountInputError.TOO_LARGE -> RateError.TOO_HIGH
                },
            )
        }

    /** [basisPoints] as editable percent text with two decimals: 250 → "2.50". */
    fun format(basisPoints: Int): String = MinorUnitsParser.toInputText(basisPoints.toLong(), PERCENT_DECIMALS)
}

sealed interface InterestSettingsEvent {
    data class RateChanged(val text: String) : InterestSettingsEvent

    /** 1 to [MAX_SCHEDULE_DAY_OF_MONTH]; anything else is clamped. */
    data class AccrualDayChanged(val day: Int) : InterestSettingsEvent

    data object Save : InterestSettingsEvent

    data object MessageShown : InterestSettingsEvent
}

/**
 * The household interest policy (D-M2-5), written through [InterestPolicyRepository.set] (outbox + audit). The
 * form follows the stored policy until the parent edits it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class InterestSettingsViewModel @Inject constructor(
    private val interest: InterestPolicyRepository,
    private val household: HouseholdContext,
) : ViewModel() {
    private val mutableState = MutableStateFlow(InterestSettingsUiState())
    val state: StateFlow<InterestSettingsUiState> = mutableState.asStateFlow()

    private var householdId: HouseholdId? = null
    private var edited = false

    init {
        viewModelScope.launch {
            flow {
                val id = household.householdId()
                householdId = id
                mutableState.update { it.copy(currency = household.fiatCurrency()) }
                emit(id)
            }
                .flatMapLatest { interest.observe(it) }
                .catch { mutableState.update { it.copy(isLoading = false, loadFailed = true) } }
                .collect { policy -> onPolicy(policy) }
        }
    }

    fun onEvent(event: InterestSettingsEvent) {
        when (event) {
            is InterestSettingsEvent.RateChanged -> edit { it.copy(rateText = event.text) }

            is InterestSettingsEvent.AccrualDayChanged ->
                edit { it.copy(accrualDay = event.day.coerceIn(1, MAX_SCHEDULE_DAY_OF_MONTH)) }

            InterestSettingsEvent.Save -> save()

            InterestSettingsEvent.MessageShown -> mutableState.update { it.copy(message = null) }
        }
    }

    private fun onPolicy(policy: InterestPolicy) = mutableState.update { current ->
        val synced = current.copy(isLoading = false, loadFailed = false, saved = policy)
        if (edited) synced else synced.withForm(InterestRate.format(policy.annualRateBasisPoints), policy.accrualDay)
    }

    private fun edit(transform: (InterestSettingsUiState) -> InterestSettingsUiState) {
        edited = true
        mutableState.update { current -> transform(current).let { it.withForm(it.rateText, it.accrualDay) } }
    }

    private fun InterestSettingsUiState.withForm(rateText: String, accrualDay: Int): InterestSettingsUiState {
        val parsed = InterestRate.parse(rateText)
        val currency = currency
        val preview = if (parsed is InterestRate.Parsed.Valid &&
            currency != null
        ) {
            previewFor(currency, parsed)
        } else {
            null
        }
        return copy(
            rateText = rateText,
            accrualDay = accrualDay,
            rateError = (parsed as? InterestRate.Parsed.Invalid)?.error,
            preview = preview,
        )
    }

    private fun previewFor(currency: Currency.Fiat, rate: InterestRate.Parsed.Valid): InterestPreview {
        val principal = Money(InterestSettingsUiState.PREVIEW_PRINCIPAL_MAJOR * pow10(currency.fractionDigits))
        val earned = InterestCalculator.accrue(principal, rate.basisPoints, InterestSettingsUiState.PREVIEW_DAYS)
        return InterestPreview(principal, earned)
    }

    private fun save() {
        val current = mutableState.value.takeIf { it.canSave } ?: return
        val id = householdId
        val bps = current.rateBasisPoints
        if (id == null || bps == null) return
        mutableState.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            val saved = attempt { interest.set(InterestPolicy(id, bps, current.accrualDay)) }
            if (saved) edited = false
            mutableState.update {
                it.copy(isSaving = false, message = if (saved) SettingsMessage.SAVED else SettingsMessage.SAVE_FAILED)
            }
        }
    }

    private fun pow10(exponent: Int): Long = (1..exponent).fold(1L) { acc, _ -> acc * DECIMAL_BASE }

    private companion object {
        const val DECIMAL_BASE = 10L
    }
}
