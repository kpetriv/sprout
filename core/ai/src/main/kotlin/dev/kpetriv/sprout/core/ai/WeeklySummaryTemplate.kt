package dev.kpetriv.sprout.core.ai

import dev.kpetriv.sprout.core.domainhousehold.EarnedByKind
import dev.kpetriv.sprout.core.domainhousehold.WeeklyFacts
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.Money
import java.util.Locale

/**
 * Money as the summary writes it: the currency's symbol in [locale] and the amount with all its fraction digits and a
 * dot ("£3.50", "CHF 3.50", "¥120"). Never grouped, so [AiOutputRules.numbersIn] reads it back exactly.
 */
internal fun formatMoney(money: Money, currency: Currency.Fiat, locale: Locale): String {
    val symbol = runCatching { java.util.Currency.getInstance(currency.isoCode).getSymbol(locale) }
        .getOrDefault(currency.isoCode)
    val amount = AiOutputRules.majorUnits(money, currency.fractionDigits)
    return if (symbol.any(Char::isLetter)) "$symbol $amount" else "$symbol$amount"
}

/**
 * The rules weekly summary (D-M6-3): two to four fixed sentences filled from [WeeklyFacts] (English). It is the text
 * shown on the rules path, the draft the model rewrites, and what replaces a model answer that fails
 * [AiOutputRules.isValidSummary]. Complete facts are written as "Last week", others as "This week so far".
 */
internal object WeeklySummaryTemplate {
    fun text(facts: WeeklyFacts, locale: Locale): String {
        val period = if (facts.complete) "Last week" else "This week so far"
        if (facts.kids.isEmpty()) {
            return "$period, there is nothing to report yet. Chores, allowance and games will show up here."
        }
        val money = { amount: Money -> formatMoney(amount, facts.currency, locale) }
        val totals = facts.totals
        val who = facts.kids.singleOrNull()?.firstName ?: "your kids"
        val sentences = listOfNotNull(
            earnedSentence(period, who, totals.earned, money, facts.complete),
            choresSentence(totals.choresSubmitted, totals.choresApproved, totals.choresRejected),
            spendingSentence(totals.spentOnRewards, totals.movedToSavings, totals.minutesUsed, totals.choreMinutes) {
                money(it)
            },
            gamesSentence(totals.gamesPlayed, totals.anomalies),
        )
        return sentences.take(AiOutputRules.SUMMARY_SENTENCES.last).joinToString(" ")
    }

    private fun earnedSentence(
        period: String,
        who: String,
        earned: EarnedByKind,
        money: (Money) -> String,
        complete: Boolean,
    ): String {
        if (!earned.total.isPositive) return "$period, $who earned nothing${if (complete) "" else " yet"}."
        val parts = listOf(
            earned.chores to "from chores",
            earned.allowance to "in allowance",
            earned.games to "from games",
            earned.interest to "in interest",
        ).filter { (amount, _) -> amount.isPositive }
        val total = money(earned.total)
        val single = parts.singleOrNull()
        return if (single != null) {
            "$period, $who earned $total ${single.second}."
        } else {
            "$period, $who earned $total: ${join(parts.map { (amount, label) -> "${money(amount)} $label" })}."
        }
    }

    private fun choresSentence(submitted: Int, approved: Int, rejected: Int): String {
        val parts = listOfNotNull(
            submitted.takeIf { it > 0 }?.let { "$it marked done" },
            approved.takeIf { it > 0 }?.let { "$it approved" },
            rejected.takeIf { it > 0 }?.let { "$it sent back" },
        )
        return if (parts.isEmpty()) "No chores were marked done." else "Chores: ${join(parts)}."
    }

    private fun spendingSentence(
        spent: Money,
        saved: Money,
        minutesUsed: Money,
        minutesEarned: Money,
        money: (Money) -> String,
    ): String? {
        val parts = listOfNotNull(
            spent.takeIf { it.isPositive }?.let { "${money(it)} spent on rewards" },
            saved.takeIf { it.isPositive }?.let { "${money(it)} moved to savings" },
            minutesEarned.takeIf { it.isPositive }?.let { "${minutes(it)} earned from chores" },
            minutesUsed.takeIf { it.isPositive }?.let { "${minutes(it)} used" },
        )
        return if (parts.isEmpty()) null else "Also: ${join(parts)}."
    }

    private fun gamesSentence(games: Int, anomalies: Int): String? {
        val played = games.takeIf { it > 0 }?.let {
            "${plural(it, "brain game")} ${if (it == 1) "was" else "were"} played"
        }
        val warnings = anomalies.takeIf { it > 0 }?.let {
            "${plural(it, "ledger warning")} ${if (it == 1) "needs" else "need"} a look in the audit view"
        }
        return listOfNotNull(played, warnings).takeIf { it.isNotEmpty() }?.let { "${join(it)}." }
    }

    private fun minutes(amount: Money): String = plural(amount.minorUnits.toInt(), "screen-time minute")

    private fun plural(count: Int, noun: String): String = if (count == 1) "1 $noun" else "$count ${noun}s"

    /** "a", "a and b", "a, b and c". */
    private fun join(parts: List<String>): String =
        if (parts.size <= 1) parts.joinToString("") else parts.dropLast(1).joinToString(", ") + " and " + parts.last()
}
