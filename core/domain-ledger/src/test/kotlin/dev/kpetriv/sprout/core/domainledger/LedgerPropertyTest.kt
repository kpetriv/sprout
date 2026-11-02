package dev.kpetriv.sprout.core.domainledger

import dev.kpetriv.sprout.core.model.AccountId
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.Role
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Property-style tests over randomly generated multi-device histories (seeded, so failures reproduce: the seed is
 * in every assertion message). Results are checked against [ReferenceLedger], a deliberately naive model that
 * shares no folding code with [Projection], and against what the generator knows it wrote (which events break a
 * rule, which ids it reused or forged).
 */
class LedgerPropertyTest {
    private val seeds = 0 until 150

    @Test
    fun projectionMatchesTheReferenceLedger() = forAllHistories { seed, history, _ ->
        assertMatchesReference(seed, history, Ledger.project(history.events, Fixtures.accounts))
    }

    @Test
    fun anyPermutationProjectsToTheSameResult() = forAllHistories { seed, history, random ->
        val expected = Ledger.project(history.events, Fixtures.accounts)
        repeat(3) {
            assertEquals(expected, Ledger.project(history.events.shuffled(random), Fixtures.accounts), "seed $seed")
        }
    }

    @Test
    fun incrementalFoldingInAnyArrivalOrderMatchesTheReference() = forAllHistories { seed, history, random ->
        for (arrival in listOf(history.events.sortedWith(LedgerOrdering), history.events.shuffled(random))) {
            val incremental = arrival.fold(Projection.empty(Fixtures.accounts)) { projection, event ->
                projection.apply(event)
            }
            assertMatchesReference(seed, history, incremental)
        }
    }

    @Test
    fun foldingInBatchesMatchesTheReference() = forAllHistories { seed, history, random ->
        val arrival = history.events.shuffled(random)
        val split = random.nextInt(arrival.size + 1)
        val stepwise = Projection.empty(Fixtures.accounts).apply(arrival.take(split)).apply(arrival.drop(split))
        assertMatchesReference(seed, history, stepwise)
    }

    @Test
    fun redeliveredAndReSignedEventsChangeNothing() = forAllHistories { seed, history, random ->
        val replays = history.events.filter { random.nextBoolean() }.map { it.withSignature(random.nextBytes(8)) }
        val projection = Ledger.project((history.events + replays + history.events).shuffled(random), Fixtures.accounts)
        assertMatchesReference(seed, history, projection)
        assertEquals(Ledger.project(history.events, Fixtures.accounts), projection, "seed $seed")
    }

    @Test
    fun shuffleThenSortRestoresTheTotalOrder() = forAllHistories { seed, history, random ->
        val sorted = history.events.sortedWith(LedgerOrdering.thenBy { it.envelope.hash })
        assertEquals(sorted, history.events.shuffled(random).sortedWith(LedgerOrdering.thenBy { it.envelope.hash }))
        val set = LedgerEventSet.of(history.events.shuffled(random))
        assertEquals(sorted, set.sorted(), "seed $seed")
        assertEquals(history.conflicted, set.conflictingIds, "seed $seed")
    }

    @Test
    fun everyAuthorChainVerifies() = forAllHistories { seed, history, _ ->
        history.chained.groupBy { it.envelope.authorDeviceId }.values.forEach { stream ->
            assertIs<ChainVerification.Valid>(HashChain().verifyChain(stream.sortedBy { it.lamport }), "seed $seed")
        }
    }

    @Test
    fun generatedHistoriesCoverEveryAnomalyType() {
        val histories = seeds.map { StreamGenerator(it).generate(40) }
        val kinds = histories.flatMap { Ledger.project(it.events, Fixtures.accounts).anomalies.map { a -> a::class } }
        assertEquals(
            setOf(
                LedgerAnomaly.OverdraftDetected::class,
                LedgerAnomaly.DailyCapExceeded::class,
                LedgerAnomaly.InvalidEvent::class,
                LedgerAnomaly.ConflictingDuplicate::class,
            ),
            kinds.toSet(),
        )
        // Both kinds of conflict occur: ids reused across authors, and forged copies of one author's event.
        val conflicts = histories.flatMap { history ->
            Ledger.project(
                history.events,
                Fixtures.accounts,
            ).anomalies.filterIsInstance<LedgerAnomaly.ConflictingDuplicate>()
        }
        assertTrue(conflicts.any { c -> c.copies.map { it.authorDeviceId }.toSet().size > 1 })
        assertTrue(conflicts.any { c -> c.copies.map { it.authorDeviceId }.toSet().size == 1 })
        assertTrue(conflicts.any { c -> c.copies.map { it.authorRole }.toSet() == setOf(Role.PARENT, Role.KID) })
        // Both outcomes occur: a parent copy applied over kid copies, and nothing applied.
        assertTrue(conflicts.any { it.applied != null })
        assertTrue(conflicts.any { it.applied == null })
    }

    private fun assertMatchesReference(seed: Int, history: GeneratedHistory, projection: Projection) {
        val expected = ReferenceLedger.fold(history, Fixtures.accounts.map { it.id }, LedgerPolicy())
        for (account in Fixtures.accounts) {
            assertEquals(
                Money(expected.balances.getValue(account.id)),
                projection.balanceOf(account.id),
                "seed $seed account ${account.id}",
            )
        }
        assertEquals(
            expected.gameCredits.mapValues { Money(it.value) },
            projection.dailyGameCredits,
            "seed $seed game credits",
        )
        assertEquals(
            Money(expected.minutesUsed[Fixtures.kid] ?: 0),
            projection.timeBank(Fixtures.kid)?.accumulatedMinutes ?: Money.ZERO,
            "seed $seed minutes",
        )

        val anomalies = projection.anomalies
        assertEquals(
            history.conflicted,
            anomalies.filterIsInstance<LedgerAnomaly.ConflictingDuplicate>().map { it.eventId }.toSet(),
            "seed $seed conflicts",
        )
        for (conflict in anomalies.filterIsInstance<LedgerAnomaly.ConflictingDuplicate>()) {
            val copies = history.events.filter { it.eventId == conflict.eventId }.map { it.envelope.hash }.toSet()
            assertEquals(copies, conflict.copies.map { it.hash }.toSet(), "seed $seed copies of ${conflict.eventId}")
        }
        assertEquals(
            expected.invalid,
            anomalies.filterIsInstance<LedgerAnomaly.InvalidEvent>().map { it.eventId }.toSet(),
            "seed $seed invalid",
        )
        assertEquals(
            expected.overCap,
            anomalies.filterIsInstance<LedgerAnomaly.DailyCapExceeded>().map { it.eventId }.toSet(),
            "seed $seed over cap",
        )
        // A balance that ends below zero always has an open overdraft at least that deep.
        for ((accountId, balance) in expected.balances.filterValues { it < 0 }) {
            assertTrue(
                anomalies.any {
                    it is LedgerAnomaly.OverdraftDetected && it.accountId == accountId &&
                        it.shortfall.minorUnits >= -balance
                },
                "seed $seed overdraft on $accountId",
            )
        }
    }

    private fun forAllHistories(check: (seed: Int, history: GeneratedHistory, random: Random) -> Unit) {
        for (seed in seeds) {
            val random = Random(seed)
            check(seed, StreamGenerator(seed).generate(random.nextInt(1, 40)), random)
        }
    }
}

/**
 * A naive model of the ledger rules, written independently of [Projection]: no builder, no incremental state, plain
 * `Long` sums. It trusts the generator for which events are statically invalid, and models only what the
 * generator produces.
 *
 * Conflicting ids: of the distinct copies of one id, the single parent copy is applied when every other copy is a
 * kid's; otherwise no copy is.
 */
internal object ReferenceLedger {
    class Expected(
        val balances: Map<AccountId, Long>,
        val gameCredits: Map<DailyGameKey, Long>,
        val minutesUsed: Map<KidId, Long>,
        val overCap: Set<EventId>,
        val invalid: Set<EventId>,
    )

    fun fold(history: GeneratedHistory, accounts: List<AccountId>, policy: LedgerPolicy): Expected {
        val chosen = history.events.groupBy { it.eventId }.values.mapNotNull { winner(it.distinct()) }
        val (invalid, valid) = chosen.partition { it.envelope.hash in history.invalidHashes }
        val applied = valid
            .sortedWith(compareBy({ it.envelope.lamport }, { it.envelope.authorDeviceId.value }, { it.eventId.value }))
        val balances = accounts.associateWith { 0L }.toMutableMap()
        val games = mutableMapOf<DailyGameKey, Long>()
        val minutes = mutableMapOf<KidId, Long>()
        val overCap = mutableSetOf<EventId>()
        for (event in applied) {
            if (event is GamePlayed) {
                val key = DailyGameKey(event.envelope.kidId, event.playedOn, event.currency)
                val sum = games[key] ?: 0L
                if (sum + event.amount.minorUnits > policy.dailyGameCapFor(event.currency).minorUnits) {
                    overCap += event.eventId
                    continue
                }
                games[key] = sum + event.amount.minorUnits
            }
            if (event is TimeBankStopped) {
                minutes[event.envelope.kidId] = (minutes[event.envelope.kidId] ?: 0L) + event.minutesUsed.minorUnits
            }
            for ((account, delta) in deltas(event)) balances[account] = (balances[account] ?: 0L) + delta
        }
        return Expected(balances, games, minutes, overCap, invalid.map { it.eventId }.toSet())
    }

    private fun winner(copies: List<LedgerEvent>): LedgerEvent? {
        val parents = copies.filter { it.envelope.authorRole == Role.PARENT }
        val kids = copies.filter { it.envelope.authorRole == Role.KID }
        return when {
            copies.size == 1 -> copies[0]
            parents.size == 1 && kids.size == copies.size - 1 -> parents[0]
            else -> null
        }
    }

    private fun deltas(event: LedgerEvent): List<Pair<AccountId, Long>> {
        val account = event.envelope.accountId
        return when (event) {
            is ChoreRewarded -> listOf(account to event.amount.minorUnits)

            is AllowancePaid -> listOf(account to event.amount.minorUnits)

            is GamePlayed -> listOf(account to event.amount.minorUnits)

            is ManualAdjustment -> listOf(account to event.amount.minorUnits)

            is InterestAccrued -> listOf(account to event.amount.minorUnits)

            is OverdraftCompensated -> listOf(account to event.amount.minorUnits)

            is RewardPurchased -> listOf(account to -event.amount.minorUnits)

            is TimeBankStopped -> listOf(account to -event.minutesUsed.minorUnits)

            is TransferToSavings -> listOf(
                account to -event.amount.minorUnits,
                event.toAccountId to event.amount.minorUnits,
            )

            is TimeBankStarted -> emptyList()
        }
    }
}

/**
 * A generated history and what the generator knows about it.
 *
 * @property events everything delivered: every device's chained stream plus forged copies.
 * @property chained only what devices wrote through their own chains (verifies per author).
 * @property invalidHashes hashes of events written to break a static rule (per copy: a reused id can have one
 *   valid and one invalid copy).
 * @property conflicted ids claimed by more than one distinct event (reused ids and forged copies).
 */
internal class GeneratedHistory(
    val events: List<LedgerEvent>,
    val chained: List<LedgerEvent>,
    val invalidHashes: Set<String>,
    val conflicted: Set<EventId>,
)

/**
 * Generates a household history: two parent devices and a kid device writing while mostly offline, with
 * occasional syncs that merge lamport clocks. Includes events that later trip every anomaly: overdrafts, games over
 * the cap, forged parent-only events, ids reused across authors, and forged copies of existing events.
 */
internal class StreamGenerator(seed: Int) {
    private val random = Random(seed)
    private val devices = listOf(
        DeviceWriter(Fixtures.parentPhone, seed = seed * 3),
        DeviceWriter(Fixtures.parentTablet, seed = seed * 3 + 1),
        DeviceWriter(Fixtures.kidTablet, seed = seed * 3 + 2),
    )
    private var session: String? = null
    private val dates = listOf(Fixtures.today, Fixtures.today.copy(day = 3))
    private val invalid = mutableSetOf<String>()
    private val conflicted = mutableSetOf<EventId>()
    private val forged = mutableListOf<LedgerEvent>()

    fun generate(count: Int): GeneratedHistory {
        repeat(count) {
            if (random.nextInt(4) == 0) sync()
            when {
                random.nextInt(20) == 0 -> forgeCopy()

                else -> devices.random(random).let {
                    if (it.author.role ==
                        Role.PARENT
                    ) {
                        parentWrites(it)
                    } else {
                        kidWrites(it)
                    }
                }
            }
        }
        val chained = devices.flatMap { it.written }
        return GeneratedHistory(chained + forged, chained, invalid.toSet(), conflicted.toSet())
    }

    private fun written() = devices.flatMap { it.written }

    private fun sync() {
        val (a, b) = devices.shuffled(random).take(2)
        val high = maxOf(a.lamport.current, b.lamport.current)
        a.observe(high)
        b.observe(high)
    }

    private fun amount(max: Int = 300) = random.nextLong(1, max.toLong())

    private fun parentWrites(device: DeviceWriter) {
        when (random.nextInt(9)) {
            0 -> device.chore(amount())

            1 -> device.allowance(amount(600))

            2 -> device.adjust(if (random.nextBoolean()) amount() else -amount())

            3 -> device.interest(amount(20))

            4 -> device.chore(amount(60), Fixtures.minutes)

            5 -> device.purchase(amount())

            6 -> {
                val causes = written().shuffled(random).take(2).map { it.eventId }
                device.compensate(amount(), causes).also { if (causes.isEmpty()) invalid += it.envelope.hash }
            }

            7 -> reuseId(device, Role.KID) { device.chore(amount()) }

            else -> invalid += device.adjust(0).envelope.hash
        }
    }

    private fun kidWrites(device: DeviceWriter) {
        when (random.nextInt(8)) {
            0, 1 -> device.game(amount(40), dates.random(random))

            2 -> device.purchase(amount())

            3 -> device.transfer(amount(200))

            4 -> session?.let { device.timeStop(it, amount(30)).also { session = null } }
                ?: device.timeStart("s${random.nextInt()}").also { session = it.sessionId }

            5 -> device.game(amount(40), account = Fixtures.minutes)

            6 -> reuseId(device, Role.PARENT) { device.purchase(amount()) }

            else -> invalid += device.chore(amount()).envelope.hash // forged parent-only event
        }
    }

    /**
     * [device] writes a correctly chained event that reuses the id of an event written by a [victimRole] device;
     * falls back to [otherwise] when there is none yet.
     */
    private fun reuseId(device: DeviceWriter, victimRole: Role, otherwise: () -> LedgerEvent) {
        // A device never reuses an id twice itself: that would break its own chain (a replay), not just conflict.
        val own = device.written.map { it.eventId }.toSet()
        val victim = written().filter {
            it.envelope.authorRole == victimRole && it.eventId !in own
        }.randomOrNull(random)
        if (victim == null) {
            otherwise()
            return
        }
        device.writeWithId(victim.eventId, Fixtures.main) {
            if (device.author.role == Role.PARENT) {
                ChoreRewarded(it, Fixtures.gbp, Money(amount()), "reused")
            } else {
                GamePlayed(it, Fixtures.gbp, Money(amount(40)), "reused", 1, Fixtures.today)
            }
        }
        conflicted += victim.eventId
    }

    /** A tampered copy of an existing event that kept its id but carries a different, self-consistent hash. */
    private fun forgeCopy() {
        val original = written().randomOrNull(random) ?: return
        // Shift by a different amount each time so two forgeries of one event are still different events.
        val edited = original.mutateEnvelope { it.copy(createdAt = it.createdAt + (forged.size + 1).seconds) }
        val copy = edited.mutateEnvelope { it.copy(hash = HashChain().hashOf(edited)) }
        forged += copy
        // A forged copy breaks the same rules as its original.
        if (original.envelope.hash in invalid) invalid += copy.envelope.hash
        conflicted += original.eventId
    }
}
