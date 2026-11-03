package dev.kpetriv.sprout.core.database

import dev.kpetriv.sprout.core.domainhousehold.PendingParentAction
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.Money
import javax.inject.Inject

/**
 * The reason shown in the parent authorisation prompt for one queued action (R6): the prompt is a security surface,
 * so it says who is paid, how much, what for and for which period, for example "Pay Leo 3.00 GBP allowance for
 * 2026-W45". The app may bind a localised implementation; [EnglishPendingActionDescriber] is the default.
 */
fun interface PendingActionDescriber {
    suspend fun describe(action: PendingParentAction): String
}

/**
 * [PendingActionDescriber] in plain English: the kid's first name (or "your kid" for an unknown profile), the amount
 * with the currency's ISO 4217 fraction digits and code (minutes for a minutes account), the kind and the period key.
 * Integer formatting only; no floating point.
 */
class EnglishPendingActionDescriber @Inject constructor(private val kids: KidRepository) : PendingActionDescriber {
    override suspend fun describe(action: PendingParentAction): String {
        val name = kids.getKid(action.kidId)?.firstName ?: UNKNOWN_KID
        val amount = format(action.amount, action.currency)
        return when (action) {
            is PendingParentAction.PayAllowance -> "Pay $name $amount allowance for ${action.periodKey}"
            is PendingParentAction.AccrueInterest -> "Pay $name $amount savings interest for ${action.periodKey}"
        }
    }

    companion object {
        private const val UNKNOWN_KID = "your kid"
        private const val DECIMAL_BASE = 10L

        /** `"3.00 GBP"`, `"500 JPY"`, `"30 minutes"`: [amount]'s minor units with [currency]'s fraction digits. */
        fun format(amount: Money, currency: Currency): String = when (currency) {
            Currency.Minutes -> "${amount.minorUnits} minutes"
            is Currency.Fiat -> "${decimal(amount.minorUnits, currency.fractionDigits)} ${currency.isoCode}"
        }

        private fun decimal(minor: Long, digits: Int): String {
            if (digits == 0) return minor.toString()
            var unit = 1L
            repeat(digits) { unit *= DECIMAL_BASE }
            val sign = if (minor < 0) "-" else ""
            val absolute = if (minor < 0) -minor else minor
            val fraction = (absolute % unit).toString().padStart(digits, '0')
            return "$sign${absolute / unit}.$fraction"
        }
    }
}
