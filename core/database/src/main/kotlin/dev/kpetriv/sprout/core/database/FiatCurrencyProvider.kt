package dev.kpetriv.sprout.core.database

import dev.kpetriv.sprout.core.domainledger.Currency
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The household's money currency: the synced household policy's choice ([HouseholdFiatCurrencyProvider]).
 *
 * Prefer [observeFiatCurrency] on a screen (it follows the policy when a pull brings it) and [currentFiatCurrency] in
 * a suspend function; [fiatCurrency] never blocks and answers with the last value the provider saw.
 */
fun interface FiatCurrencyProvider {
    /** The currency as last known, without reading storage. */
    fun fiatCurrency(): Currency.Fiat

    /** The currency read now. */
    suspend fun currentFiatCurrency(): Currency.Fiat = fiatCurrency()

    /** The currency now and whenever it changes. */
    fun observeFiatCurrency(): Flow<Currency.Fiat> = flow { emit(currentFiatCurrency()) }
}

/**
 * [FiatCurrencyProvider] over the household policy ([HouseholdPolicyRepository.observe]): the policy's currency, which
 * the parent chose when creating the household (the trial's policy has the locale's). Only while this device has no
 * policy row yet (a phone restored or linked before its first pull) is it the locale's ([LocaleFiatCurrencyProvider]);
 * nothing may create a real household's policy from that fallback (D-REL-11).
 */
@Singleton
class HouseholdFiatCurrencyProvider @Inject constructor(
    private val policies: HouseholdPolicyRepository,
    private val locale: LocaleFiatCurrencyProvider,
) : FiatCurrencyProvider {
    @Volatile
    private var known: Currency.Fiat? = null

    override fun fiatCurrency(): Currency.Fiat = known ?: locale.fiatCurrency()

    override suspend fun currentFiatCurrency(): Currency.Fiat = remember(policies.get())

    override fun observeFiatCurrency(): Flow<Currency.Fiat> = policies.observe().map(::remember).distinctUntilChanged()

    private fun remember(policy: HouseholdPolicy?): Currency.Fiat {
        known = policy?.fiatCurrency
        return policy?.fiatCurrency ?: locale.fiatCurrency()
    }
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
