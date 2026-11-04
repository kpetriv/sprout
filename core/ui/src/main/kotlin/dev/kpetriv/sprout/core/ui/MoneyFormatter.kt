package dev.kpetriv.sprout.core.ui

import android.content.res.Resources
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.model.KidUiTier
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale

/**
 * Turns ledger [Money] into display text. Never goes through floating point: fiat amounts become a [BigDecimal]
 * scaled by the currency's fraction digits before [NumberFormat] sees them.
 *
 * - Fiat: the locale's currency format with exactly [Currency.Fiat.fractionDigits] decimals ("£12.50", "¥1,200").
 * - Minutes: "45 min", "2 h", "1 h 20 min".
 * - Seedling: whole "coins" (minor units dropped, never negative) for fiat; minutes as above. Game credits, a few
 *   minor units each, are counted in named minor units instead ("8 pennies", [formatMinorUnits]).
 *
 * Thread-safe: a fresh [NumberFormat] is created per call.
 */
class MoneyFormatter(
    private val locale: Locale = Locale.getDefault(),
    private val labels: MoneyLabels = MoneyLabels.English,
) {
    /**
     * Formats [money] in [currency]. With [signed], positive amounts get a leading "+" and negative ones a leading
     * "-" regardless of the locale's negative pattern; zero is never signed.
     */
    fun format(money: Money, currency: Currency, signed: Boolean = false): String = when (currency) {
        is Currency.Fiat -> formatFiat(money, currency, signed)
        Currency.Minutes -> formatMinutes(money.minorUnits, signed)
    }

    /**
     * Formats [money] the way a kid on [tier] reads it. Seedling never sees decimals or minus signs: fiat becomes
     * whole coins and anything below zero shows as zero (the parent sees the real figure).
     */
    fun formatForTier(money: Money, currency: Currency, tier: KidUiTier, signed: Boolean = false): String =
        when (tier) {
            KidUiTier.SEEDLING -> when (currency) {
                is Currency.Fiat -> formatCoins(money, currency)
                Currency.Minutes -> formatMinutes(money.minorUnits.coerceAtLeast(0))
            }

            KidUiTier.SPROUT, KidUiTier.SAPLING -> format(money, currency, signed)
        }

    /**
     * Formats a price (or an amount still needed) the way a kid on [tier] reads it. Like [formatForTier], except that
     * Seedling coins round **up** ([priceCoins]): balances round down, so a price shown as "2 coins" is affordable
     * exactly when the jar shows at least 2 coins, and a kid never sees "1 coin" for something their "1 coin" cannot
     * buy.
     */
    fun formatPriceForTier(money: Money, currency: Currency, tier: KidUiTier): String =
        if (tier == KidUiTier.SEEDLING && currency is Currency.Fiat) {
            val coins = priceCoins(money, currency)
            labels.coins(coins, integerFormat().format(coins))
        } else {
            formatForTier(money, currency, tier)
        }

    /** "7 coins": the whole major units of [money], never negative. */
    fun formatCoins(money: Money, currency: Currency.Fiat): String {
        val coins = wholeCoins(money, currency)
        return labels.coins(coins, integerFormat().format(coins))
    }

    /**
     * "8 pennies", "1 cent": [money] counted in minor units, named for [currency] ([MoneyLabels.minorUnits]), never
     * negative. Seedling game credits (D-M5-10): a game earns a few minor units, which whole [formatCoins] would show
     * as nothing. With [signed], a positive count gets a leading "+" ("+8 pennies"); zero is never signed.
     */
    fun formatMinorUnits(money: Money, currency: Currency.Fiat, signed: Boolean = false): String {
        val count = money.minorUnits.coerceAtLeast(0)
        val text = labels.minorUnits(currency, count, integerFormat().format(count))
        return if (signed && count > 0) PLUS + text else text
    }

    /** "45 min", "2 h", "1 h 20 min". With [signed], a leading "+" or "-" like [format]. */
    fun formatMinutes(minutes: Long, signed: Boolean = false): String {
        val magnitude = BigDecimal.valueOf(minutes).abs()
        val (hours, rest) = magnitude.divideAndRemainder(MINUTES_PER_HOUR).let { it[0] to it[1] }
        val numbers = integerFormat()
        val text = when {
            hours.signum() == 0 -> labels.minutes(numbers.format(rest))
            rest.signum() == 0 -> labels.hours(numbers.format(hours))
            else -> labels.hoursAndMinutes(numbers.format(hours), numbers.format(rest))
        }
        return when {
            minutes < 0 -> MINUS + text
            signed && minutes > 0 -> PLUS + text
            else -> text
        }
    }

    private fun formatFiat(money: Money, currency: Currency.Fiat, signed: Boolean): String {
        val format = NumberFormat.getCurrencyInstance(locale).apply {
            this.currency = java.util.Currency.getInstance(currency.isoCode)
            minimumFractionDigits = currency.fractionDigits
            maximumFractionDigits = currency.fractionDigits
        }
        val amount = majorUnits(money, currency)
        if (!signed) return format.format(amount)
        val text = format.format(amount.abs())
        return when (amount.signum()) {
            1 -> PLUS + text
            -1 -> MINUS + text
            else -> text
        }
    }

    private fun integerFormat(): NumberFormat = NumberFormat.getIntegerInstance(locale)

    companion object {
        private const val PLUS = "+"
        private const val MINUS = "-"
        private const val DECIMAL_RADIX = 10L
        private val MINUTES_PER_HOUR = BigDecimal.valueOf(60)

        /** [money] as an exact decimal in major units: 1250 pence becomes 12.50. */
        fun majorUnits(money: Money, currency: Currency.Fiat): BigDecimal =
            BigDecimal.valueOf(money.minorUnits, currency.fractionDigits)

        /** Whole major units of [money] (minor units dropped), clamped at zero. Seedling's "coins". */
        fun wholeCoins(money: Money, currency: Currency.Fiat): Long =
            (money.minorUnits / pow10(currency.fractionDigits)).coerceAtLeast(0)

        /** Whole major units of [money] rounded up (a part coin counts as a coin), clamped at zero. Seedling prices. */
        fun priceCoins(money: Money, currency: Currency.Fiat): Long {
            val unit = pow10(currency.fractionDigits)
            val minor = money.minorUnits.coerceAtLeast(0)
            return minor / unit + if (minor % unit == 0L) 0 else 1
        }

        private fun pow10(exponent: Int): Long = (1..exponent).fold(1L) { acc, _ ->
            Math.multiplyExact(acc, DECIMAL_RADIX)
        }
    }
}

/** Fraction digits amounts in this currency are entered and shown with: 0 for minutes. */
val Currency.displayFractionDigits: Int
    get() = when (this) {
        is Currency.Fiat -> fractionDigits
        Currency.Minutes -> 0
    }

/**
 * Unit words used by [MoneyFormatter]. Each function receives numbers already formatted for the locale.
 * Use [fromResources] in the app so the words and plural rules follow the user's language.
 */
interface MoneyLabels {
    fun minutes(minutes: String): String

    fun hours(hours: String): String

    fun hoursAndMinutes(hours: String, minutes: String): String

    fun coins(count: Long, formattedCount: String): String

    /**
     * [count] minor units of [currency]: pennies for GBP, cents for EUR and USD, and [coins] for any other currency
     * (the default, so existing implementations keep working).
     */
    fun minorUnits(currency: Currency.Fiat, count: Long, formattedCount: String): String = coins(count, formattedCount)

    /** How a currency's minor unit is named. */
    enum class MinorUnitName {
        PENNY,
        CENT,
        COIN,
        ;

        companion object {
            /** [MinorUnitName] of [currency]'s minor unit: [COIN] for currencies without a name of their own here. */
            fun of(currency: Currency.Fiat): MinorUnitName = when (currency.isoCode) {
                "GBP" -> PENNY
                "EUR", "USD" -> CENT
                else -> COIN
            }
        }
    }

    companion object {
        /** English words with English plural rules; the default for tests and before resources are available. */
        val English: MoneyLabels = object : MoneyLabels {
            override fun minutes(minutes: String) = "$minutes min"

            override fun hours(hours: String) = "$hours h"

            override fun hoursAndMinutes(hours: String, minutes: String) = "$hours h $minutes min"

            override fun coins(count: Long, formattedCount: String) =
                if (count == 1L) "$formattedCount coin" else "$formattedCount coins"

            override fun minorUnits(currency: Currency.Fiat, count: Long, formattedCount: String) =
                when (MinorUnitName.of(currency)) {
                    MinorUnitName.PENNY -> if (count == 1L) "$formattedCount penny" else "$formattedCount pennies"
                    MinorUnitName.CENT -> if (count == 1L) "$formattedCount cent" else "$formattedCount cents"
                    MinorUnitName.COIN -> coins(count, formattedCount)
                }
        }

        /** Labels from this module's string and plural resources. */
        fun fromResources(resources: Resources): MoneyLabels = object : MoneyLabels {
            override fun minutes(minutes: String) = resources.getString(R.string.core_ui_minutes_short, minutes)

            override fun hours(hours: String) = resources.getString(R.string.core_ui_hours_short, hours)

            override fun hoursAndMinutes(hours: String, minutes: String) =
                resources.getString(R.string.core_ui_hours_minutes_short, hours, minutes)

            override fun coins(count: Long, formattedCount: String) = resources.getQuantityString(
                R.plurals.core_ui_coins,
                count.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
                formattedCount,
            )

            override fun minorUnits(currency: Currency.Fiat, count: Long, formattedCount: String): String {
                val plural = when (MinorUnitName.of(currency)) {
                    MinorUnitName.PENNY -> R.plurals.core_ui_pennies
                    MinorUnitName.CENT -> R.plurals.core_ui_cents
                    MinorUnitName.COIN -> R.plurals.core_ui_coins
                }
                val quantity = count.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
                return resources.getQuantityString(plural, quantity, formattedCount)
            }
        }
    }
}
