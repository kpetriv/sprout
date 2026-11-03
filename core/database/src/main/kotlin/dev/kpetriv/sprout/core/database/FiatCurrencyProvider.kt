package dev.kpetriv.sprout.core.database

import dev.kpetriv.sprout.core.domainledger.Currency
import java.util.Locale

/** The household's money currency. Configurable; settings (M2) will store the parent's choice. */
fun interface FiatCurrencyProvider {
    fun fiatCurrency(): Currency.Fiat
}

/** The currency of [locale]'s country, or [FALLBACK] when it has none or Sprout does not support it. */
class LocaleFiatCurrencyProvider(private val locale: () -> Locale = Locale::getDefault) : FiatCurrencyProvider {
    override fun fiatCurrency(): Currency.Fiat = forLocale(locale())

    companion object {
        val FALLBACK: Currency.Fiat = Currency.Fiat("EUR")

        fun forLocale(locale: Locale): Currency.Fiat {
            // getInstance throws for a locale without a (supported) country; Currency.Fiat throws for an ISO code
            // outside Sprout's circulating-currency table.
            val code = runCatching { java.util.Currency.getInstance(locale)?.currencyCode }.getOrNull()
            return code?.let { runCatching { Currency.Fiat(it) }.getOrNull() } ?: FALLBACK
        }
    }
}
