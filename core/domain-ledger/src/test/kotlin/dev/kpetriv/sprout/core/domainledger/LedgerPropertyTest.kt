package dev.kpetriv.sprout.core.domainledger

import dev.kpetriv.sprout.core.model.AccountId
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.Role
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Property-style tests over randomly generated multi-device histories (seeded, so failures reproduce: the seed is
 * in every assertion message). Results are checked against [ReferenceLedger], a deliberately naive model that
 * shares no folding code with [Projection], and against what the generator knows it wrote (which events break a
 * rule, which ids it reused or forged).
 */
class LedgerPropertyTest {
    private val seeds = 0 until 150

    // Every generated device is a fixture device whose registered role matches its envelopes, so registry roles
    // and the reference model's envelope roles agree. The history's server stamps go with its events.
    private fun project(
        history: GeneratedHistory,
        events: List<LedgerEvent> = history.events,
        policy: LedgerPolicy = POLICY,
    ) = Ledger.project(events, Fixtures.accounts, policy, Fixtures::roleOf, receivedAt = history.receivedAt)

    private fun emptyProjection(history: GeneratedHistory) =
        Projection.empty(Fixtures.accounts, POLICY, roleOf = Fixtures::roleOf, receivedAt = history.receivedAt)

    @Test
    fun projectionMatchesTheReferenceLedger() = forAllHistories { seed, history, _ ->
        assertMatchesReference(seed, history, project(history))
    }

    @Test
    fun requiredParentSignaturesMatchTheReference() {
        val required = POLICY.copy(requireParentSignature = true)
        for (seed in seeds) {
            // Unsigned parent devices: every parent-only event is excluded.
            val unsigned = StreamGenerator(seed).generate(Random(seed).nextInt(1, 40))
            assertMatchesReference(
                seed,
                unsigned,
                project(unsigned, policy = required),
                required,
            )
            // A cut-off lamport: only unsigned parent-only events at or above it are excluded.
            val cutOff = required.copy(parentSignatureRequiredFromLamport = Random(seed).nextLong(0, 40))
            assertMatchesReference(
                seed,
                unsigned,
                project(unsigned, policy = cutOff),
                cutOff,
            )
            // Signing parent devices: the policy changes nothing.
            val signed = StreamGenerator(seed, FakeParentAuthorization()).generate(Random(seed).nextInt(1, 40))
            val projection = project(signed, policy = required)
            assertMatchesReference(seed, signed, projection, required)
            assertEquals(
                project(signed).balances,
                projection.balances,
                "seed $seed",
            )
        }
    }

    @Test
    fun anyPermutationProjectsToTheSameResult() = forAllHistories { seed, history, random ->
        val expected = project(history)
        repeat(3) {
            assertEquals(expected, project(history, history.events.shuffled(random)), "seed $seed")
        }
    }

    @Test
    fun incrementalFoldingInAnyArrivalOrderMatchesTheReference() = forAllHistories { seed, history, random ->
        for (arrival in listOf(history.events.sortedWith(LedgerOrdering), history.events.shuffled(random))) {
            val incremental = arrival.fold(emptyProjection(history)) { projection, event ->
                projection.apply(event)
            }
            assertMatchesReference(seed, history, incremental)
        }
    }

    @Test
    fun foldingInBatchesMatchesTheReference() = forAllHistories { seed, history, random ->
        val arrival = history.events.shuffled(random)
        val split = random.nextInt(arrival.size + 1)
        val stepwise = emptyProjection(history).apply(arrival.take(split)).apply(arrival.drop(split))
        assertMatchesReference(seed, history, stepwise)
    }

    @Test
    fun redeliveredAndReSignedEventsChangeNothing() = forAllHistories { seed, history, random ->
        val replays = history.events.filter { random.nextBoolean() }.map { it.withSignature(random.nextBytes(8)) }
        val projection = project(history, (history.events + replays + history.events).shuffled(random))
        assertMatchesReference(seed, history, projection)
        assertEquals(project(history), projection, "seed $seed")
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
        val kinds = histories.flatMap { project(it).anomalies.map { a -> a::class } }
        assertEquals(
            setOf(
                LedgerAnomaly.OverdraftDetected::class,
                LedgerAnomaly.DailyCapExceeded::class,
                LedgerAnomaly.PlayedOnOutOfRange::class,
                LedgerAnomaly.InvalidEvent::class,
                LedgerAnomaly.ConflictingDuplicate::class,
            ),
            kinds.toSet(),
        )
        // Both kinds of conflict occur: ids reused across authors, and forged copies of one author's event.
        val conflicts = histories.flatMap { history ->
            project(history).anomalies.filterIsInstance<LedgerAnomaly.ConflictingDuplicate>()
        }
        assertTrue(conflicts.any { c -> c.copies.map { it.authorDeviceId }.toSet().size > 1 })
        assertTrue(conflicts.any { c -> c.copies.map { it.authorDeviceId }.toSet().size == 1 })
        assertTrue(conflicts.any { c -> c.copies.map { it.authorRole }.toSet() == setOf(Role.PARENT, Role.KID) })
        // A copy is always applied: a parent copy over kid copies, or the first of same-role copies.
        assertTrue(conflicts.all { it.applied != null })
        assertTrue(conflicts.any { c -> c.copies.map { it.authorRole }.toSet().size == 1 })
        // Every history rule, and every game-credit rule, fires somewhere.
        val violations = histories.flatMap { history ->
            project(history).anomalies
                .filterIsInstance<LedgerAnomaly.InvalidEvent>().flatMap { it.violations }
        }.map { it::class }.toSet()
        for (kind in listOf(
            Violation.DuplicatePeriod::class,
            Violation.GrantNotFound::class,
            Violation.GrantMinutesMismatch::class,
            Violation.GrantAlreadyCredited::class,
            Violation.UnknownGame::class,
            Violation.ScoreOutOfRange::class,
            Violation.GameCreditTooHigh::class,
            Violation.GameCurrencyNotAllowed::class,
        )) {
            assertTrue(kind in violations, kind.toString())
        }
        // And screen-time credits are applied too.
        assertTrue(
            histories.any { history ->
                project(history).rewardGrants.values.any { it.creditedBy != null }
            },
        )
    }

    private fun assertMatchesReference(
        seed: Int,
        history: GeneratedHistory,
        projection: Projection,
        policy: LedgerPolicy = POLICY,
    ) {
        val expected = ReferenceLedger.fold(history, Fixtures.accounts.map { it.id }, policy)
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
        assertEquals(expected.history.claims, projection.claimedPeriods, "seed $seed claims")
        assertEquals(
            expected.history.grants,
            projection.rewardGrants.mapValues { (_, grant) -> grant.minutes.minorUnits to grant.creditedBy },
            "seed $seed grants",
        )

        assertAnomaliesMatchReference(seed, history, projection, expected)
    }

    private fun assertAnomaliesMatchReference(
        seed: Int,
        history: GeneratedHistory,
        projection: Projection,
        expected: ReferenceLedger.Expected,
    ) {
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
        assertEquals(
            expected.outOfWindow,
            anomalies.filterIsInstance<LedgerAnomaly.PlayedOnOutOfRange>().map { it.eventId }.toSet(),
            "seed $seed played-on window",
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

    private companion object {
        /** A daily game cap small enough that generated histories go over it (a play earns at most 15). */
        val POLICY = LedgerPolicy(dailyGameCap = Money(10))
    }
}

/**
 * A naive model of the ledger rules, written independently of [Projection]: no builder, no incremental state, plain
 * `Long` sums. It trusts the generator for which events are statically invalid, and models only what the
 * generator produces.
 *
 * Conflicting ids: of the distinct copies of one id, the parent copy that sorts first by `(lamport, authorDeviceId,
 * hash)` is applied, or the first copy overall when none is a parent's. Periods: the first applied event per claim
 * wins, later ones are invalid. Time credits: one that names a purchase is valid only if that purchase was applied
 * earlier for the same kid and reward, granted exactly its minutes, and was not redeemed yet. With a policy that
 * requires parent signatures, parent-only events without one are invalid as well.
 */
internal object ReferenceLedger {
    @Suppress("LongParameterList") // One field per thing the reference model predicts.
    class Expected(
        val balances: Map<AccountId, Long>,
        val gameCredits: Map<DailyGameKey, Long>,
        val minutesUsed: Map<KidId, Long>,
        val overCap: Set<EventId>,
        val outOfWindow: Set<EventId>,
        val invalid: Set<EventId>,
        val history: ExpectedHistory,
    )

    /**
     * @property claims which applied event holds each period claim.
     * @property grants purchase id to (minutes granted, the credit that redeemed it).
     */
    class ExpectedHistory(val claims: Map<PeriodClaim, EventId>, val grants: Map<EventId, Pair<Long, EventId?>>)

    fun fold(history: GeneratedHistory, accounts: List<AccountId>, policy: LedgerPolicy): Expected {
        val chosen = history.events.groupBy { it.eventId }.values.mapNotNull { winner(it.distinct()) }
        val (invalid, valid) = chosen.partition {
            val from = policy.parentSignatureRequiredFromLamport
            val signatureRequired =
                policy.requireParentSignature && it.isParentOnly() && (from == null || it.lamport >= from)
            it.envelope.hash in history.invalidHashes || (signatureRequired && it.envelope.parentSignature == null) ||
                (it is GamePlayed && !creditsFiat(it))
        }
        val folder = Folder(accounts, policy, history.receivedAt)
        valid
            .sortedWith(compareBy({ it.envelope.lamport }, { it.envelope.authorDeviceId.value }, { it.eventId.value }))
            .forEach(folder::apply)
        return Expected(
            folder.balances,
            folder.games,
            folder.minutes,
            folder.overCap,
            folder.outOfWindow,
            invalid.map { it.eventId }.toSet() + folder.rejected,
            ExpectedHistory(folder.claims, folder.grants),
        )
    }

    private class Folder(
        accounts: List<AccountId>,
        private val policy: LedgerPolicy,
        private val receivedAt: Map<String, Instant>,
    ) {
        val balances = accounts.associateWith { 0L }.toMutableMap()
        val games = mutableMapOf<DailyGameKey, Long>()
        val minutes = mutableMapOf<KidId, Long>()
        val overCap = mutableSetOf<EventId>()
        val outOfWindow = mutableSetOf<EventId>()
        val rejected = mutableSetOf<EventId>()
        val claims = mutableMapOf<PeriodClaim, EventId>()
        val grants = mutableMapOf<EventId, Pair<Long, EventId?>>()
        private val purchases = mutableMapOf<EventId, RewardPurchased>()

        fun apply(event: LedgerEvent) {
            when {
                !historyAllows(event) -> rejected += event.eventId
                event is GamePlayed && !withinWindow(event) -> outOfWindow += event.eventId
                event is GamePlayed && !creditGame(event) -> overCap += event.eventId
                else -> applyAllowed(event)
            }
        }

        private fun applyAllowed(event: LedgerEvent) {
            if (event is TimeBankStopped) {
                minutes[event.envelope.kidId] = (minutes[event.envelope.kidId] ?: 0L) + event.minutesUsed.minorUnits
            }
            for ((account, delta) in deltas(event)) balances[account] = (balances[account] ?: 0L) + delta
            record(event)
        }

        private fun historyAllows(event: LedgerEvent): Boolean {
            val claimFree = claimOf(event)?.let { it !in claims } ?: true
            val purchaseId = (event as? TimeBankCredited)?.grantedBy ?: return claimFree
            val purchase = purchases[purchaseId]
            val grant = grants[purchaseId]
            return purchase != null && grant != null && grant.second == null &&
                purchase.envelope.kidId == event.envelope.kidId && purchase.rewardId == event.rewardId &&
                grant.first == event.minutes.minorUnits
        }

        /** A stamped game: received at most 1 day before and 7 days after its `playedOn` (UTC days). */
        private fun withinWindow(event: GamePlayed): Boolean {
            val stamp = receivedAt[event.envelope.hash] ?: return true
            val receivedDay = Math.floorDiv(stamp.epochSeconds, SECONDS_PER_DAY)
            return receivedDay - event.playedOn.toEpochDay() in -1L..7L
        }

        private fun creditGame(event: GamePlayed): Boolean {
            val key = DailyGameKey(event.envelope.kidId, event.playedOn, event.currency)
            val sum = (games[key] ?: 0L) + event.amount.minorUnits
            if (sum > policy.dailyGameCapFor(event.currency).minorUnits) return false
            games[key] = sum
            return true
        }

        private fun record(event: LedgerEvent) {
            claimOf(event)?.let { claims[it] = event.eventId }
            if (event is TimeBankCredited && event.grantedBy != null) {
                grants[event.grantedBy] = grants.getValue(event.grantedBy).first to event.eventId
            }
            if (event is RewardPurchased && event.minutesGranted != null) {
                purchases[event.eventId] = event
                grants[event.eventId] = event.minutesGranted.minorUnits to null
            }
        }
    }

    private const val SECONDS_PER_DAY = 86_400L

    /** Games credit a fiat currency only, never screen-time minutes (audit H1). */
    private fun creditsFiat(event: GamePlayed): Boolean = event.currency != Currency.Minutes

    /**
     * The most a play may credit, written out independently of `GameCredit`: rounds per difficulty (Seedling 5,
     * Sprout 8, Sapling 10), 1 per correct round, +3 for all correct, +2 more at Sapling (timed), at most 15. Null for
     * an id that is not playable or a score outside `0..rounds`.
     */
    fun maxGameCredit(gameId: String, score: Long): Long? {
        val rounds = when (gameId) {
            "arithmetic.seedling", "memory.seedling" -> 5L
            "arithmetic.sprout", "memory.sprout", "words.sprout" -> 8L
            "arithmetic.sapling", "memory.sapling", "words.sapling" -> 10L
            else -> null
        }
        if (rounds == null || score !in 0L..rounds) return null
        val perfect = score == rounds
        val bonus = (if (perfect) 3L else 0L) + (if (perfect && rounds == 10L) 2L else 0L)
        return minOf(score + bonus, 15L)
    }

    /** Written out independently of [periodClaim]. */
    fun claimOf(event: LedgerEvent): PeriodClaim? = when (event) {
        is AllowancePaid -> PeriodClaim.Allowance(event.envelope.kidId, event.periodKey, event.currency)
        is ChoreRewarded -> PeriodClaim.Chore(event.envelope.kidId, event.choreId, event.dueOn, event.currency)
        is InterestAccrued -> PeriodClaim.Interest(event.envelope.accountId, event.periodKey)
        else -> null
    }

    private fun winner(copies: List<LedgerEvent>): LedgerEvent? {
        val parents = copies.filter { it.envelope.authorRole == Role.PARENT }
        return parents.ifEmpty { copies }.minWithOrNull(
            compareBy({ it.envelope.lamport }, { it.envelope.authorDeviceId.value }, { it.envelope.hash }),
        )
    }

    fun deltas(event: LedgerEvent): List<Pair<AccountId, Long>> {
        val account = event.envelope.accountId
        return when (event) {
            is ChoreRewarded -> listOf(account to event.amount.minorUnits)

            is AllowancePaid -> listOf(account to event.amount.minorUnits)

            is GamePlayed -> listOf(account to event.amount.minorUnits)

            is ManualAdjustment -> listOf(account to event.amount.minorUnits)

            is InterestAccrued -> listOf(account to event.amount.minorUnits)

            is OverdraftCompensated -> listOf(account to event.amount.minorUnits)

            is TimeBankCredited -> listOf(account to event.minutes.minorUnits)

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
 * @property receivedAt server stamps of the kid's games that were "pulled", by hash: some within the played-on
 *   window of their date, some outside it.
 */
internal class GeneratedHistory(
    val events: List<LedgerEvent>,
    val chained: List<LedgerEvent>,
    val invalidHashes: Set<String>,
    val conflicted: Set<EventId>,
    val receivedAt: Map<String, Instant> = emptyMap(),
)

/**
 * Generates a household history: two parent devices and a kid device writing while mostly offline, with
 * occasional syncs that merge lamport clocks. Includes events that later trip every anomaly: overdrafts, games over
 * the cap, forged parent-only events, ids reused across authors, forged copies of existing events, periods paid
 * twice (chores, allowance and interest drawn from small pools, so offline devices collide), and screen-time credits
 * that are right, wrong, repeated or unreferenced.
 *
 * @param parentKey when set, the parent devices parent-sign their parent-only events.
 */
internal class StreamGenerator(seed: Int, parentKey: ParentAuthorization? = null) {
    private val random = Random(seed)
    private val devices = listOf(
        DeviceWriter(Fixtures.parentPhone, seed = seed * 3, parentKey = parentKey),
        DeviceWriter(Fixtures.parentTablet, seed = seed * 3 + 1, parentKey = parentKey),
        DeviceWriter(Fixtures.kidTablet, seed = seed * 3 + 2),
    )
    private val weeks = listOf("2026-W45", "2026-W46", "2026-W47")
    private val months = listOf("2026-11", "2026-12")
    private val chores = listOf("dishes", "bins")
    private var lastScreenTime: RewardPurchased? = null
    private var session: String? = null
    private val dates = listOf(Fixtures.today, Fixtures.today.copy(day = 3))
    private val invalid = mutableSetOf<String>()
    private val conflicted = mutableSetOf<EventId>()
    private val forged = mutableListOf<LedgerEvent>()
    private val stamps = mutableMapOf<String, Instant>()
    private val gameIds = listOf(
        "arithmetic.seedling", "memory.seedling", "arithmetic.sprout", "memory.sprout", "words.sprout",
        "arithmetic.sapling", "memory.sapling", "words.sapling", "words.seedling", "chess.sprout", "memory",
    )

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
        return GeneratedHistory(chained + forged, chained, invalid.toSet(), conflicted.toSet(), stamps.toMap())
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
        when (random.nextInt(10)) {
            0 -> device.chore(amount(), choreId = chores.random(random), dueOn = dates.random(random))

            1 -> device.allowance(amount(600), periodKey = weeks.random(random))

            2 -> device.adjust(if (random.nextBoolean()) amount() else -amount())

            3 -> device.interest(amount(20), periodKey = months.random(random))

            4 -> device.chore(amount(60), Fixtures.minutes, chores.random(random), dates.random(random))

            9 -> device.timeCredit(amount(60))

            // a manual grant

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
        when (random.nextInt(10)) {
            8 -> buyScreenTime(device)

            9 -> creditMinutes(device)

            0, 1 -> game(device, Fixtures.main)

            2 -> device.purchase(amount())

            3 -> device.transfer(amount(200))

            4 -> session?.let { device.timeStop(it, amount(30)).also { session = null } }
                ?: device.timeStart("s${random.nextInt()}").also { session = it.sessionId }

            5 -> game(device, Fixtures.minutes)

            6 -> reuseId(device, Role.PARENT) { device.purchase(amount()) }

            else -> invalid += device.chore(amount()).envelope.hash // forged parent-only event
        }
    }

    /**
     * A play: usually a playable id with a score in range and at most what it earns, sometimes an unknown id, a score
     * out of range, an amount above the credit, or a screen-time minutes account (statically invalid). Half the
     * plays get a server stamp (they were "pulled"), a few days either side of `playedOn`, so some fall outside the
     * played-on window.
     */
    private fun game(device: DeviceWriter, account: Account) {
        val gameId = gameIds.random(random)
        val score = random.nextLong(-1, 12)
        val max = ReferenceLedger.maxGameCredit(gameId, score)
        val amount = when {
            max == null || max == 0L -> amount(16)
            random.nextInt(5) == 0 -> max + 1
            else -> random.nextLong(1, max + 1)
        }
        val event = device.game(amount, dates.random(random), account, gameId, score)
        if (max == null || amount > max || account.currency == Currency.Minutes) invalid += event.envelope.hash
        if (random.nextBoolean()) {
            val receivedOn = event.playedOn.plusDays(STAMP_OFFSETS.random(random))
            stamps[event.envelope.hash] =
                Instant.fromEpochSeconds(receivedOn.toEpochDay() * SECONDS_PER_DAY + random.nextLong(SECONDS_PER_DAY))
        }
    }

    /** A screen-time purchase, usually redeemed straight away (sometimes left for [creditMinutes]). */
    private fun buyScreenTime(device: DeviceWriter) {
        val minutes = amount(60)
        val purchase = device.screenTime(amount(), minutes)
        lastScreenTime = purchase
        if (random.nextInt(4) != 0) device.timeCredit(minutes, grantedBy = purchase)
    }

    /** A credit for the last screen-time purchase: right (maybe a repeat), wrong minutes, or with no reference. */
    private fun creditMinutes(device: DeviceWriter) {
        val purchase = lastScreenTime
        when {
            // A kid cannot grant minutes manually.
            purchase == null || random.nextInt(4) == 0 -> invalid += device.timeCredit(amount(60)).envelope.hash

            random.nextBoolean() -> device.timeCredit(assertNotNull(purchase.minutesGranted).minorUnits, purchase)

            else -> device.timeCredit(assertNotNull(purchase.minutesGranted).minorUnits + 1, purchase)
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
                ChoreRewarded(it, Fixtures.gbp, Money(amount()), "reused", dates.random(random))
            } else {
                GamePlayed(it, Fixtures.gbp, Money(amount(16)), Fixtures.GAME_ID, Fixtures.GAME_SCORE, Fixtures.today)
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

    private companion object {
        const val SECONDS_PER_DAY = 86_400L

        /** Days from `playedOn` to the server stamp: the window allows -1..7. */
        val STAMP_OFFSETS = listOf(-3, -2, -1, 0, 1, 7, 8, 10)
    }
}
