package dev.kpetriv.sprout.core.domainledger

import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class ProjectionTest {
    private val parent = DeviceWriter(Fixtures.parentPhone)
    private val kid = DeviceWriter(Fixtures.kidTablet)

    // M1/M2 semantics: no device registry, so conflicts are resolved by the envelopes' own roles (opted in).
    private fun project(events: List<LedgerEvent>, policy: LedgerPolicy = LedgerPolicy()) =
        Ledger.project(events, Fixtures.accounts, policy, trustEnvelopeRoles = true)

    private fun empty(policy: LedgerPolicy = LedgerPolicy()) =
        Projection.empty(Fixtures.accounts, policy, trustEnvelopeRoles = true)

    @Test
    fun creditsAndDebitsFoldIntoBalances() {
        val events = listOf(
            parent.chore(150),
            parent.allowance(500),
            kid.game(15),
            kid.purchase(120),
            parent.adjust(-30),
            kid.transfer(200),
            parent.interest(3),
        )
        val projection = project(events)
        assertEquals(Money(150 + 500 + 15 - 120 - 30 - 200), projection.balanceOf(Fixtures.main.id))
        assertEquals(Money(203), projection.balanceOf(Fixtures.savings.id))
        assertEquals(Money.ZERO, projection.balanceOf(Fixtures.minutes.id))
        assertTrue(projection.anomalies.isEmpty())
        assertEquals(events.map { it.eventId }.toSet(), projection.appliedEventIds)

        val main = projection.balances.getValue(Fixtures.main.id)
        assertEquals(Fixtures.gbp, main.currency)
        val lastMainEvent = events.filter { e -> e.postings.any { it.accountId == Fixtures.main.id } }
            .maxWith(LedgerOrdering)
        assertEquals(lastMainEvent.lamport, main.lastLamport)
        assertEquals(lastMainEvent.envelope.createdAt, main.asOf)
    }

    @Test
    fun timeBankStateTracksSessions() {
        val credit = parent.chore(90, Fixtures.minutes)
        val start = kid.timeStart("s1")
        val running = project(listOf(credit, start)).timeBank(Fixtures.kid)
        assertEquals(
            TimeBankState(Fixtures.kid, Fixtures.minutes.id, true, "s1", start.envelope.createdAt, Money.ZERO),
            running,
        )
        val stopped = project(listOf(credit, start, kid.timeStop("s1", 25), kid.timeStart("s2"), kid.timeStop("s2", 5)))
        val state = stopped.timeBank(Fixtures.kid)
        assertEquals(false, state?.running)
        assertNull(state?.startedAt)
        assertEquals(Money(30), state?.accumulatedMinutes)
        assertEquals(Money(60), stopped.balanceOf(Fixtures.minutes.id))
    }

    @Test
    fun aGameOverTheDailyCapIsExcludedWholeNotClamped() {
        val policy = LedgerPolicy(dailyGameCap = Money(20))
        val first = kid.game(12)
        val second = kid.game(15)
        val third = kid.game(4)
        val fourth = kid.game(4)
        val tomorrow = kid.game(10, Fixtures.today.copy(day = 3))
        val projection = project(listOf(first, second, third, fourth, tomorrow), policy)
        assertEquals(Money(20), projection.dailyGameCredits(Fixtures.kid, Fixtures.today, Fixtures.gbp))
        assertEquals(Money(10), projection.dailyGameCredits(Fixtures.kid, Fixtures.today.copy(day = 3), Fixtures.gbp))
        assertEquals(Money.ZERO, projection.dailyGameCredits(Fixtures.kid, Fixtures.today, Currency.Minutes))
        assertEquals(Money(12 + 4 + 4 + 10), projection.balanceOf(Fixtures.main.id))
        assertEquals(
            listOf(
                LedgerAnomaly.DailyCapExceeded(Fixtures.kid, Fixtures.today, Fixtures.gbp, second.eventId, Money(7)),
            ),
            projection.anomalies,
        )
        // Validation applies the same rule: the excluded game would have been rejected locally too.
        val beforeSecond = project(listOf(first), policy)
        assertIs<Violation.DailyCapExceeded>(
            assertIs<ValidationResult.Invalid>(LedgerRules.validate(second, beforeSecond)).violations.single(),
        )
    }

    @Test
    fun aPulledGameDatedOutsideItsReceiveWindowIsExcludedWhole() {
        val today = Fixtures.today

        // Stamps at 23:59 UTC on the receive day: the date, not the time of day, is what counts.
        fun stampOn(date: CalendarDate) = Instant.fromEpochSeconds(date.toEpochDay() * 86_400 + 86_399)
        val offline = kid.game(10, today.plusDays(-7)) // offline for a week: accepted
        val early = kid.game(10, today.plusDays(1)) // a day ahead (time zones): accepted
        val backdated = kid.game(10, today.plusDays(-8))
        val postdated = kid.game(10, today.plusDays(2))
        val own = kid.game(10, today.plusDays(-30)) // no stamp: the author's own copy, never date-checked
        val received = listOf(offline, early, backdated, postdated).associate { it.envelope.hash to stampOn(today) }
        val events = listOf(offline, early, backdated, postdated, own)
        val projection = Ledger.project(events, Fixtures.accounts, receivedAt = received)

        assertEquals(Money(30), projection.balanceOf(Fixtures.main.id))
        assertEquals(
            listOf(
                LedgerAnomaly.PlayedOnOutOfRange(Fixtures.kid, backdated.eventId, backdated.playedOn, today),
                LedgerAnomaly.PlayedOnOutOfRange(Fixtures.kid, postdated.eventId, postdated.playedOn, today),
            ),
            projection.anomalies,
        )
        // Excluded games take nothing from their day's cap.
        assertEquals(Money.ZERO, projection.dailyGameCredits(Fixtures.kid, backdated.playedOn, Fixtures.gbp))
        assertEquals(Money(10), projection.dailyGameCredits(Fixtures.kid, offline.playedOn, Fixtures.gbp))
        assertEquals(
            projection.anomalies,
            projection.anomaliesFor(Fixtures.kid),
        )
        // Incremental applies carry the stamps they are given, and match the full projection.
        val incremental = events.sortedWith(LedgerOrdering).fold(Projection.empty(Fixtures.accounts)) { p, e ->
            p.apply(e, received.filterKeys { it == e.envelope.hash })
        }
        assertEquals(projection, incremental)
        assertEquals(projection.anomalies, incremental.anomalies)
        // Without stamps everything applies.
        assertEquals(Money(50), Ledger.project(events, Fixtures.accounts).balanceOf(Fixtures.main.id))
    }

    @Test
    fun twoOfflineDebitsThatOverdrawAreFlaggedNotThrown() {
        val chore = parent.chore(100)
        kid.observe(chore.lamport)
        val otherKidDevice =
            DeviceWriter(Fixtures.kidTablet.copy(deviceId = dev.kpetriv.sprout.core.model.DeviceId("kid-phone")))
        otherKidDevice.observe(chore.lamport)
        // Both devices saw a balance of 100 and each spent 80 while offline.
        val before = project(listOf(chore))
        val a = kid.purchase(80)
        val b = otherKidDevice.purchase(80)
        assertTrue(LedgerRules.validate(a, before).isValid)
        assertTrue(LedgerRules.validate(b, before).isValid)

        val merged = project(listOf(b, chore, a))
        assertEquals(Money(-60), merged.balanceOf(Fixtures.main.id))
        val later = listOf(a, b).maxWith(LedgerOrdering)
        assertEquals(
            listOf(LedgerAnomaly.OverdraftDetected(Fixtures.main.id, Money(60), listOf(later.eventId))),
            merged.anomalies,
        )
    }

    @Test
    fun furtherDebitsWhileOverdrawnExtendTheSameAnomaly() {
        val events = listOf(parent.chore(10), parent.purchase(15), parent.purchase(5), parent.adjust(-2))
        val anomaly = assertIs<LedgerAnomaly.OverdraftDetected>(project(events).anomalies.single())
        assertEquals(Money(12), anomaly.shortfall)
        assertEquals(events.drop(1).map { it.eventId }, anomaly.causedBy)
    }

    @Test
    fun recoveringAndOverdrawingAgainOpensANewAnomaly() {
        val events = listOf(kid.purchase(5), parent.allowance(10), kid.purchase(8))
        val anomalies = project(events).anomalies
        assertEquals(2, anomalies.size)
        assertEquals(Money(3), (anomalies[1] as LedgerAnomaly.OverdraftDetected).shortfall)
    }

    @Test
    fun compensationResolvesTheAnomaly() {
        val chore = parent.chore(100)
        kid.observe(chore.lamport)
        val a = kid.purchase(80)
        val b = kid.purchase(80)
        parent.observe(b.lamport)
        val overdrawn = project(listOf(chore, a, b))
        val anomaly = assertIs<LedgerAnomaly.OverdraftDetected>(overdrawn.anomalies.single())

        val fix = parent.compensate(anomaly.shortfall.minorUnits, anomaly.causedBy)
        val resolved = overdrawn.apply(fix)
        assertTrue(resolved.anomalies.isEmpty())
        assertEquals(Money.ZERO, resolved.balanceOf(Fixtures.main.id))
        assertEquals(project(listOf(chore, a, b, fix)), resolved)
    }

    @Test
    fun partialCompensationLeavesTheRemainderVisible() {
        val a = kid.purchase(50)
        val fix = parent.compensate(20, listOf(a.eventId))
        val anomaly = assertIs<LedgerAnomaly.OverdraftDetected>(project(listOf(a, fix)).anomalies.single())
        assertEquals(Money(30), anomaly.shortfall)
        assertEquals(listOf(a.eventId), anomaly.causedBy)
    }

    @Test
    fun compensationNamingNothingRelevantLeavesAnomaliesAlone() {
        val chore = parent.chore(1)
        val a = kid.purchase(50)
        val projection = project(listOf(chore, a, parent.compensate(50, listOf(chore.eventId))))
        assertEquals(1, projection.anomalies.size)
    }

    @Test
    fun invalidEventsAreExcludedAndReported() {
        val forged = kid.chore(1_000)
        val projection = project(listOf(parent.chore(10), forged))
        assertEquals(Money(10), projection.balanceOf(Fixtures.main.id))
        val anomaly = assertIs<LedgerAnomaly.InvalidEvent>(projection.anomalies.single())
        assertEquals(forged.eventId, anomaly.eventId)
        assertIs<Violation.ParentOnly>(anomaly.violations.single())
        assertTrue(forged.eventId in projection)
    }

    @Test
    fun currencyIsCheckedAgainstPriorBalancesForUnregisteredAccounts() {
        val unregistered = Fixtures.main.copy(id = dev.kpetriv.sprout.core.model.AccountId("loose"))
        val gbp = parent.chore(10, unregistered)
        val minutes = parent.write(unregistered) { ChoreRewarded(it, Currency.Minutes, Money(5), "c", Fixtures.today) }
        val projection = Ledger.project(listOf(gbp, minutes))
        assertEquals(Money(10), projection.balanceOf(unregistered.id))
        assertIs<Violation.CurrencyMismatch>(
            assertIs<LedgerAnomaly.InvalidEvent>(projection.anomalies.single()).violations.single(),
        )
    }

    @Test
    fun duplicatesAreIgnored() {
        val chore = parent.chore(100)
        val projection = project(listOf(chore, chore, chore.withSignature(byteArrayOf(1))))
        assertEquals(Money(100), projection.balanceOf(Fixtures.main.id))
        assertTrue(projection.anomalies.isEmpty())
        assertEquals(projection, projection.apply(chore))
    }

    @Test
    fun ofTwoCopiesFromOneAuthorTheFirstByHashIsApplied() {
        val chore = parent.chore(100) as ChoreRewarded
        val evil = chore.copy(amount = Money(9_999)).mutateEnvelope { it.copy(hash = "f".repeat(64)) }
        val projection = project(listOf(evil, chore, evil))
        val conflict = assertIs<LedgerAnomaly.ConflictingDuplicate>(projection.anomalies.single())
        assertEquals(chore.eventId, conflict.eventId)
        assertEquals(listOf(chore.envelope.hash, "f".repeat(64)), conflict.copies.map { it.hash })
        // Same lamport and device: the lower hash wins, on every device.
        assertTrue(chore.envelope.hash < "f".repeat(64))
        assertEquals(chore.envelope.hash, conflict.applied?.hash)
        assertEquals(Money(100), projection.balanceOf(Fixtures.main.id))
        assertTrue(chore.eventId in projection)
        // The input order cannot matter, and re-applying either copy changes nothing.
        assertEquals(projection, project(listOf(chore, evil)))
        assertEquals(projection, project(listOf(evil)).apply(chore))
        assertEquals(projection, projection.apply(chore))
        assertEquals(projection, projection.apply(evil))
    }

    @Test
    fun sameHashButDifferentContentIsAlsoAConflict() {
        val chore = parent.chore(100) as ChoreRewarded
        // A tampered copy that kept the stored hash: unverified input, reported as a conflict all the same.
        val tampered = chore.copy(amount = Money(9_999))
        val projection = project(listOf(chore, tampered))
        assertIs<LedgerAnomaly.ConflictingDuplicate>(projection.anomalies.single())
        assertEquals(projection, project(listOf(tampered, chore)))
    }

    @Test
    fun aKidReusingAParentsEventIdWithALowerLamportCannotCancelTheParentsCredit() {
        val chore = parent.chore(10)
        val allowance = parent.allowance(500)
        // The kid device never synced, so its lamport is far behind the parent's.
        val reused = kid.writeWithId(allowance.eventId, Fixtures.main) {
            RewardPurchased(it, Fixtures.gbp, Money(5), "toy")
        }
        assertTrue(reused.lamport < allowance.lamport)
        assertTrue(LedgerOrdering.compare(reused, chore) < 0)

        val expectedConflict = LedgerAnomaly.ConflictingDuplicate(
            allowance.eventId,
            listOf(DuplicateCopy.of(allowance), DuplicateCopy.of(reused)).sortedBy { it.hash },
        )
        val full = project(listOf(chore, allowance, reused))
        assertEquals(listOf(expectedConflict), full.anomalies)
        assertEquals(DuplicateCopy.of(allowance), expectedConflict.applied)
        // The parent's credit applies; the kid's purchase does not.
        assertEquals(Money(510), full.balanceOf(Fixtures.main.id))
        assertEquals(setOf(Role.PARENT, Role.KID), expectedConflict.copies.map { it.authorRole }.toSet())

        // Arriving late, after the parent's copy was applied, the kid's copy changes nothing but the anomaly.
        val incremental = project(listOf(chore, allowance)).apply(reused)
        assertEquals(full, incremental)
        assertEquals(Money(510), incremental.balanceOf(Fixtures.main.id))
        // Any arrival order converges.
        assertEquals(full, project(listOf(reused, allowance, chore)))
        assertEquals(full, project(listOf(reused)).apply(listOf(allowance, chore)))
    }

    @Test
    fun kidReusesParentsEventIdParentDebitStillApplies() {
        val allowance = parent.allowance(500)
        val debit = parent.adjust(-200)
        kid.observe(debit.lamport)
        val reused = kid.writeWithId(debit.eventId, Fixtures.main) {
            GamePlayed(it, Fixtures.gbp, Money(5), Fixtures.GAME_ID, Fixtures.GAME_SCORE, Fixtures.today)
        }

        val projection = project(listOf(allowance, debit, reused))
        assertEquals(Money(300), projection.balanceOf(Fixtures.main.id))
        // The kid's game was not credited towards the daily cap either.
        assertEquals(Money.ZERO, projection.dailyGameCredits(Fixtures.kid, Fixtures.today, Fixtures.gbp))
        val conflict = assertIs<LedgerAnomaly.ConflictingDuplicate>(projection.anomalies.single())
        assertEquals(debit.envelope.hash, conflict.applied?.hash)
        assertEquals(2, conflict.copies.size)
        assertEquals(projection, project(listOf(reused, debit, allowance)))
        assertEquals(projection, project(listOf(allowance, debit)).apply(reused))
    }

    @Test
    fun parentWinsJudgesCopiesByRegisteredDeviceRoleNotTheEnvelope() {
        val allowance = parent.allowance(500)
        // A kid device that writes authorRole = PARENT on everything.
        val liar = DeviceWriter(Fixtures.kidTablet.copy(role = Role.PARENT), seed = 99)
        val reuse = liar.writeWithId(allowance.eventId, Fixtures.main) {
            RewardPurchased(it, Fixtures.gbp, Money(5), "toy")
        }
        // By envelope both copies claim PARENT; by registry only the parent phone is a parent device.
        assertEquals(allowance, appliedCopyOf(listOf(allowance, reuse), Fixtures::roleOf))
        val registered = Ledger.project(listOf(allowance, reuse), Fixtures.accounts, roleOf = Fixtures::roleOf)
        assertEquals(Money(500), registered.balanceOf(Fixtures.main.id))
        val conflict = assertIs<LedgerAnomaly.ConflictingDuplicate>(registered.anomalies.single())
        assertEquals(DuplicateCopy.of(allowance), conflict.applied)
        // Kept across incremental applies and the rebuild a conflicting copy forces.
        val incremental = Projection.empty(Fixtures.accounts, roleOf = Fixtures::roleOf).apply(reuse).apply(allowance)
        assertEquals(registered, incremental)
        assertEquals(conflict, incremental.anomalies.single())

        // Against a real kid copy, the kid device claiming PARENT does not win: both are KID copies by registry, so
        // the first by (lamport, device, hash) applies, which is the real game.
        val game = kid.game(5)
        val claim = liar.writeWithId(game.eventId, Fixtures.main) {
            ChoreRewarded(it, Fixtures.gbp, Money(900), "fake", Fixtures.today)
        }
        assertTrue(game.lamport < claim.lamport)
        assertEquals(game, appliedCopyOf(listOf(game, claim), Fixtures::roleOf))
        val byRegistry = Ledger.project(listOf(game, claim), Fixtures.accounts, roleOf = Fixtures::roleOf)
        assertEquals(Money(5), byRegistry.balanceOf(Fixtures.main.id))
        assertEquals(
            DuplicateCopy.of(game),
            assertIs<LedgerAnomaly.ConflictingDuplicate>(byRegistry.anomalies.single()).applied,
        )
        // The registry wins over trusted envelopes too; trusting envelopes alone would let the claim win.
        val bothWays = Ledger.project(
            listOf(game, claim),
            Fixtures.accounts,
            roleOf = Fixtures::roleOf,
            trustEnvelopeRoles = true,
        )
        assertEquals(byRegistry, bothWays)
        val trusted = Ledger.project(listOf(game, claim), Fixtures.accounts, trustEnvelopeRoles = true)
        assertEquals(Money(900), trusted.balanceOf(Fixtures.main.id))
    }

    @Test
    fun withoutARegistryOrTrustedEnvelopesTheFirstCopyIsApplied() {
        val allowance = parent.allowance(500)
        kid.observe(allowance.lamport)
        val reuse = kid.writeWithId(allowance.eventId, Fixtures.main) {
            RewardPurchased(it, Fixtures.gbp, Money(5), "toy")
        }
        val copies = listOf(allowance, reuse)
        assertTrue(allowance.lamport < reuse.lamport)
        val unknown = Ledger.project(copies, Fixtures.accounts)
        assertEquals(Money(500), unknown.balanceOf(Fixtures.main.id))
        val unknownConflict = assertIs<LedgerAnomaly.ConflictingDuplicate>(unknown.anomalies.single())
        assertEquals(DuplicateCopy.of(allowance), unknownConflict.applied)
        assertEquals(allowance, appliedCopyOf(copies) { null })
        // One known parent copy wins over a copy whose device is unknown, whatever the order.
        val kidOnly: (DeviceId) -> Role? = { if (it == Fixtures.kidTablet.deviceId) Role.PARENT else null }
        assertEquals(reuse, appliedCopyOf(copies, kidOnly))
        // A single copy needs no roles at all.
        assertEquals(allowance, appliedCopyOf(listOf(allowance)) { null })
    }

    @Test
    fun twoParentCopiesOrTwoKidCopiesApplyTheFirstByLamportDeviceAndHash() {
        val tablet = DeviceWriter(Fixtures.parentTablet)
        val chore = parent.chore(100)
        tablet.observe(chore.lamport)
        val parentReuse = tablet.writeWithId(chore.eventId, Fixtures.main) {
            ChoreRewarded(it, Fixtures.gbp, Money(900), "other", Fixtures.today)
        }
        val parents = project(listOf(parentReuse, chore))
        assertEquals(Money(100), parents.balanceOf(Fixtures.main.id))
        val parentConflict = assertIs<LedgerAnomaly.ConflictingDuplicate>(parents.anomalies.single())
        assertEquals(chore.envelope.hash, parentConflict.applied?.hash)

        // A forged copy of a parent event plus a kid's reuse that sorts first ("kid-tablet" < "parent-phone" at the
        // same lamport): the kid copy never beats a parent copy, and of the two parent copies the first by
        // (lamport, device, hash) wins.
        val forged = (chore as ChoreRewarded).copy(amount = Money(1)).let {
            it.mutateEnvelope { e -> e.copy(hash = HashChain().hashOf(it)) }
        }
        val kidReuse = DeviceWriter(Fixtures.kidTablet, seed = 77).writeWithId(chore.eventId, Fixtures.main) {
            RewardPurchased(it, Fixtures.gbp, Money(1), "r")
        }
        assertTrue(LedgerOrdering.compare(kidReuse, chore) < 0)
        val expected = listOf(chore, forged).minBy { it.envelope.hash }
        val mixed = project(listOf(chore, forged, kidReuse))
        val mixedConflict = assertIs<LedgerAnomaly.ConflictingDuplicate>(mixed.anomalies.single())
        assertEquals(expected.envelope.hash, mixedConflict.applied?.hash)
        assertEquals(mixed, project(listOf(kidReuse, forged, chore)))

        val game = kid.game(10)
        val kidTwin = DeviceWriter(Fixtures.kidTablet.copy(deviceId = DeviceId("kid-phone"))).writeWithId(
            game.eventId,
            Fixtures.main,
        ) { GamePlayed(it, Fixtures.gbp, Money(15), Fixtures.GAME_ID, Fixtures.GAME_SCORE, Fixtures.today) }
        assertEquals(game.lamport, kidTwin.lamport)
        // Same lamport: "kid-phone" sorts before "kid-tablet".
        val kids = project(listOf(game, kidTwin))
        assertEquals(Money(15), kids.balanceOf(Fixtures.main.id))
        val kidConflict = assertIs<LedgerAnomaly.ConflictingDuplicate>(kids.anomalies.single())
        assertEquals(kidTwin.envelope.hash, kidConflict.applied?.hash)
        assertEquals(kids, project(listOf(kidTwin, game)))
    }

    @Test
    fun compensationNamingAClosedOverdraftDoesNotReopenOrDuplicate() {
        val a = kid.purchase(5) // overdraft A: -5
        val credit = parent.allowance(10) // clears A: +5
        val b = kid.purchase(8) // overdraft B: -3
        parent.observe(b.lamport)
        val fix = parent.compensate(1, listOf(a.eventId)) // names A's cause only: -2
        val before = project(listOf(a, credit, b))
        val overdraftB = LedgerAnomaly.OverdraftDetected(Fixtures.main.id, Money(3), listOf(b.eventId))
        assertEquals(
            listOf(LedgerAnomaly.OverdraftDetected(Fixtures.main.id, Money(5), listOf(a.eventId)), overdraftB),
            before.anomalies,
        )

        val after = before.apply(fix)
        // A is acknowledged and gone; B is untouched and still the open overdraft.
        assertEquals(listOf(overdraftB), after.anomalies)
        assertEquals(Money(-2), after.balanceOf(Fixtures.main.id))
        assertEquals(after, project(listOf(fix, b, credit, a)))

        // A further debit extends B, proving it is still the open anomaly.
        kid.observe(fix.lamport)
        val more = kid.purchase(4)
        assertEquals(
            listOf(overdraftB.copy(shortfall = Money(6), causedBy = listOf(b.eventId, more.eventId))),
            after.apply(more).anomalies,
        )
    }

    @Test
    fun compensationNamingTheOpenOverdraftPartlyKeepsItOpenOnce() {
        val a = kid.purchase(5)
        val credit = parent.allowance(10)
        val b = kid.purchase(8)
        parent.observe(b.lamport)
        val fix = parent.compensate(1, listOf(a.eventId, b.eventId))
        val anomalies = project(listOf(a, credit, b, fix)).anomalies
        assertEquals(listOf(LedgerAnomaly.OverdraftDetected(Fixtures.main.id, Money(2), listOf(b.eventId))), anomalies)
    }

    @Test
    fun extremeAmountsNeverThrowAndOverflowIsAnInvalidEvent() {
        val permissive = LedgerPolicy(maxAmount = Money(Long.MAX_VALUE), dailyGameCap = Money(Long.MAX_VALUE))
        val first = parent.chore(Long.MAX_VALUE)
        val second = parent.chore(Long.MAX_VALUE)
        val events = listOf(first, second)

        val bounded = project(events)
        assertEquals(Money.ZERO, bounded.balanceOf(Fixtures.main.id))
        assertEquals(
            listOf(Violation.AmountTooLarge(Money(Long.MAX_VALUE), LedgerPolicy.DEFAULT_MAX_AMOUNT)),
            bounded.anomalies.filterIsInstance<LedgerAnomaly.InvalidEvent>().first().violations,
        )

        val unbounded = project(events, permissive)
        assertEquals(Money(Long.MAX_VALUE), unbounded.balanceOf(Fixtures.main.id))
        assertEquals(
            listOf(LedgerAnomaly.InvalidEvent(second.eventId, listOf(Violation.Overflow))),
            unbounded.anomalies,
        )
        // A two-posting transfer that overflows on its second leg leaves both accounts untouched.
        kid.observe(second.lamport)
        val fund = parent.write(Fixtures.savings) { ManualAdjustment(it, Fixtures.gbp, Money(Long.MAX_VALUE), "seed") }
        kid.observe(fund.lamport)
        val transfer = kid.transfer(1)
        val atomic = project(listOf(first, fund, transfer), permissive)
        assertEquals(Money(Long.MAX_VALUE), atomic.balanceOf(Fixtures.main.id))
        assertEquals(Money(Long.MAX_VALUE), atomic.balanceOf(Fixtures.savings.id))
        assertEquals(listOf(LedgerAnomaly.InvalidEvent(transfer.eventId, listOf(Violation.Overflow))), atomic.anomalies)
    }

    @Test
    fun projectionNeverThrowsOnExtremeValues() {
        val extremes = listOf(
            Long.MIN_VALUE, Long.MIN_VALUE + 1, -1_000_000_000_001, -1, 0, 1, 1_000_000_000_000, 1_000_000_000_001,
            Long.MAX_VALUE - 1, Long.MAX_VALUE,
        )
        val policies = listOf(
            LedgerPolicy(),
            LedgerPolicy(maxAmount = Money(Long.MAX_VALUE), dailyGameCap = Money(Long.MAX_VALUE)),
            LedgerPolicy(maxAmount = Money(Long.MAX_VALUE), dailyGameCap = Money.ZERO),
        )
        val random = kotlin.random.Random(42)
        repeat(300) { round ->
            val p = DeviceWriter(Fixtures.parentPhone, seed = round * 2)
            val k = DeviceWriter(Fixtures.kidTablet, seed = round * 2 + 1)
            repeat(random.nextInt(1, 25)) { writeExtreme(p, k, random) { extremes.random(random) } }
            val events = (p.written + k.written).shuffled(random)
            for (policy in policies) {
                val projection = project(events, policy)
                assertEquals(projection, empty(policy).apply(events.shuffled(random)))
                events.fold(empty(policy)) { acc, e ->
                    LedgerRules.validate(e, acc)
                    acc.apply(e)
                }
            }
        }
    }

    private fun writeExtreme(p: DeviceWriter, k: DeviceWriter, random: kotlin.random.Random, pick: () -> Long) {
        when (random.nextInt(12)) {
            0 -> p.chore(pick())

            1 -> p.adjust(pick())

            2 -> k.purchase(pick())

            3 -> k.transfer(pick())

            4 -> k.game(pick())

            5 -> k.timeStop("s", pick())

            6 -> p.compensate(pick(), listOf(p.written.lastOrNull()?.eventId ?: k.timeStart("s").eventId))

            7 -> p.write(Fixtures.savings) {
                InterestAccrued(
                    it,
                    Fixtures.gbp,
                    Money(pick()),
                    Money(pick()),
                    random.nextInt(),
                    random.nextInt(),
                    p.nextMonth(),
                )
            }

            8 -> p.chore(pick(), Fixtures.minutes)

            9 -> k.write(Fixtures.minutes) { TimeBankStopped(it, "s", Money(pick())) }

            10 -> k.timeStart("s")

            else -> p.allowance(pick())
        }
    }

    @Test
    fun mergedScreenTimeCreditsRedeemEachPurchaseOnce() {
        val purchase = kid.screenTime(cost = 50, minutes = 20)
        val credit = kid.timeCredit(20, grantedBy = purchase)
        // A second kid device that synced the purchase redeems it again while offline.
        val twin = DeviceWriter(Fixtures.kidTablet.copy(deviceId = DeviceId("kid-phone")))
        twin.observe(credit.lamport)
        val again = twin.timeCredit(20, grantedBy = purchase)
        // A credit that sorts before its purchase cannot redeem it.
        val early = DeviceWriter(Fixtures.kidTablet.copy(deviceId = DeviceId("a-kid-device")))
            .timeCredit(20, grantedBy = purchase)
        val events = listOf(parent.allowance(100), purchase, credit, again, early)

        val projection = project(events)
        assertEquals(Money(20), projection.balanceOf(Fixtures.minutes.id))
        val invalid = projection.anomalies.filterIsInstance<LedgerAnomaly.InvalidEvent>().associateBy { it.eventId }
        assertEquals(setOf(again.eventId, early.eventId), invalid.keys)
        assertEquals(
            listOf(Violation.GrantAlreadyCredited(purchase.eventId, creditedBy = credit.eventId)),
            invalid.getValue(again.eventId).violations,
        )
        assertEquals(listOf(Violation.GrantNotFound(purchase.eventId)), invalid.getValue(early.eventId).violations)
        assertEquals(credit.eventId, projection.rewardGrants.getValue(purchase.eventId).creditedBy)
        assertEquals(projection, project(events.shuffled(kotlin.random.Random(1))))
        assertEquals(projection, project(events.reversed()).apply(events))
    }

    @Test
    fun aPeriodPaidOnTwoOfflineDevicesCountsOnce() {
        val tablet = DeviceWriter(Fixtures.parentTablet)
        val week = parent.allowance(500, periodKey = "2026-W45")
        val sameWeek = tablet.allowance(500, periodKey = "2026-W45")
        val chore = parent.chore(100, choreId = "dishes")
        val sameChore = tablet.chore(100, choreId = "dishes")
        val interest = parent.interest(3, periodKey = "2026-11")
        val sameInterest = tablet.interest(3, periodKey = "2026-11")
        val events = listOf(week, sameWeek, chore, sameChore, interest, sameInterest)

        val projection = project(events)
        assertEquals(Money(600), projection.balanceOf(Fixtures.main.id))
        assertEquals(Money(3), projection.balanceOf(Fixtures.savings.id))
        val invalid = projection.anomalies.map { assertIs<LedgerAnomaly.InvalidEvent>(it) }
        // "parent-phone" sorts before "parent-tablet" at equal lamports, so the tablet's copies lose.
        assertEquals(listOf(sameWeek, sameChore, sameInterest).map { it.eventId }, invalid.map { it.eventId })
        assertTrue(invalid.all { it.violations.single() is Violation.DuplicatePeriod })
        assertEquals(projection, project(events.reversed()))
    }

    @Test
    fun maxLamportCoversEveryFoldedEvent() {
        val forged = kid.chore(1) // invalid, still folded
        parent.observe(10)
        val chore = parent.chore(5)
        assertEquals(0, Projection.empty().maxLamport)
        assertEquals(chore.lamport, project(listOf(chore, forged)).maxLamport)
        assertEquals(forged.lamport, project(listOf(forged)).maxLamport)
    }

    @Test
    fun anomaliesAreAttributedPerKid() {
        val otherKid = dev.kpetriv.sprout.core.model.KidId("kid-2")
        val otherMain =
            Account(dev.kpetriv.sprout.core.model.AccountId("main-2"), otherKid, Fixtures.gbp, AccountKind.MAIN)
        val accounts = Fixtures.accounts + otherMain
        val overdraft = kid.purchase(5)
        val forgedForOther = kid.write(otherMain) { ChoreRewarded(it, Fixtures.gbp, Money(5), "c", Fixtures.today) }
        val projection = Ledger.project(listOf(overdraft, forgedForOther), accounts)
        assertIs<LedgerAnomaly.OverdraftDetected>(projection.anomaliesFor(Fixtures.kid).single())
        assertIs<LedgerAnomaly.InvalidEvent>(projection.anomaliesFor(otherKid).single())
    }

    @Test
    fun incrementalApplyMatchesAFullProjection() {
        val events = listOf(parent.chore(100), kid.game(10), kid.purchase(30), parent.interest(2), kid.transfer(20))
            .sortedWith(LedgerOrdering)
        val incremental = events.fold(empty()) { p, e ->
            assertTrue(p.isInOrder(e))
            p.apply(e)
        }
        assertEquals(project(events), incremental)
    }

    @Test
    fun aLateEventIsReportedOutOfOrderButStillAppliedExactly() {
        val early = kid.purchase(1)
        val late = parent.chore(1)
        val projection = project(listOf(late))
        assertTrue(LedgerOrdering.compare(early, late) < 0)
        assertFalse(projection.isInOrder(early))
        // A copy of an applied event is a no-op, which needs no rebuild.
        assertTrue(projection.isInOrder(late))
        assertTrue(Projection.empty().isInOrder(early))
        // Applying the late event rebuilds, so the result matches a full projection.
        assertEquals(project(listOf(early, late)), projection.apply(early))
        assertEquals(Money.ZERO, projection.apply(early).balanceOf(Fixtures.main.id))
    }
}
