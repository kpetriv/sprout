package dev.kpetriv.sprout.core.domainhousehold

import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.model.AccountId
import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.model.InterestPolicy
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.ScheduleId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A parent-only ledger event planned by a scheduler but not yet signed: the parent key needs a biometric unlock,
 * so these queue up and are drained at the next parent unlock (ADR-0004). This is an intent, not an event: the
 * data layer turns each into one ledger event when the parent authorises it.
 *
 * [key] is unique per planned event and stable across re-planning, so storing actions under it (and skipping keys
 * that already have an event) makes planning idempotent.
 */
@Serializable
sealed interface PendingParentAction {
    val kidId: KidId
    val accountId: AccountId
    val currency: Currency

    /** The amount the resulting event credits; always positive. */
    val amount: Money

    /** `2026-W45` for a weekly allowance, `2026-11` for a monthly allowance or an interest accrual. */
    val periodKey: String

    /** Idempotency key: `allowance/<scheduleId>/<periodKey>` or `interest/<accountId>/<periodKey>`. */
    val key: String

    /** One allowance payment, for the period [periodKey] due on [dueOn]. Becomes `AllowancePaid`. */
    @Serializable
    @SerialName("pay_allowance")
    data class PayAllowance(
        val scheduleId: ScheduleId,
        override val kidId: KidId,
        override val accountId: AccountId,
        override val amount: Money,
        override val currency: Currency,
        override val periodKey: String,
        val dueOn: CalendarDate,
    ) : PendingParentAction {
        init {
            require(amount.isPositive) { "allowance amount must be positive" }
            require(periodKey.isNotBlank()) { "periodKey must not be blank" }
        }

        override val key: String get() = "allowance/$scheduleId/$periodKey"
    }

    /**
     * Interest on a SAVINGS account for [days] days at [annualRateBasisPoints] on [principal]
     * (`amount = InterestCalculator.accrue(principal, annualRateBasisPoints, days)`). Becomes `InterestAccrued`.
     */
    @Serializable
    @SerialName("accrue_interest")
    data class AccrueInterest(
        override val kidId: KidId,
        override val accountId: AccountId,
        override val currency: Currency,
        val principal: Money,
        val annualRateBasisPoints: Int,
        val days: Int,
        override val periodKey: String,
        override val amount: Money,
    ) : PendingParentAction {
        init {
            require(!principal.isNegative) { "principal must not be negative" }
            require(annualRateBasisPoints in 1..InterestPolicy.MAX_ANNUAL_RATE_BASIS_POINTS) {
                "annualRateBasisPoints must be in 1..${InterestPolicy.MAX_ANNUAL_RATE_BASIS_POINTS}"
            }
            require(days > 0) { "days must be positive" }
            require(amount.isPositive) { "interest amount must be positive" }
            require(periodKey.isNotBlank()) { "periodKey must not be blank" }
        }

        override val key: String get() = "interest/$accountId/$periodKey"
    }
}
