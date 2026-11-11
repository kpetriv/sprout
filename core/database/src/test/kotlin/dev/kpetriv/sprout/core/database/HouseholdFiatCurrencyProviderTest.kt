package dev.kpetriv.sprout.core.database

import dev.kpetriv.sprout.core.domainledger.Currency
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale
import kotlin.test.assertEquals

/** The app's [FiatCurrencyProvider] (D-REL-10): the household policy's currency, the locale's only without one. */
@RunWith(RobolectricTestRunner::class)
class HouseholdFiatCurrencyProviderTest {
    private val phone = parentDevice()
    private val eur = Currency.Fiat("EUR")
    private val provider = HouseholdFiatCurrencyProvider(phone.policies, LocaleFiatCurrencyProvider { Locale.GERMANY })

    @After
    fun close() = phone.close()

    @Test
    fun aGbpHouseholdOnAGermanPhoneIsGbpFromThePolicyOn() = runTest {
        // Restored, before its first pull: only the locale is known.
        assertEquals(eur, provider.currentFiatCurrency())
        assertEquals(eur, provider.fiatCurrency())

        assertEquals(eur, provider.observeFiatCurrency().first())

        phone.policies.ensure(phone.gbp) // the household's policy row, as the first pull stores it

        assertEquals(phone.gbp, provider.observeFiatCurrency().first())
        assertEquals(phone.gbp, provider.currentFiatCurrency())
        assertEquals(phone.gbp, provider.fiatCurrency(), "the non-blocking read answers with the last value seen")
    }
}
