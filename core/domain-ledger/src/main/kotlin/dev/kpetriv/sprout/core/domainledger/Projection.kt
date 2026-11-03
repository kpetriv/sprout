package dev.kpetriv.sprout.core.domainledger

import dev.kpetriv.sprout.core.model.AccountId
import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.model.UserId
import kotlin.time.Instant

/** Folds event streams into a [Projection]. */
object Ledger {
    /**
     * Projects [events] (any order, duplicates allowed) by folding them in [LedgerOrdering]. A pure function:
     * any permutation of the same events gives an equal [Projection]. **Never throws**, whatever the input:
     * merged-stream problems, broken rules and arithmetic overflow are reported in [Projection.anomalies].
     *
     * Duplicates are judged by content ([isSameEventAs]): a second copy of the same event is ignored, while
     * different events claiming one eventId are a [LedgerAnomaly.ConflictingDuplicate]. Of those, only
     * [appliedCopyOf] is folded (at its own place in the order): a PARENT device's copy over KID copies, otherwise
     * the first by `(lamport, authorDeviceId, hash)`.
     *
     * Events must already have passed chain and signature verification; this function does not check hashes or
     * verify parent signatures (with [LedgerPolicy.requireParentSignature] it only checks that parent-only events
     * carry one).
     *
     * @param accounts registered accounts, used for currency, ownership and kind checks.
     * @param roleOf the registered role of an author device ([DeviceRegistry]), used only to resolve conflicting
     *   duplicates ([appliedCopyOf]); null when the device is unknown. The default knows no device, so of
     *   conflicting copies the first by `(lamport, authorDeviceId, hash)` is applied.
     * @param trustEnvelopeRoles when [roleOf] returns null for a device, use the copy's self-declared
     *   [Envelope.authorRole] instead. Only for single-household fixtures and tests whose events never left this
     *   device (the in-memory repository, M1/M2 tests); a projection over pulled events must leave it false.
     */
    fun project(
        events: List<LedgerEvent>,
        accounts: Collection<Account> = emptyList(),
        policy: LedgerPolicy = LedgerPolicy(),
        roleOf: (DeviceId) -> Role? = { null },
        trustEnvelopeRoles: Boolean = false,
    ): Projection {
        val copies = LinkedHashMap<EventId, List<LedgerEvent>>()
        for (event in events) {
            val known = copies[event.eventId].orEmpty()
            if (known.none { it.isSameEventAs(event) }) copies[event.eventId] = known + event
        }
        // At most one copy of a conflicting id is folded (chosen from the copies alone), so the order among them
        // does not matter.
        val ordered = copies.values.flatten().sortedWith(LedgerOrdering)
        val builder = ProjectionBuilder(Projection.empty(accounts, policy, roleOf, trustEnvelopeRoles).state, copies)
        ordered.forEach(builder::fold)
        return builder.build()
    }
}

/** Key for [Projection.dailyGameCredits]. */
data class DailyGameKey(val kidId: KidId, val date: CalendarDate, val currency: Currency)

/**
 * A kid's time bank, projected from [TimeBankStarted] and [TimeBankStopped].
 *
 * @property accumulatedMinutes total minutes debited by stopped sessions.
 */
data class TimeBankState(
    val kidId: KidId,
    val accountId: AccountId,
    val running: Boolean,
    val sessionId: String?,
    val startedAt: Instant?,
    val accumulatedMinutes: Money,
)

/** One of the different events that claim the same eventId, as listed in [LedgerAnomaly.ConflictingDuplicate]. */
data class DuplicateCopy(
    val hash: String,
    val kidId: KidId,
    val authorUid: UserId,
    val authorRole: Role,
    val authorDeviceId: DeviceId,
    val lamport: Long,
) {
    companion object {
        fun of(event: LedgerEvent): DuplicateCopy = with(event.envelope) {
            DuplicateCopy(hash, kidId, authorUid, authorRole, authorDeviceId, lamport)
        }

        /** The same tie-break as [appliedCopyOf]: `(lamport, authorDeviceId, hash)`. */
        internal val CONFLICT_ORDER: Comparator<DuplicateCopy> =
            compareBy({ it.lamport }, { it.authorDeviceId.value }, { it.hash })

        internal val ORDER: Comparator<DuplicateCopy> = compareBy(
            { it.hash },
            { it.authorDeviceId.value },
            { it.lamport },
            { it.authorUid.value },
            { it.authorRole },
            { it.kidId.value },
        )
    }
}

/** Something in a merged stream a parent should look at. Anomalies never stop a projection. */
sealed interface LedgerAnomaly {
    /**
     * Debits merged from several devices took [accountId] below zero. [shortfall] is the deepest the balance
     * went during the overdraft; [causedBy] lists the debits applied while it was negative, in total order.
     * Resolved by an [OverdraftCompensated] that names any of [causedBy].
     */
    data class OverdraftDetected(val accountId: AccountId, val shortfall: Money, val causedBy: List<EventId>) :
        LedgerAnomaly

    /**
     * A [GamePlayed] would have taken the day's game credits past the cap. The whole event was excluded (never
     * clamped); [excess] is how far over the cap it would have gone.
     */
    data class DailyCapExceeded(
        val kidId: KidId,
        val date: CalendarDate,
        val currency: Currency,
        val eventId: EventId,
        val excess: Money,
    ) : LedgerAnomaly

    /** An event broke a static rule (or overflowed) and was excluded from balances. */
    data class InvalidEvent(val eventId: EventId, val violations: List<Violation>) : LedgerAnomaly

    /**
     * Different events (different hashes or content) claim the same [eventId]. [copies] lists every distinct copy
     * with its hash and author, sorted by hash, so a parent can see who reused the id. Whatever order they arrived
     * in, only [applied] reaches the balances ([appliedCopyOf]): a PARENT device's copy over KID copies (by
     * registered role), otherwise the first by `(lamport, authorDeviceId, hash)`.
     *
     * @property applied the copy applied to balances (null only when [copies] is empty). A projection always sets
     *   it from the registered roles it was built with. The default (each copy's self-declared
     *   [DuplicateCopy.authorRole], the answer of a projection with `trustEnvelopeRoles = true` and no registry)
     *   exists only for decoders of anomalies persisted without it.
     */
    data class ConflictingDuplicate(
        val eventId: EventId,
        val copies: List<DuplicateCopy>,
        val applied: DuplicateCopy? = resolveConflict(copies, { it.authorRole }, DuplicateCopy.CONFLICT_ORDER),
    ) : LedgerAnomaly
}

/**
 * Balances, time banks and daily game totals folded from the ledger. Immutable; [apply] returns a new projection.
 * Cached projections are never the source of truth (ADR-0002).
 *
 * A projection keeps the events it folded, so [apply] is always exact: it folds incrementally when the new event
 * sorts after everything applied (see [isInOrder]) and otherwise rebuilds with [Ledger.project]. Re-applying the
 * same event is a no-op; a different event under a known eventId makes the copies conflicting (see
 * [appliedCopyOf] for which, if any, still applies).
 */
class Projection internal constructor(internal val state: ProjectionState) {
    val policy: LedgerPolicy get() = state.policy
    val accounts: Map<AccountId, Account> get() = state.accounts
    val balances: Map<AccountId, Balance> get() = state.balances
    val timeBanks: Map<KidId, TimeBankState> get() = state.timeBanks
    val dailyGameCredits: Map<DailyGameKey, Money> get() = state.dailyGameCredits
    val anomalies: List<LedgerAnomaly> get() = state.anomalies

    /** Every applied screen-time purchase by purchase eventId, and which credit (if any) redeemed it. */
    val rewardGrants: Map<EventId, RewardGrant> get() = state.rewardGrants

    /** Every [PeriodClaim] held by an applied event, and that event's id. */
    val claimedPeriods: Map<PeriodClaim, EventId> get() = state.claimedPeriods

    /** The highest lamport of any event folded in (valid, invalid or conflicting); 0 when empty. */
    val maxLamport: Long get() = state.maxLamport

    /** Every eventId folded in, including invalid and conflicting ones (which were excluded from balances). */
    val appliedEventIds: Set<EventId> get() = state.events.keys

    operator fun contains(eventId: EventId): Boolean = eventId in state.events

    /** The balance of [accountId], or zero if nothing has posted to it. */
    fun balanceOf(accountId: AccountId): Money = balances[accountId]?.amount ?: Money.ZERO

    /** Game credits applied for [kidId] on [date] in [currency]. */
    fun dailyGameCredits(kidId: KidId, date: CalendarDate, currency: Currency): Money =
        dailyGameCredits[DailyGameKey(kidId, date, currency)] ?: Money.ZERO

    fun timeBank(kidId: KidId): TimeBankState? = timeBanks[kidId]

    /**
     * True when [apply] folds [event] incrementally: it is a new eventId that sorts after everything applied, or a
     * copy of an event already applied (a no-op). False for a late event or a conflicting copy, which [apply]
     * handles with a full rebuild.
     */
    fun isInOrder(event: LedgerEvent): Boolean {
        val copies = state.events[event.eventId] ?: return state.lastApplied.let {
            it == null || LedgerOrdering.compare(it, event) < 0
        }
        return copies.any { it.isSameEventAs(event) }
    }

    /** This projection with [event] folded in. Equal to [Ledger.project] of every folded event plus [event]. */
    fun apply(event: LedgerEvent): Projection = apply(listOf(event))

    /** This projection with [events] folded in. Equal to [Ledger.project] of every folded event plus [events]. */
    fun apply(events: Iterable<LedgerEvent>): Projection {
        val added = events.toList()
        val builder = ProjectionBuilder(state, state.events)
        return if (added.all(builder::tryAdd)) {
            builder.build()
        } else {
            Ledger.project(
                state.events.values.flatten() + added,
                accounts.values,
                policy,
                state.roleOf,
                state.trustEnvelopeRoles,
            )
        }
    }

    /** Equal when the folded content is equal; which re-signed copy of an event was kept does not matter. */
    override fun equals(other: Any?): Boolean = other is Projection && contentKey() == other.contentKey()

    override fun hashCode(): Int = contentKey().hashCode()

    private fun contentKey(): List<Any> = listOf(
        state.policy,
        state.accounts,
        state.balances,
        state.timeBanks,
        state.dailyGameCredits,
        state.anomalies,
        state.rewardGrants,
        state.claimedPeriods,
        state.openOverdrafts,
        state.maxLamport,
        state.events.mapValues { (_, copies) -> copies.map { it.envelope.hash }.toSet() },
    )

    override fun toString(): String =
        "Projection(balances=${balances.values}, timeBanks=${timeBanks.values}, anomalies=$anomalies)"

    companion object {
        /** An empty projection; [roleOf] and [trustEnvelopeRoles] as in [Ledger.project], kept for every [apply]. */
        fun empty(
            accounts: Collection<Account> = emptyList(),
            policy: LedgerPolicy = LedgerPolicy(),
            roleOf: (DeviceId) -> Role? = { null },
            trustEnvelopeRoles: Boolean = false,
        ): Projection = Projection(
            ProjectionState(
                policy = policy,
                roleOf = roleOf,
                trustEnvelopeRoles = trustEnvelopeRoles,
                accounts = accounts.associateBy { it.id },
                balances = emptyMap(),
                timeBanks = emptyMap(),
                dailyGameCredits = emptyMap(),
                anomalies = emptyList(),
                rewardGrants = emptyMap(),
                claimedPeriods = emptyMap(),
                events = emptyMap(),
                openOverdrafts = emptySet(),
                lastApplied = null,
                maxLamport = 0,
            ),
        )
    }
}

/** The anomalies of [this] projection about [kidId]'s accounts or events. */
fun Projection.anomaliesFor(kidId: KidId): List<LedgerAnomaly> {
    fun concerns(eventId: EventId) = state.events[eventId].orEmpty().any { it.envelope.kidId == kidId }
    return anomalies.filter { anomaly ->
        when (anomaly) {
            is LedgerAnomaly.OverdraftDetected ->
                accounts[anomaly.accountId]?.kidId == kidId || anomaly.causedBy.any(::concerns)

            is LedgerAnomaly.DailyCapExceeded -> anomaly.kidId == kidId

            is LedgerAnomaly.InvalidEvent -> concerns(anomaly.eventId)

            is LedgerAnomaly.ConflictingDuplicate -> anomaly.copies.any { it.kidId == kidId }
        }
    }
}

/**
 * Everything a [Projection] holds.
 *
 * @property events every distinct copy folded in, by eventId; more than one copy means a conflict.
 * @property openOverdrafts accounts whose latest [LedgerAnomaly.OverdraftDetected] is still open (the balance has
 *   not been back to zero or above since). The open anomaly is always the last one for its account.
 * @property roleOf registered author-device roles, and [trustEnvelopeRoles] whether to fall back to the envelope's
 *   role; see [Ledger.project].
 */
internal data class ProjectionState(
    val policy: LedgerPolicy,
    val roleOf: (DeviceId) -> Role?,
    val trustEnvelopeRoles: Boolean,
    val accounts: Map<AccountId, Account>,
    val balances: Map<AccountId, Balance>,
    val timeBanks: Map<KidId, TimeBankState>,
    val dailyGameCredits: Map<DailyGameKey, Money>,
    val anomalies: List<LedgerAnomaly>,
    val rewardGrants: Map<EventId, RewardGrant>,
    val claimedPeriods: Map<PeriodClaim, EventId>,
    val events: Map<EventId, List<LedgerEvent>>,
    val openOverdrafts: Set<AccountId>,
    val lastApplied: LedgerEvent?,
    val maxLamport: Long,
)

/** One posting's effect, computed (with every overflow check) before anything is written. */
private class BalanceUpdate(val posting: Posting, val before: Money, val after: Money) {
    /** How far below zero [after] is; zero when it is not negative. */
    val shortfall: Money = if (after.isNegative) -after else Money.ZERO
}

/**
 * Mutable working copy used to fold many events without copying maps per event. Each event is folded atomically:
 * all arithmetic (which may throw [ArithmeticException]) happens before the first write, and an overflow turns
 * the event into [LedgerAnomaly.InvalidEvent] with [Violation.Overflow].
 *
 * @param copies every distinct copy per eventId. For a full rebuild it holds the whole input up front, so a
 *   conflicting id is known before its first copy is folded.
 */
private class ProjectionBuilder(private val base: ProjectionState, copies: Map<EventId, List<LedgerEvent>>) {
    private val balances = LinkedHashMap(base.balances)
    private val timeBanks = LinkedHashMap(base.timeBanks)
    private val dailyGame = LinkedHashMap(base.dailyGameCredits)
    private val anomalies = ArrayList(base.anomalies)
    private val grants = LinkedHashMap(base.rewardGrants)
    private val claims = LinkedHashMap(base.claimedPeriods)
    private val events = LinkedHashMap(copies)
    private val openOverdrafts = LinkedHashSet(base.openOverdrafts)
    private val reportedConflicts = HashSet<EventId>()
    private var lastApplied = base.lastApplied
    private var maxLamport = base.maxLamport

    /**
     * Folds [event] incrementally if that is exact. Returns false, changing nothing, when the caller must rebuild
     * instead: [event] conflicts with a folded copy of its id, or sorts before the last folded event.
     */
    fun tryAdd(event: LedgerEvent): Boolean {
        val known = events[event.eventId]
        val last = lastApplied
        return when {
            known != null -> known.any { it.isSameEventAs(event) }

            last != null && LedgerOrdering.compare(last, event) >= 0 -> false

            else -> {
                events[event.eventId] = listOf(event)
                fold(event)
                true
            }
        }
    }

    /** Folds [event], whose copies are already recorded in [events]. */
    fun fold(event: LedgerEvent) {
        maxLamport = maxOf(maxLamport, event.lamport)
        if (lastApplied.let { it == null || LedgerOrdering.compare(it, event) < 0 }) lastApplied = event
        val copies = events[event.eventId].orEmpty()
        if (copies.size > 1) {
            val applied = resolveConflict(copies, ::roleOf, CONFLICT_ORDER)
            reportConflict(event.eventId, copies, applied)
            if (applied?.isSameEventAs(event) != true) return
        }
        val violations =
            LedgerRules.staticViolations(event, base.accounts, balances, requireKnownAccounts = false, base.policy)
                .ifEmpty { LedgerRules.historyViolations(event, grants, claims) }
        if (violations.isNotEmpty()) {
            anomalies += LedgerAnomaly.InvalidEvent(event.eventId, violations)
        } else {
            try {
                if (event is GamePlayed) applyGame(event) else applyValid(event)
            } catch (expected: ArithmeticException) {
                anomalies += LedgerAnomaly.InvalidEvent(event.eventId, listOf(Violation.Overflow))
            }
        }
    }

    fun build(): Projection = Projection(
        base.copy(
            balances = balances,
            timeBanks = timeBanks,
            dailyGameCredits = dailyGame,
            anomalies = anomalies,
            rewardGrants = grants,
            claimedPeriods = claims,
            events = events,
            openOverdrafts = openOverdrafts,
            lastApplied = lastApplied,
            maxLamport = maxLamport,
        ),
    )

    private fun reportConflict(eventId: EventId, copies: List<LedgerEvent>, applied: LedgerEvent?) {
        if (reportedConflicts.add(eventId)) {
            val listed = copies.map(DuplicateCopy::of).sortedWith(DuplicateCopy.ORDER)
            anomalies += LedgerAnomaly.ConflictingDuplicate(eventId, listed, applied?.let(DuplicateCopy::of))
        }
    }

    /**
     * The role [appliedCopyOf] judges a copy by: its author device's registered role, falling back to the copy's
     * self-declared role only when the projection was built with `trustEnvelopeRoles`.
     */
    private fun roleOf(copy: LedgerEvent): Role? = base.roleOf(copy.envelope.authorDeviceId)
        ?: copy.envelope.authorRole.takeIf { base.trustEnvelopeRoles }

    /** Plans every posting of [event]; throws [ArithmeticException] on overflow, before anything is written. */
    private fun plan(event: LedgerEvent): List<BalanceUpdate> = event.postings.map { posting ->
        val before = balances[posting.accountId]?.amount ?: Money.ZERO
        BalanceUpdate(posting, before, before + posting.delta)
    }

    /** A game is credited whole if it fits under the day's cap, and excluded whole if it does not (M5). */
    private fun applyGame(event: GamePlayed) {
        val key = DailyGameKey(event.envelope.kidId, event.playedOn, event.currency)
        val credited = dailyGame[key] ?: Money.ZERO
        // credited never exceeds the cap and both are non-negative, so this cannot overflow.
        val remaining = base.policy.dailyGameCapFor(event.currency) - credited
        if (event.amount > remaining) {
            anomalies += LedgerAnomaly.DailyCapExceeded(
                kidId = key.kidId,
                date = key.date,
                currency = key.currency,
                eventId = event.eventId,
                excess = event.amount - remaining,
            )
            return
        }
        val updates = plan(event)
        dailyGame[key] = credited + event.amount
        updates.forEach { commit(event, it) }
    }

    private fun applyValid(event: LedgerEvent) {
        val updates = plan(event)
        val stoppedMinutes = (event as? TimeBankStopped)?.let {
            (timeBanks[it.envelope.kidId]?.accumulatedMinutes ?: Money.ZERO) + it.minutesUsed
        }
        val openBefore = openOverdraftIndex(event.envelope.accountId)
        // Nothing below can overflow.
        updates.forEach { commit(event, it) }
        when (event) {
            is TimeBankStarted -> startTimeBank(event)
            is TimeBankStopped -> stopTimeBank(event, stoppedMinutes ?: event.minutesUsed)
            is OverdraftCompensated -> resolveOverdraft(event, openBefore, updates.single())
            is RewardPurchased -> recordGrant(event)
            is TimeBankCredited -> redeemGrant(event)
            else -> Unit
        }
        event.periodClaim()?.let { claims[it] = event.eventId }
    }

    private fun recordGrant(event: RewardPurchased) {
        val minutes = event.minutesGranted ?: return
        grants[event.eventId] = RewardGrant(event.eventId, event.envelope.kidId, event.rewardId, minutes, null)
    }

    /** Only reached for a valid credit, so a referenced grant exists and is still open. */
    private fun redeemGrant(event: TimeBankCredited) {
        val purchaseId = event.grantedBy ?: return
        grants[purchaseId] = grants.getValue(purchaseId).copy(creditedBy = event.eventId)
    }

    private fun commit(event: LedgerEvent, update: BalanceUpdate) {
        val id = update.posting.accountId
        val previous = balances[id]
        balances[id] = Balance(
            accountId = id,
            currency = event.currency,
            amount = update.after,
            asOf = event.envelope.createdAt,
            lastLamport = maxOf(previous?.lastLamport ?: event.lamport, event.lamport),
        )
        when {
            !update.after.isNegative -> openOverdrafts -= id
            update.posting.delta.isNegative -> recordOverdraft(id, update, event.eventId)
        }
    }

    /** Index in [anomalies] of the open overdraft on [accountId], or -1. */
    private fun openOverdraftIndex(accountId: AccountId): Int = if (accountId in openOverdrafts) {
        anomalies.indexOfLast { it is LedgerAnomaly.OverdraftDetected && it.accountId == accountId }
    } else {
        -1
    }

    private fun recordOverdraft(accountId: AccountId, update: BalanceUpdate, cause: EventId) {
        val openIndex = if (update.before.isNegative) openOverdraftIndex(accountId) else -1
        if (openIndex >= 0) {
            val open = anomalies[openIndex] as LedgerAnomaly.OverdraftDetected
            anomalies[openIndex] =
                open.copy(shortfall = maxOf(open.shortfall, update.shortfall), causedBy = open.causedBy + cause)
        } else {
            anomalies += LedgerAnomaly.OverdraftDetected(accountId, update.shortfall, listOf(cause))
            openOverdrafts += accountId
        }
    }

    /**
     * Resolves the overdraft anomalies on the account that [event] names (M1):
     * - a closed one (the balance already recovered) is removed: the parent has acknowledged it;
     * - the one that was open before this credit is removed if the credit cleared it, or kept open with the
     *   remaining shortfall if the account is still short. It is never re-created or duplicated.
     * An open anomaly that [event] does not name is left alone (the credit may still have closed it).
     *
     * @param openBefore index of the anomaly that was open before [event]'s credit, or -1.
     */
    private fun resolveOverdraft(event: OverdraftCompensated, openBefore: Int, credit: BalanceUpdate) {
        val accountId = event.envelope.accountId
        val named = event.causedBy.toSet()
        val iterator = anomalies.listIterator()
        var originalIndex = -1
        while (iterator.hasNext()) {
            val anomaly = iterator.next()
            originalIndex += 1
            val matches = anomaly is LedgerAnomaly.OverdraftDetected &&
                anomaly.accountId == accountId &&
                anomaly.causedBy.any(named::contains)
            when {
                !matches -> Unit

                originalIndex == openBefore && credit.after.isNegative ->
                    iterator.set((anomaly as LedgerAnomaly.OverdraftDetected).copy(shortfall = credit.shortfall))

                else -> iterator.remove()
            }
        }
    }

    private fun startTimeBank(event: TimeBankStarted) {
        val kidId = event.envelope.kidId
        val previous = timeBanks[kidId]
        timeBanks[kidId] = TimeBankState(
            kidId = kidId,
            accountId = event.envelope.accountId,
            running = true,
            sessionId = event.sessionId,
            startedAt = event.envelope.createdAt,
            accumulatedMinutes = previous?.accumulatedMinutes ?: Money.ZERO,
        )
    }

    /** A stop without a matching start (merged offline stream) still records the minutes used. */
    private fun stopTimeBank(event: TimeBankStopped, accumulatedMinutes: Money) {
        val kidId = event.envelope.kidId
        timeBanks[kidId] = TimeBankState(
            kidId = kidId,
            accountId = event.envelope.accountId,
            running = false,
            sessionId = null,
            startedAt = null,
            accumulatedMinutes = accumulatedMinutes,
        )
    }
}
