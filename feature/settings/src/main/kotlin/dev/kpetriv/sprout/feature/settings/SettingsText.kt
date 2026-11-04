package dev.kpetriv.sprout.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.ui.MoneyFormatter
import dev.kpetriv.sprout.core.ui.rememberMoneyFormatter
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
internal fun amountErrorText(error: AmountError?): String? = when (error) {
    null -> null
    AmountError.REQUIRED -> stringResource(R.string.feature_settings_error_required)
    AmountError.NOT_A_NUMBER -> stringResource(R.string.feature_settings_error_not_a_number)
    AmountError.TOO_MANY_DECIMALS -> stringResource(R.string.feature_settings_error_too_many_decimals)
    AmountError.TOO_LARGE -> stringResource(R.string.feature_settings_error_too_large)
    AmountError.MUST_BE_POSITIVE -> stringResource(R.string.feature_settings_error_must_be_positive)
}

@Composable
internal fun nameErrorText(error: NameError?, max: Int): String? = when (error) {
    null -> null
    NameError.REQUIRED -> stringResource(R.string.feature_settings_error_required)
    NameError.TOO_LONG -> pluralStringResource(R.plurals.feature_settings_error_name_too_long, max, max)
}

@Composable
internal fun tierName(tier: KidUiTier): String = stringResource(
    when (tier) {
        KidUiTier.SEEDLING -> R.string.feature_settings_tier_seedling
        KidUiTier.SPROUT -> R.string.feature_settings_tier_sprout
        KidUiTier.SAPLING -> R.string.feature_settings_tier_sapling
    },
)

/** [date] in the current locale's medium style ("7 Nov 2026" in en-GB). */
@Composable
internal fun formatDate(date: CalendarDate): String {
    val locale = LocalConfiguration.current.locales[0]
    return remember(date, locale) {
        LocalDate.of(date.year, date.month, date.day)
            .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
    }
}

/**
 * [minorUnits] of [currency] the way a parent says a small amount: "50p", "50¢", "50c" below one major unit in the
 * currencies that have such a short form, else [MoneyFormatter.format] ("£1.20"). The game limit's default is 50
 * minor units, which "£0.50" reads worse than "50p".
 */
@Composable
internal fun formatSmallAmount(minorUnits: Long, currency: Currency.Fiat): String {
    val formatter = rememberMoneyFormatter()
    val suffix = MINOR_UNIT_SUFFIX[currency.isoCode]
    val oneMajor = (1..currency.fractionDigits).fold(1L) { acc, _ -> acc * DECIMAL_RADIX }
    return if (suffix != null && minorUnits in 0 until oneMajor) {
        "$minorUnits$suffix"
    } else {
        formatter.format(Money(minorUnits), currency)
    }
}

private const val DECIMAL_RADIX = 10L

private val MINOR_UNIT_SUFFIX = mapOf(
    "GBP" to "p",
    "EUR" to "c",
    "USD" to "¢",
    "CAD" to "¢",
    "AUD" to "c",
    "NZD" to "c",
)
