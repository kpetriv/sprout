package dev.kpetriv.sprout.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.model.KidUiTier
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
