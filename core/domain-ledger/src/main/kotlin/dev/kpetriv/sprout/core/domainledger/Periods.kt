package dev.kpetriv.sprout.core.domainledger

import dev.kpetriv.sprout.core.model.AccountId
import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.model.KidId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What a periodic credit pays for. At most one applied event holds each claim: a second event with the same claim
 * is refused locally and excluded when projecting merged streams ([Violation.DuplicatePeriod]), whichever comes
 * later in [LedgerOrdering]. This makes allowance and interest planning and chore approval idempotent across
 * devices that plan or approve the same thing while offline.
 */
@Serializable
sealed interface PeriodClaim {
    /**
     * One [AllowancePaid] per kid, period and currency, so a money allowance and a minutes allowance paid for the
     * same period do not collide.
     */
    @Serializable
    @SerialName("allowance")
    data class Allowance(val kidId: KidId, val periodKey: String, val currency: Currency) : PeriodClaim

    /** One [ChoreRewarded] per kid, chore occurrence and currency (a chore may pay money and minutes). */
    @Serializable
    @SerialName("chore")
    data class Chore(val kidId: KidId, val choreId: String, val dueOn: CalendarDate, val currency: Currency) :
        PeriodClaim

    /** One [InterestAccrued] per savings account and period. */
    @Serializable
    @SerialName("interest")
    data class Interest(val accountId: AccountId, val periodKey: String) : PeriodClaim
}

/** The claim this event makes, or null for event types without one. */
fun LedgerEvent.periodClaim(): PeriodClaim? = when (this) {
    is AllowancePaid -> PeriodClaim.Allowance(envelope.kidId, periodKey, currency)
    is ChoreRewarded -> PeriodClaim.Chore(envelope.kidId, choreId, dueOn, currency)
    is InterestAccrued -> PeriodClaim.Interest(envelope.accountId, periodKey)
    else -> null
}

/**
 * Period keys of [AllowancePaid.periodKey] and [InterestAccrued.periodKey]: `YYYY-Www` for an ISO week (week 01 to
 * 53) or `YYYY-MM` for a month (01 to 12), with a four-digit year. Any other string breaks
 * [Violation.InvalidPeriodKey].
 */
object PeriodKeys {
    private val PATTERN = Regex("([0-9]{4})-(W([0-9]{2})|([0-9]{2}))")
    private const val MAX_YEAR = 9999
    private const val MAX_ISO_WEEK = 53
    private const val MONTHS = 12

    /** True if [key] is a well-formed weekly or monthly key. */
    fun isValid(key: String): Boolean {
        val match = PATTERN.matchEntire(key) ?: return false
        val week = match.groups[WEEK_GROUP]?.value?.toInt()
        val month = match.groups[MONTH_GROUP]?.value?.toInt()
        return if (week != null) week in 1..MAX_ISO_WEEK else month in 1..MONTHS
    }

    /** `YYYY-Www` for ISO week [isoWeek] of ISO week-numbering year [isoYear]. */
    fun weekly(isoYear: Int, isoWeek: Int): String {
        require(isoYear in 0..MAX_YEAR) { "isoYear must have four digits" }
        require(isoWeek in 1..MAX_ISO_WEEK) { "isoWeek must be in 1..53" }
        return "${pad(isoYear, YEAR_DIGITS)}-W${pad(isoWeek, 2)}"
    }

    /** `YYYY-MM` for [month] of [year]. */
    fun monthly(year: Int, month: Int): String {
        require(year in 0..MAX_YEAR) { "year must have four digits" }
        require(month in 1..MONTHS) { "month must be in 1..12" }
        return "${pad(year, YEAR_DIGITS)}-${pad(month, 2)}"
    }

    private fun pad(value: Int, digits: Int) = value.toString().padStart(digits, '0')

    private const val WEEK_GROUP = 3
    private const val MONTH_GROUP = 4
    private const val YEAR_DIGITS = 4
}

/**
 * A screen-time [RewardPurchased] that has been applied, and whether a [TimeBankCredited] has redeemed it yet.
 * Projections keep one per purchase with a [RewardPurchased.minutesGranted]; a grant with [creditedBy] null is
 * still owed to the kid (for example when the credit failed to append after the purchase).
 */
data class RewardGrant(
    val purchaseId: EventId,
    val kidId: KidId,
    val rewardId: String,
    val minutes: Money,
    val creditedBy: EventId?,
)
