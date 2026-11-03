package dev.kpetriv.sprout.core.domainledger

import dev.kpetriv.sprout.core.model.AccountId
import dev.kpetriv.sprout.core.model.CalendarDate
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A signed change to one account's balance. Negative [delta] is a debit. */
data class Posting(val accountId: AccountId, val delta: Money)

/**
 * An immutable, append-only ledger event (ADR-0002). Events are never edited or deleted; mistakes are corrected
 * by a new compensating event. Every event posts to [Envelope.accountId] in [currency], which must equal that
 * account's currency (see [LedgerRules]).
 *
 * The `@SerialName` of each type and every property name are part of the canonical encoding: renaming one
 * changes every hash, so they must only change together with [Envelope.schemaVersion].
 *
 * **Schema version 2** added [TimeBankCredited], the optional [RewardPurchased.minutesGranted] (omitted while null,
 * so version 1 purchases keep their exact stored and canonical form) and the required period fields
 * [ChoreRewarded.dueOn], [AllowancePaid.periodKey] and [InterestAccrued.periodKey]. No production data existed when
 * those became required, so a version 1 document of those three types no longer decodes into a class
 * ([LedgerJson.decodeOrNull] returns null); its stored tree still hashes and verifies unchanged.
 */
@Serializable
sealed interface LedgerEvent {
    val envelope: Envelope

    /** Currency of every amount in the event; equal to the target account's currency. */
    val currency: Currency

    /** The balance changes this event makes when applied. Empty for events that move no value. */
    val postings: List<Posting>

    /** This event with [envelope] replaced (used to attach the hash and signature). */
    fun withEnvelope(envelope: Envelope): LedgerEvent

    val eventId: EventId get() = envelope.eventId
    val lamport: Long get() = envelope.lamport
}

/**
 * An event that moves a single [amount] in [currency]. Every type except [TimeBankStarted]. The amount is
 * strictly positive, except for [ManualAdjustment], where it is signed and non-zero.
 */
sealed interface MovesValue : LedgerEvent {
    val amount: Money
}

private fun LedgerEvent.credit(amount: Money) = listOf(Posting(envelope.accountId, amount))

private fun LedgerEvent.debit(amount: Money) = listOf(Posting(envelope.accountId, -amount))

/**
 * A parent approved a chore; credits [amount]. Parent only. At most one is applied per
 * [PeriodClaim.Chore] (kid, [choreId], [dueOn], currency), so re-approving the same occurrence is idempotent.
 *
 * @property dueOn the chore occurrence this rewards (schema version 2).
 */
@Serializable
@SerialName("chore_rewarded")
data class ChoreRewarded(
    override val envelope: Envelope,
    override val currency: Currency,
    override val amount: Money,
    val choreId: String,
    val dueOn: CalendarDate,
) : MovesValue {
    override val postings: List<Posting> get() = credit(amount)

    override fun withEnvelope(envelope: Envelope) = copy(envelope = envelope)
}

/**
 * A scheduled allowance credit. Parent only. At most one is applied per [PeriodClaim.Allowance] (kid,
 * [periodKey], [currency]), so re-planning a period is idempotent.
 *
 * @property periodKey the period paid for, `YYYY-Www` (ISO week) or `YYYY-MM`; see [PeriodKeys] (schema version 2).
 */
@Serializable
@SerialName("allowance_paid")
data class AllowancePaid(
    override val envelope: Envelope,
    override val currency: Currency,
    override val amount: Money,
    val scheduleId: String,
    val periodKey: String,
) : MovesValue {
    override val postings: List<Posting> get() = credit(amount)

    override fun withEnvelope(envelope: Envelope) = copy(envelope = envelope)
}

/**
 * A small credit for a played game, counted against the daily cap for [playedOn] (the household's local date,
 * declared by the author device; see THREAT_MODEL T12).
 */
@Serializable
@SerialName("game_played")
data class GamePlayed(
    override val envelope: Envelope,
    override val currency: Currency,
    override val amount: Money,
    val gameId: String,
    val score: Long,
    val playedOn: CalendarDate,
) : MovesValue {
    override val postings: List<Posting> get() = credit(amount)

    override fun withEnvelope(envelope: Envelope) = copy(envelope = envelope)
}

/**
 * A purchase from the rewards catalogue; debits [amount].
 *
 * @property minutesGranted (schema version 2) for a screen-time reward, the minutes the purchase entitles the kid to;
 *   a [TimeBankCredited] that names this purchase must credit exactly this many. The ledger does not know the
 *   catalogue, so the writing app must take it from the reward definition. Null (and then omitted from the stored and
 *   canonical form, exactly like a version 1 purchase) for every other reward.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@SerialName("reward_purchased")
data class RewardPurchased(
    override val envelope: Envelope,
    override val currency: Currency,
    override val amount: Money,
    val rewardId: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val minutesGranted: Money? = null,
) : MovesValue {
    override val postings: List<Posting> get() = debit(amount)

    override fun withEnvelope(envelope: Envelope) = copy(envelope = envelope)
}

/**
 * A parent's correction or receipt-based entry. [amount] is signed (negative debits) and never zero.
 *
 * @property reason free text written by a parent, at most [LedgerRules.MAX_TEXT_LENGTH] characters. It may hold
 *   personal data, so [toString] redacts it and it never reaches a log.
 */
@Serializable
@SerialName("manual_adjustment")
data class ManualAdjustment(
    override val envelope: Envelope,
    override val currency: Currency,
    override val amount: Money,
    val reason: String,
) : MovesValue {
    override val postings: List<Posting> get() = credit(amount)

    override fun withEnvelope(envelope: Envelope) = copy(envelope = envelope)

    override fun toString(): String =
        "ManualAdjustment(envelope=$envelope, currency=$currency, amount=$amount, reason=$REDACTED)"
}

/** Moves [amount] from [Envelope.accountId] (the main account) to the savings account [toAccountId]. */
@Serializable
@SerialName("transfer_to_savings")
data class TransferToSavings(
    override val envelope: Envelope,
    override val currency: Currency,
    override val amount: Money,
    val toAccountId: AccountId,
) : MovesValue {
    override val postings: List<Posting>
        get() = listOf(Posting(envelope.accountId, -amount), Posting(toAccountId, amount))

    override fun withEnvelope(envelope: Envelope) = copy(envelope = envelope)
}

/**
 * Interest credited to a savings account. [principal], [annualRateBasisPoints] and [days] record how [amount] was
 * derived (see [InterestCalculator]) so the parent audit view can show the working. Parent only. At most one is
 * applied per [PeriodClaim.Interest] (account, [periodKey]).
 *
 * @property periodKey the accrual period, `YYYY-MM` (or `YYYY-Www`); see [PeriodKeys] (schema version 2).
 */
@Serializable
@SerialName("interest_accrued")
data class InterestAccrued(
    override val envelope: Envelope,
    override val currency: Currency,
    override val amount: Money,
    val principal: Money,
    val annualRateBasisPoints: Int,
    val days: Int,
    val periodKey: String,
) : MovesValue {
    override val postings: List<Posting> get() = credit(amount)

    override fun withEnvelope(envelope: Envelope) = copy(envelope = envelope)
}

/** The kid started spending screen time from the minutes account. Moves no value. */
@Serializable
@SerialName("time_bank_started")
data class TimeBankStarted(override val envelope: Envelope, val sessionId: String) : LedgerEvent {
    override val currency: Currency get() = Currency.Minutes
    override val postings: List<Posting> get() = emptyList()

    override fun withEnvelope(envelope: Envelope) = copy(envelope = envelope)
}

/**
 * The kid stopped a time bank session; debits [minutesUsed] from the minutes account. The time has already been
 * spent (honour system), so this debit is never refused for insufficient minutes; an overdraw shows up as
 * [LedgerAnomaly.OverdraftDetected] instead.
 */
@Serializable
@SerialName("time_bank_stopped")
data class TimeBankStopped(override val envelope: Envelope, val sessionId: String, val minutesUsed: Money) :
    MovesValue {
    override val amount: Money get() = minutesUsed
    override val currency: Currency get() = Currency.Minutes
    override val postings: List<Posting> get() = debit(minutesUsed)

    override fun withEnvelope(envelope: Envelope) = copy(envelope = envelope)
}

/**
 * Screen-time minutes credited to the kid's minutes account (schema version 2); credits [minutes].
 *
 * Authorisation:
 * - **Reward-referenced** ([grantedBy] set): any author may write it, because the purchase it names is the
 *   authority. It is applied only if [grantedBy] is an applied [RewardPurchased] of the same kid with the same
 *   [rewardId] whose [RewardPurchased.minutesGranted] equals [minutes], and no other credit has already named that
 *   purchase (see [RewardGrant]).
 * - **Manual** ([grantedBy] null): a parent grant outside the shop; parent only ([isParentOnly]) and subject to
 *   [LedgerPolicy.requireParentSignature].
 *
 * @property grantedBy the eventId of the [RewardPurchased] this credit redeems, or null for a manual grant.
 * @property rewardId the catalogue reward the minutes are for.
 */
@Serializable
@SerialName("time_bank_credited")
data class TimeBankCredited(
    override val envelope: Envelope,
    val minutes: Money,
    val grantedBy: EventId?,
    val rewardId: String,
) : MovesValue {
    override val amount: Money get() = minutes
    override val currency: Currency get() = Currency.Minutes
    override val postings: List<Posting> get() = credit(minutes)

    override fun withEnvelope(envelope: Envelope) = copy(envelope = envelope)
}

/**
 * A parent covers an overdraft that offline debits caused (ADR-0002, "no-negative invariant ... resolution on
 * merge"); credits [amount] and resolves every [LedgerAnomaly.OverdraftDetected] on the account whose causing
 * events intersect [causedBy]. Parent only.
 *
 * @property note optional free text, at most [LedgerRules.MAX_TEXT_LENGTH] characters; redacted by [toString].
 */
@Serializable
@SerialName("overdraft_compensated")
data class OverdraftCompensated(
    override val envelope: Envelope,
    override val currency: Currency,
    override val amount: Money,
    val causedBy: List<EventId>,
    val note: String? = null,
) : MovesValue {
    override val postings: List<Posting> get() = credit(amount)

    override fun withEnvelope(envelope: Envelope) = copy(envelope = envelope)

    override fun toString(): String = "OverdraftCompensated(envelope=$envelope, currency=$currency, amount=$amount, " +
        "causedBy=$causedBy, note=${if (note == null) "null" else REDACTED})"
}

/** Stands in for free text in `toString`, so parent-written notes never end up in logs. */
private const val REDACTED = "<redacted>"
