package dev.kpetriv.sprout.core.domainledger

/** What an account's [Money] amounts count: real money in an ISO 4217 currency, or screen-time minutes. */
sealed interface Currency {
    /**
     * Real money; amounts are minor units of [isoCode] (for example pence for GBP).
     * Only circulating ISO 4217 currencies are accepted, so [fractionDigits] is always known.
     */
    data class Fiat(val isoCode: String) : Currency {
        init {
            require(isoCode in Iso4217.minorUnits) { "isoCode must be a supported ISO 4217 currency code" }
        }

        /** Digits after the decimal point in the major unit: 2 for GBP, 0 for JPY, 3 for KWD. */
        val fractionDigits: Int get() = Iso4217.minorUnits.getValue(isoCode)
    }

    /** Screen time; amounts are whole minutes. */
    data object Minutes : Currency
}

/**
 * Minor-unit exponents of circulating ISO 4217 currencies (fund codes, precious metals and withdrawn
 * codes such as HRK, BGN and SLL are left out). Source: the ISO 4217 list one table, 2026.
 */
internal object Iso4217 {
    private const val TWO_DIGITS = 2
    private const val THREE_DIGITS = 3

    private val zeroDigit = setOf(
        "BIF", "CLP", "DJF", "GNF", "ISK", "JPY", "KMF", "KRW", "PYG", "RWF", "UGX", "VND", "VUV",
        "XAF", "XOF", "XPF",
    )

    private val threeDigit = setOf("BHD", "IQD", "JOD", "KWD", "LYD", "OMR", "TND")

    private val twoDigit = setOf(
        "AED", "AFN", "ALL", "AMD", "AOA", "ARS", "AUD", "AWG", "AZN", "BAM", "BBD", "BDT", "BMD", "BND",
        "BOB", "BRL", "BSD", "BTN", "BWP", "BYN", "BZD", "CAD", "CDF", "CHF", "CNY", "COP", "CRC", "CUP",
        "CVE", "CZK", "DKK", "DOP", "DZD", "EGP", "ERN", "ETB", "EUR", "FJD", "FKP", "GBP", "GEL", "GHS",
        "GIP", "GMD", "GTQ", "GYD", "HKD", "HNL", "HTG", "HUF", "IDR", "ILS", "INR", "IRR", "JMD", "KES",
        "KGS", "KHR", "KPW", "KYD", "KZT", "LAK", "LBP", "LKR", "LRD", "LSL", "MAD", "MDL", "MGA", "MKD",
        "MMK", "MNT", "MOP", "MRU", "MUR", "MVR", "MWK", "MXN", "MYR", "MZN", "NAD", "NGN", "NIO", "NOK",
        "NPR", "NZD", "PAB", "PEN", "PGK", "PHP", "PKR", "PLN", "QAR", "RON", "RSD", "RUB", "SAR", "SBD",
        "SCR", "SDG", "SEK", "SGD", "SHP", "SLE", "SOS", "SRD", "SSP", "STN", "SVC", "SYP", "SZL", "THB",
        "TJS", "TMT", "TOP", "TRY", "TTD", "TWD", "TZS", "UAH", "USD", "UYU", "UZS", "VED", "VES", "WST",
        "XCD", "XCG", "YER", "ZAR", "ZMW", "ZWG",
    )

    /** ISO code to fraction digits. A code missing here is rejected by [Currency.Fiat]. */
    val minorUnits: Map<String, Int> = buildMap {
        twoDigit.forEach { put(it, TWO_DIGITS) }
        zeroDigit.forEach { put(it, 0) }
        threeDigit.forEach { put(it, THREE_DIGITS) }
    }.also { check(it.size == twoDigit.size + zeroDigit.size + threeDigit.size) { "ISO 4217 table has duplicates" } }
}
