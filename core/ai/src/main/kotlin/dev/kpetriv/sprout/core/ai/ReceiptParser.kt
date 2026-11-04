package dev.kpetriv.sprout.core.ai

import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.LedgerPolicy
import dev.kpetriv.sprout.core.domainledger.Money

/**
 * One line of text recognised on a receipt, with its box in image pixels ([top] and [bottom] rows, [left] column), so
 * a label and its amount printed in two columns of the same row can be paired.
 */
data class RecognizedLine(val text: String, val top: Int, val bottom: Int, val left: Int) {
    override fun toString(): String = "RecognizedLine(text=$REDACTED, top=$top, bottom=$bottom, left=$left)"
}

/** Reads the lines of text in a receipt photo. Null when the image cannot be decoded or recognition fails. */
fun interface ReceiptTextReader {
    suspend fun read(jpeg: ByteArray): List<RecognizedLine>?
}

/**
 * Rules parsing of recognised receipt lines (D-M6-5): the amount is the largest amount on a line labelled "total"
 * (or "amount due", "balance due", "to pay"), or on a line in the same row as such a label; the merchant is the first
 * line, from the top, that has letters and is not a link, e-mail address or phone number, cut to
 * [AiGateway.MAX_MERCHANT_LENGTH] characters. Sub-totals, item counts, savings, discounts, points and VAT or tax lines
 * are not the payment, but "Total inc VAT" is. English labels only.
 */
internal object ReceiptParser {
    private const val MERCHANT_LINES = 5
    private const val SAME_ROW_OVERLAP_PERCENT = 50
    private const val PERCENT = 100
    private const val DECIMAL = 10L
    private const val MAX_WHOLE_DIGITS = 12
    private const val THREE = 3

    private val totalLabel = Regex("(?i)\\b(?:total|amount\\s+due|balance\\s+due|to\\s+pay|grand\\s+total)\\b")

    /** Lines that say "total" but are not what was paid. */
    private val notThePayment =
        Regex("(?i)\\b(?:items?|qty|quantity|savings?|saved|discount|points|vat|tax)\\b|\\bsub[\\s-]*total")

    /** "inc VAT", "incl. tax", "including VAT": a total with tax in it, which is still the payment. */
    private val taxIncluded = Regex("(?i)\\b(?:inc|incl|including|includes)\\.?\\s*(?:vat|tax)\\b")

    /** Dates and times, removed before amounts are read ("12/03/2026", "14:05"). */
    private val dateOrTime = Regex("\\d{1,4}[/-]\\d{1,2}[/-]\\d{1,4}|\\d{1,2}:\\d{2}(?::\\d{2})?")

    /** An amount: digits with optional thousands separators and a decimal part. */
    private val amount = Regex("(?<![\\d.,])(?<whole>\\d{1,3}(?:[ ,.]\\d{3})+|\\d+)(?:[.,](?<fraction>\\d+))?(?![\\d])")

    fun parse(
        lines: List<RecognizedLine>,
        currency: Currency.Fiat,
        policy: LedgerPolicy = LedgerPolicy(),
    ): ReceiptPrefill? {
        val ordered = lines.filter { it.text.isNotBlank() }.sortedWith(compareBy({ it.top }, { it.left }))
        val total = totalOf(ordered, currency)?.takeIf { it.isPositive && it <= policy.maxAmount }
        val merchant = merchantOf(ordered)
        return if (total == null && merchant == null) null else ReceiptPrefill(total, merchant)
    }

    private fun totalOf(lines: List<RecognizedLine>, currency: Currency.Fiat): Money? {
        val labelled = lines.filter {
            totalLabel.containsMatchIn(it.text) && !notThePayment.containsMatchIn(it.text.replace(taxIncluded, " "))
        }
        val candidates = labelled.flatMap { label ->
            val row = lines.filter { it === label || sameRow(it, label) }
            row.flatMap { amountsIn(it.text, currency) }
        }
        return candidates.maxOrNull()
    }

    private fun sameRow(a: RecognizedLine, b: RecognizedLine): Boolean {
        val overlap = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        val smaller = minOf(a.bottom - a.top, b.bottom - b.top)
        return smaller > 0 && overlap * PERCENT >= smaller * SAME_ROW_OVERLAP_PERCENT
    }

    /**
     * Amounts written in [text] in [currency]'s minor units. A two-digit currency needs its two decimals ("12.50",
     * "12,50"); a whole number is read as major units ("Total 12" is 12.00). Thousands separators are dropped; dates
     * and times are ignored. In a three-digit currency (KWD, BHD) a dot or comma before the last three digits is the
     * decimal point when it is the only separator or differs from the ones before it ("12.500" is 12.500, not 12,500;
     * "1,250.500" is 1,250.500).
     */
    fun amountsIn(text: String, currency: Currency.Fiat): List<Money> {
        val digits = currency.fractionDigits
        return amount.findAll(text.replace(dateOrTime, " ")).mapNotNull { match ->
            val raw = match.groups["whole"]?.value.orEmpty()
            val whole = raw.filter(Char::isDigit)
            val fraction = match.groups["fraction"]?.value.orEmpty()
            val decimals = if (fraction.isEmpty() && digits == THREE) threeDecimalSplit(raw) else null
            when {
                decimals != null -> minor(decimals.first, decimals.second, digits)
                fraction.isEmpty() -> minor(whole, "", digits)
                fraction.length == digits -> minor(whole, fraction, digits)
                else -> null
            }
        }.toList()
    }

    /** [raw] (grouped digits) as whole and fraction digits when its last dot or comma is a decimal point. */
    private fun threeDecimalSplit(raw: String): Pair<String, String>? {
        val separators = raw.filterNot(Char::isDigit)
        val last = separators.lastOrNull()?.takeIf { it != ' ' } ?: return null
        val decimal = separators.length == 1 || separators.first() != last
        val whole = raw.substringBeforeLast(last).filter(Char::isDigit)
        return if (decimal) whole to raw.substringAfterLast(last) else null
    }

    /** [whole].[fraction] in minor units; null past [MAX_WHOLE_DIGITS] whole digits (far beyond any bound). */
    private fun minor(whole: String, fraction: String, digits: Int): Money? {
        var scale = 1L
        repeat(digits) { scale *= DECIMAL }
        val part = if (fraction.isEmpty()) 0L else fraction.toLong()
        return whole.takeIf { it.length <= MAX_WHOLE_DIGITS }?.toLongOrNull()?.let { Money(it * scale + part) }
    }

    private fun merchantOf(lines: List<RecognizedLine>): String? = lines.take(MERCHANT_LINES)
        .map { it.text.trim().replace(Regex("\\s+"), " ") }
        .filterNot { it.length < 2 || it.count(Char::isLetter) < 2 }
        .filterNot { AiOutputRules.hasUrl(it) || AiOutputRules.hasEmail(it) || AiOutputRules.hasPhoneNumber(it) }
        .map { line -> line.filterNot { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() } }
        .firstOrNull()
        ?.let(::truncate)

    /**
     * At most [AiGateway.MAX_MERCHANT_LENGTH] characters: cut at the last space when it keeps at least half, otherwise
     * mid-word; never splitting a surrogate pair.
     */
    private fun truncate(text: String): String {
        if (text.length <= AiGateway.MAX_MERCHANT_LENGTH) return text
        var end = AiGateway.MAX_MERCHANT_LENGTH
        if (Character.isLowSurrogate(text[end])) end--
        val cut = text.substring(0, end)
        val space = cut.lastIndexOf(' ')
        return (if (space >= AiGateway.MAX_MERCHANT_LENGTH / 2) cut.substring(0, space) else cut).trimEnd()
    }
}
