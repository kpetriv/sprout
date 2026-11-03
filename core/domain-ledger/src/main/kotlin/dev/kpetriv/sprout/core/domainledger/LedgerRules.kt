package dev.kpetriv.sprout.core.domainledger

import dev.kpetriv.sprout.core.model.AccountId
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.Role
import kotlin.reflect.KClass

/**
 * Domain invariants (ADR-0002) checked before an event is accepted locally. Never throws: arithmetic overflow is
 * reported as [Violation.Overflow].
 *
 * Static rules (also applied while projecting merged streams; a violating event is excluded and reported as
 * [LedgerAnomaly.InvalidEvent]):
 * - **Positive amounts.** Every amount is > 0, except [ManualAdjustment], whose signed amount must be non-zero.
 * - **Bounded amounts.** No amount (and no interest principal) exceeds [LedgerPolicy.maxAmount] in absolute value.
 * - **Parent-only authors** for [PARENT_ONLY] types: [ChoreRewarded], [AllowancePaid], [ManualAdjustment],
 *   [InterestAccrued] and [OverdraftCompensated], plus a manual [TimeBankCredited] (no [TimeBankCredited.grantedBy])
 *   (THREAT_MODEL T1, T16); see [isParentOnly].
 * - **Parent signature.** With [LedgerPolicy.requireParentSignature], every parent-only event (from
 *   [LedgerPolicy.parentSignatureRequiredFromLamport] on, when set) carries a decodable, non-empty
 *   [Envelope.parentSignature] ([Violation.ParentSignatureMissing]); see [requiresParentSignature]. Presence only:
 *   the data layer verifies the signature against the parent key (ADR-0004).
 * - **Currency.** The event's [LedgerEvent.currency] equals the currency of every account it posts to; time bank
 *   events always target a [Currency.Minutes] account.
 * - **Ownership.** Every account posted to belongs to the envelope's kid.
 * - **Account kinds.** [ChoreRewarded], [AllowancePaid], [GamePlayed], [RewardPurchased] and the time bank events
 *   (including [TimeBankCredited]) target a [AccountKind.MAIN] account; [TransferToSavings] goes from a MAIN
 *   account to a different [AccountKind.SAVINGS] account; [InterestAccrued] targets a SAVINGS account.
 *   [ManualAdjustment] and [OverdraftCompensated] may target either.
 * - **Interest.** [InterestAccrued.amount] equals [InterestCalculator.accrue] of its recorded terms.
 * - **Text.** Adjustment reasons, time bank session ids, chore ids, credited reward ids and compensation causes
 *   are not blank/empty; reasons and notes are at most [MAX_TEXT_LENGTH] characters; no string holds an unpaired
 *   surrogate (it has no UTF-8 form, so the event could not be hashed or signed).
 * - **Period keys.** [AllowancePaid.periodKey] and [InterestAccrued.periodKey] are [PeriodKeys.isValid].
 * - **Granted minutes.** A [RewardPurchased.minutesGranted], when present, is positive and within the amount bound.
 *
 * History rules (against what was applied before the event, in total order; checked at local validation **and**
 * when projecting merged streams, where a violating event is excluded and reported as
 * [LedgerAnomaly.InvalidEvent]):
 * - **One payment per period.** At most one applied event per [PeriodClaim] ([Violation.DuplicatePeriod]).
 * - **Reward-referenced time credits.** A [TimeBankCredited] with a [TimeBankCredited.grantedBy] needs an applied
 *   [RewardPurchased] of the same kid with that id and a [RewardPurchased.minutesGranted]
 *   ([Violation.GrantNotFound]), the same reward id ([Violation.GrantRewardMismatch]), exactly that many minutes
 *   ([Violation.GrantMinutesMismatch]), and no earlier credit for that purchase ([Violation.GrantAlreadyCredited]).
 *
 * Stateful rules (against the [Projection] before the event; only at local validation time, and only once the
 * static rules pass):
 * - **Idempotency.** An already-applied eventId is a [Violation.DuplicateEvent].
 * - **Lamport bound.** The lamport is at most [LedgerPolicy.maxLamportJump] above [Projection.maxLamport].
 * - **No overdraw.** [RewardPurchased], [TransferToSavings] and a negative [ManualAdjustment] cannot take the
 *   balance below zero. [TimeBankStopped] is exempt: the minutes were already used (honour system).
 * - **Daily game cap.** [GamePlayed] credits for one kid, date and currency stay within
 *   [LedgerPolicy.dailyGameCapFor]. Projection applies the same rule to merged streams: a game that would go over
 *   the cap is excluded whole (never clamped) and reported as [LedgerAnomaly.DailyCapExceeded].
 * - **Time bank.** Only one running session per kid; starting needs a positive minutes balance; stopping must
 *   name the running session.
 * - **Known accounts.** Every account posted to is registered in [Projection.accounts].
 *
 * Two offline devices can each pass the stateful checks and together overdraw. Projection never rejects for
 * that: it records [LedgerAnomaly.OverdraftDetected] so a parent can author [OverdraftCompensated].
 */
object LedgerRules {
    /** Longest [ManualAdjustment.reason] or [OverdraftCompensated.note], in UTF-16 code units. */
    const val MAX_TEXT_LENGTH: Int = 200

    /**
     * Event types only a [Role.PARENT] may author, whatever their content. A [TimeBankCredited] is parent-only only
     * when it is a manual grant; use [isParentOnly] to decide for a given event.
     */
    val PARENT_ONLY: Set<KClass<out LedgerEvent>> = setOf(
        ChoreRewarded::class,
        AllowancePaid::class,
        ManualAdjustment::class,
        InterestAccrued::class,
        OverdraftCompensated::class,
    )

    /** Checks [event] against every static and stateful rule, given the projection it would be applied to. */
    fun validate(event: LedgerEvent, projectionBefore: Projection): ValidationResult {
        if (projectionBefore.contains(event.eventId)) {
            return ValidationResult.Invalid(listOf(Violation.DuplicateEvent(event.eventId)))
        }
        // Stateful checks only make sense for a well-formed event, so they run once the static ones pass.
        val violations = try {
            staticViolations(
                event,
                projectionBefore.accounts,
                projectionBefore.balances,
                requireKnownAccounts = true,
                policy = projectionBefore.policy,
            ).ifEmpty {
                historyViolations(event, projectionBefore.rewardGrants, projectionBefore.claimedPeriods) +
                    statefulViolations(event, projectionBefore)
            }
        } catch (expected: ArithmeticException) {
            listOf(Violation.Overflow)
        }
        return if (violations.isEmpty()) ValidationResult.Valid else ValidationResult.Invalid(violations)
    }

    /**
     * Rules that need no balances. With [requireKnownAccounts] false (projection of merged streams), an account
     * missing from [accounts] is checked against the currency of its projected balance, if any. Never throws.
     */
    internal fun staticViolations(
        event: LedgerEvent,
        accounts: Map<AccountId, Account>,
        balances: Map<AccountId, Balance>,
        requireKnownAccounts: Boolean,
        policy: LedgerPolicy,
    ): List<Violation> = buildList {
        addAll(amountViolations(event, policy.maxAmount))
        addAll(contentViolations(event))
        addAll(textViolations(event))
        if (event.isParentOnly() && event.envelope.authorRole != Role.PARENT) {
            add(Violation.ParentOnly(event::class.simpleName.orEmpty(), event.envelope.authorRole))
        }
        if (event.requiresParentSignature(policy) && event.parentSignatureBytes()?.isNotEmpty() != true) {
            add(Violation.ParentSignatureMissing(event::class.simpleName.orEmpty()))
        }
        for (accountId in event.touchedAccounts()) {
            val account = accounts[accountId]
            if (account == null) {
                if (requireKnownAccounts) add(Violation.UnknownAccount(accountId))
                balances[accountId]?.let { if (it.currency != event.currency) add(currencyMismatch(it, event)) }
            } else {
                addAll(accountViolations(event, account))
            }
        }
    }

    private fun amountViolations(event: LedgerEvent, max: Money): List<Violation> = buildList {
        if (event !is MovesValue) return@buildList
        val amount = event.amount
        when {
            event is ManualAdjustment -> if (amount == Money.ZERO) add(Violation.ZeroAdjustment)
            !amount.isPositive -> add(Violation.NonPositiveAmount(amount))
        }
        // max is positive, so -max cannot overflow; comparing never does.
        if (amount > max || amount < -max) add(Violation.AmountTooLarge(amount, max))
        if (event is InterestAccrued && event.principal > max) add(Violation.AmountTooLarge(event.principal, max))
        val granted = (event as? RewardPurchased)?.minutesGranted ?: return@buildList
        if (!granted.isPositive) add(Violation.NonPositiveAmount(granted))
        if (granted > max) add(Violation.AmountTooLarge(granted, max))
    }

    private fun contentViolations(event: LedgerEvent): List<Violation> = when (event) {
        is ManualAdjustment -> listOfNotNull(Violation.BlankText("reason").takeIf { event.reason.isBlank() })

        is TimeBankStarted -> listOfNotNull(Violation.BlankText("sessionId").takeIf { event.sessionId.isBlank() })

        is TimeBankStopped -> listOfNotNull(Violation.BlankText("sessionId").takeIf { event.sessionId.isBlank() })

        is OverdraftCompensated -> listOfNotNull(Violation.BlankText("causedBy").takeIf { event.causedBy.isEmpty() })

        is TransferToSavings ->
            listOfNotNull(Violation.SameAccountTransfer.takeIf { event.toAccountId == event.envelope.accountId })

        is InterestAccrued -> periodKeyViolations(event.periodKey) + interestViolations(event)

        is AllowancePaid -> periodKeyViolations(event.periodKey)

        is ChoreRewarded -> listOfNotNull(Violation.BlankText("choreId").takeIf { event.choreId.isBlank() })

        is TimeBankCredited -> listOfNotNull(Violation.BlankText("rewardId").takeIf { event.rewardId.isBlank() })

        else -> emptyList()
    }

    private fun periodKeyViolations(periodKey: String): List<Violation> =
        listOfNotNull(Violation.InvalidPeriodKey.takeUnless { PeriodKeys.isValid(periodKey) })

    private fun interestViolations(event: InterestAccrued): List<Violation> = when {
        event.annualRateBasisPoints < 0 || event.days <= 0 || event.principal.isNegative ->
            listOf(Violation.InvalidInterestTerms)

        else -> when (val expected = accrueOrNull(event)) {
            null -> listOf(Violation.Overflow)
            event.amount -> emptyList()
            else -> listOf(Violation.InterestMismatch(expected, event.amount))
        }
    }

    private fun accrueOrNull(event: InterestAccrued): Money? = try {
        InterestCalculator.accrue(event.principal, event.annualRateBasisPoints, event.days)
    } catch (expected: ArithmeticException) {
        null
    }

    /** Every string an event carries is well-formed UTF-16; the free-text ones are also length-bounded. */
    private fun textViolations(event: LedgerEvent): List<Violation> {
        val freeText = freeTextOf(event)
        return buildList {
            freeText.forEach { (field, text) ->
                if (text.length > MAX_TEXT_LENGTH) add(Violation.TextTooLong(field, MAX_TEXT_LENGTH))
            }
            (freeText + referencesOf(event)).forEach { (field, text) ->
                if (text.hasUnpairedSurrogate()) add(Violation.InvalidText(field))
            }
        }
    }

    /** Free text written by a person, by field name. */
    private fun freeTextOf(event: LedgerEvent): Map<String, String> = when (event) {
        is ManualAdjustment -> mapOf("reason" to event.reason)
        is OverdraftCompensated -> event.note?.let { mapOf("note" to it) }.orEmpty()
        else -> emptyMap()
    }

    /** Reference strings (catalogue, schedule, game and session ids), by field name. */
    private fun referencesOf(event: LedgerEvent): Map<String, String> = when (event) {
        is ChoreRewarded -> mapOf("choreId" to event.choreId)
        is AllowancePaid -> mapOf("scheduleId" to event.scheduleId, "periodKey" to event.periodKey)
        is InterestAccrued -> mapOf("periodKey" to event.periodKey)
        is GamePlayed -> mapOf("gameId" to event.gameId)
        is RewardPurchased -> mapOf("rewardId" to event.rewardId)
        is TimeBankCredited -> mapOf("rewardId" to event.rewardId)
        is TimeBankStarted -> mapOf("sessionId" to event.sessionId)
        is TimeBankStopped -> mapOf("sessionId" to event.sessionId)
        else -> emptyMap()
    }

    private fun accountViolations(event: LedgerEvent, account: Account): List<Violation> = buildList {
        if (account.currency != event.currency) {
            add(Violation.CurrencyMismatch(account.id, expected = account.currency, actual = event.currency))
        }
        if (account.kidId != event.envelope.kidId) {
            add(Violation.KidMismatch(account.id, expected = account.kidId, actual = event.envelope.kidId))
        }
        val requiredKind = requiredKind(event, account.id)
        if (requiredKind != null && account.kind != requiredKind) {
            add(Violation.WrongAccountKind(account.id, expected = requiredKind, actual = account.kind))
        }
    }

    /** The kind [accountId] must be for [event] to post to it; null when any kind will do. */
    private fun requiredKind(event: LedgerEvent, accountId: AccountId): AccountKind? = when (event) {
        is TransferToSavings -> if (accountId == event.toAccountId) AccountKind.SAVINGS else AccountKind.MAIN

        is InterestAccrued -> AccountKind.SAVINGS

        is ChoreRewarded, is AllowancePaid, is GamePlayed, is RewardPurchased, is TimeBankStarted,
        is TimeBankStopped, is TimeBankCredited,
        -> AccountKind.MAIN

        is ManualAdjustment, is OverdraftCompensated -> null
    }

    /**
     * The history rules: period uniqueness and reward-referenced time credits, against the [grants] and [claims]
     * applied before [event]. Shared by [validate] and projection, so both decide identically. Never throws.
     */
    internal fun historyViolations(
        event: LedgerEvent,
        grants: Map<EventId, RewardGrant>,
        claims: Map<PeriodClaim, EventId>,
    ): List<Violation> = buildList {
        event.periodClaim()?.let { claim ->
            claims[claim]?.let { add(Violation.DuplicatePeriod(claim, claimedBy = it)) }
        }
        val grantedBy = (event as? TimeBankCredited)?.grantedBy ?: return@buildList
        val grant = grants[grantedBy]?.takeIf { it.kidId == event.envelope.kidId }
        when {
            grant == null -> add(Violation.GrantNotFound(grantedBy))

            else -> {
                if (grant.rewardId != event.rewardId) add(Violation.GrantRewardMismatch(grantedBy))
                if (grant.minutes != event.minutes) {
                    add(Violation.GrantMinutesMismatch(grantedBy, expected = grant.minutes, actual = event.minutes))
                }
                grant.creditedBy?.let { add(Violation.GrantAlreadyCredited(grantedBy, creditedBy = it)) }
            }
        }
    }

    private fun currencyMismatch(balance: Balance, event: LedgerEvent) =
        Violation.CurrencyMismatch(balance.accountId, expected = balance.currency, actual = event.currency)

    private fun statefulViolations(event: LedgerEvent, before: Projection): List<Violation> = buildList {
        addAll(lamportViolations(event, before))
        addAll(fundsViolations(event, before))
        when (event) {
            is GamePlayed -> {
                val cap = before.policy.dailyGameCapFor(event.currency)
                val credited = before.dailyGameCredits(event.envelope.kidId, event.playedOn, event.currency)
                // credited never exceeds cap and both are non-negative, so cap - credited cannot overflow.
                if (event.amount > cap - credited) add(Violation.DailyCapExceeded(cap, credited, event.amount))
            }

            is TimeBankStarted -> {
                if (before.timeBank(event.envelope.kidId)?.running == true) add(Violation.TimeBankAlreadyRunning)
                if (!before.balanceOf(event.envelope.accountId).isPositive) add(Violation.TimeBankEmpty)
            }

            is TimeBankStopped -> {
                val state = before.timeBank(event.envelope.kidId)
                if (state?.running != true || state.sessionId != event.sessionId) add(Violation.TimeBankNotRunning)
            }

            else -> Unit
        }
    }

    private fun lamportViolations(event: LedgerEvent, before: Projection): List<Violation> {
        val maxJump = before.policy.maxLamportJump
        // Both lamports are non-negative, so the subtraction cannot overflow.
        return listOfNotNull(
            Violation.LamportTooFarAhead(latest = before.maxLamport, actual = event.lamport, maxJump = maxJump)
                .takeIf { event.lamport - before.maxLamport > maxJump },
        )
    }

    /** No overdraw, except for [TimeBankStopped]. Throws [ArithmeticException] if a posting would overflow. */
    private fun fundsViolations(event: LedgerEvent, before: Projection): List<Violation> = event.postings.mapNotNull {
        val available = before.balanceOf(it.accountId)
        val after = available + it.delta
        Violation.InsufficientFunds(it.accountId, available, requested = -it.delta)
            .takeIf { _ -> event !is TimeBankStopped && it.delta.isNegative && after.isNegative }
    }
}

/**
 * Every account an event touches: where it posts, or its envelope account if it posts nothing. Computed without
 * building [LedgerEvent.postings], so it cannot overflow on an extreme amount.
 */
internal fun LedgerEvent.touchedAccounts(): List<AccountId> = when (this) {
    is TransferToSavings -> listOf(envelope.accountId, toAccountId).distinct()
    else -> listOf(envelope.accountId)
}

/** True if this string holds a high surrogate not followed by a low one, or a low surrogate not preceded by one. */
internal fun String.hasUnpairedSurrogate(): Boolean = indices.any { index ->
    val char = this[index]
    val unpairedHigh = char.isHighSurrogate() && (index + 1 >= length || !this[index + 1].isLowSurrogate())
    val unpairedLow = char.isLowSurrogate() && (index == 0 || !this[index - 1].isHighSurrogate())
    unpairedHigh || unpairedLow
}

/** Result of [LedgerRules.validate]. */
sealed interface ValidationResult {
    val isValid: Boolean get() = this is Valid

    data object Valid : ValidationResult

    data class Invalid(val violations: List<Violation>) : ValidationResult {
        init {
            require(violations.isNotEmpty()) { "Invalid needs at least one violation" }
        }
    }
}

/** One broken invariant. */
sealed interface Violation {
    data class NonPositiveAmount(val amount: Money) : Violation

    /** [amount] (an event amount or an interest principal) is beyond [LedgerPolicy.maxAmount] in absolute value. */
    data class AmountTooLarge(val amount: Money, val max: Money) : Violation

    /** Folding the event would overflow a `Long` (only reachable with a [LedgerPolicy.maxAmount] near the limit). */
    data object Overflow : Violation

    data object ZeroAdjustment : Violation

    data class BlankText(val field: String) : Violation

    /** [field] is longer than [maxLength]. The text itself is never included (it may hold personal data). */
    data class TextTooLong(val field: String, val maxLength: Int) : Violation

    /** [field] holds an unpaired UTF-16 surrogate, so it has no UTF-8 form and cannot be hashed or signed. */
    data class InvalidText(val field: String) : Violation

    data object SameAccountTransfer : Violation

    data object InvalidInterestTerms : Violation

    /** [InterestAccrued.amount] is not what [InterestCalculator.accrue] gives for its recorded terms. */
    data class InterestMismatch(val expected: Money, val actual: Money) : Violation

    data class ParentOnly(val eventType: String, val authorRole: Role) : Violation

    /**
     * A parent-only [eventType] has no (decodable, non-empty) [Envelope.parentSignature] although
     * [LedgerPolicy.requireParentSignature] is set.
     */
    data class ParentSignatureMissing(val eventType: String) : Violation

    /** [AllowancePaid.periodKey] or [InterestAccrued.periodKey] is not [PeriodKeys.isValid]. */
    data object InvalidPeriodKey : Violation

    /** Another applied event, [claimedBy], already holds [claim]: this period was already paid. */
    data class DuplicatePeriod(val claim: PeriodClaim, val claimedBy: EventId) : Violation

    /** [grantedBy] is not an applied screen-time [RewardPurchased] of this kid. */
    data class GrantNotFound(val grantedBy: EventId) : Violation

    /** The credit names a different reward than the purchase [grantedBy]. */
    data class GrantRewardMismatch(val grantedBy: EventId) : Violation

    /** The credit's minutes ([actual]) differ from the purchase's [RewardPurchased.minutesGranted] ([expected]). */
    data class GrantMinutesMismatch(val grantedBy: EventId, val expected: Money, val actual: Money) : Violation

    /** The purchase [grantedBy] was already redeemed by the credit [creditedBy]. */
    data class GrantAlreadyCredited(val grantedBy: EventId, val creditedBy: EventId) : Violation

    data class UnknownAccount(val accountId: AccountId) : Violation

    data class CurrencyMismatch(val accountId: AccountId, val expected: Currency, val actual: Currency) : Violation

    data class KidMismatch(val accountId: AccountId, val expected: KidId, val actual: KidId) : Violation

    data class WrongAccountKind(val accountId: AccountId, val expected: AccountKind, val actual: AccountKind) :
        Violation

    data class DuplicateEvent(val eventId: EventId) : Violation

    /** The event's lamport [actual] is more than [maxJump] above the highest lamport seen locally, [latest]. */
    data class LamportTooFarAhead(val latest: Long, val actual: Long, val maxJump: Long) : Violation

    data class InsufficientFunds(val accountId: AccountId, val available: Money, val requested: Money) : Violation

    data class DailyCapExceeded(val cap: Money, val alreadyCredited: Money, val requested: Money) : Violation

    data object TimeBankAlreadyRunning : Violation

    data object TimeBankEmpty : Violation

    data object TimeBankNotRunning : Violation
}
