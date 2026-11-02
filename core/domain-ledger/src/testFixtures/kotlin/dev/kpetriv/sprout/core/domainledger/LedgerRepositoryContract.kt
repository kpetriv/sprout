package dev.kpetriv.sprout.core.domainledger

import dev.kpetriv.sprout.core.model.Role
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Behaviour every [LedgerRepository] must show. Subclass it in an implementation's test source set and return a
 * fresh, empty repository with [accounts] registered from [createRepository].
 */
abstract class LedgerRepositoryContract {
    /** A new, empty repository that knows [accounts]. Called once per test. */
    protected abstract suspend fun createRepository(accounts: List<Account>): LedgerRepository

    @Test
    fun appendValidatesChainsAndIsIdempotent() = runTest {
        val repository = createRepository(Fixtures.accounts)
        val parent = DeviceWriter(Fixtures.parentPhone)
        val kid = DeviceWriter(Fixtures.kidTablet)

        val chore = parent.chore(100)
        assertEquals(AppendResult.Accepted, repository.append(chore))
        assertEquals(AppendResult.Duplicate, repository.append(chore))
        assertEquals(chore.envelope.hash, repository.headHash(Fixtures.parentPhone.deviceId))

        kid.observe(repository.latestLamport())
        val tooExpensive = kid.purchase(500)
        val rejected = assertIs<AppendResult.Rejected>(repository.append(tooExpensive))
        assertIs<Violation.InsufficientFunds>(rejected.violations.single())

        // The kid device's chain head did not advance in the repository, so a follow-up built on the rejected
        // event no longer links.
        val orphan = kid.purchase(10)
        assertEquals(CHAIN_REJECTION, assertIs<AppendResult.Rejected>(repository.append(orphan)).reason)

        val balances = repository.observeBalances(Fixtures.kid).first()
        assertEquals(listOf(Money(100)), balances.map { it.amount })
        assertEquals(listOf(chore), repository.observeEvents(Fixtures.kid, limit = 10).first())
        assertEquals(1, repository.latestLamport())
    }

    @Test
    fun eventsAreNewestFirstAndLimited() = runTest {
        val repository = createRepository(Fixtures.accounts)
        val parent = DeviceWriter(Fixtures.parentPhone)
        val written = listOf(parent.chore(100), parent.allowance(200), parent.transfer(50))
        written.forEach { assertEquals(AppendResult.Accepted, repository.append(it)) }

        assertEquals(written.asReversed().take(2), repository.observeEvents(Fixtures.kid, limit = 2).first())
        assertEquals(written.last().envelope.hash, repository.headHash(Fixtures.parentPhone.deviceId))
        assertEquals(null, repository.headHash(Fixtures.kidTablet.deviceId))
        assertEquals(3, repository.latestLamport())

        val balances = repository.observeBalances(Fixtures.kid).first().associate { it.accountId to it.amount }
        assertEquals(mapOf(Fixtures.main.id to Money(250), Fixtures.savings.id to Money(50)), balances)
    }

    @Test
    fun parentOnlyEventsFromKidDevicesAreRejected() = runTest {
        val repository = createRepository(Fixtures.accounts)
        val kid = DeviceWriter(Fixtures.kidTablet)

        val forged = assertIs<AppendResult.Rejected>(repository.append(kid.chore(100)))

        assertEquals(INVARIANT_REJECTION, forged.reason)
        assertIs<Violation.ParentOnly>(forged.violations.single())
        assertEquals(emptyList(), repository.observeEvents(Fixtures.kid, limit = 10).first())
        assertEquals(0, repository.latestLamport())
    }

    @Test
    fun aKidCopyOfAParentsEventIdIsAConflictAndOnlyTheParentCopyApplies() = runTest {
        val repository = createRepository(Fixtures.accounts)
        val parent = DeviceWriter(Fixtures.parentPhone)
        val kid = DeviceWriter(Fixtures.kidTablet)
        assertEquals(emptyList(), repository.observeAnomalies(Fixtures.kid).first())

        parent.chore(10)
        val chore = parent.chore(500)
        parent.written.forEach { assertEquals(AppendResult.Accepted, repository.append(it)) }
        // The kid device has not synced, so its copy has a lower lamport than the parent's original.
        val reused = kid.writeWithId(chore.eventId, Fixtures.main) {
            GamePlayed(it, Fixtures.gbp, Money(5), "g", 1, Fixtures.today)
        }
        assertEquals(true, reused.lamport < chore.lamport)

        assertEquals(
            AppendResult.Conflict(chore.eventId, storedHash = chore.envelope.hash, incomingHash = reused.envelope.hash),
            repository.append(reused),
        )
        // Re-delivering either copy is now a plain duplicate.
        assertEquals(AppendResult.Duplicate, repository.append(reused))
        assertEquals(AppendResult.Duplicate, repository.append(chore))
        // The conflicting copy is chained, so the kid's head advanced past it.
        assertEquals(reused.envelope.hash, repository.headHash(Fixtures.kidTablet.deviceId))

        // The parent's chore still counts; the kid's game does not.
        assertEquals(listOf(Money(510)), repository.observeBalances(Fixtures.kid).first().map { it.amount })
        val conflict = assertIs<LedgerAnomaly.ConflictingDuplicate>(
            repository.observeAnomalies(Fixtures.kid).first().single(),
        )
        assertEquals(chore.eventId, conflict.eventId)
        assertEquals(
            setOf(
                chore.envelope.hash to Fixtures.parentPhone.deviceId,
                reused.envelope.hash to Fixtures.kidTablet.deviceId,
            ),
            conflict.copies.map { it.hash to it.authorDeviceId }.toSet(),
        )
        assertEquals(chore.envelope.hash, conflict.applied?.hash)
    }

    @Test
    fun aKidReusingAParentsEventIdCannotCancelTheParentsDebit() = runTest {
        val repository = createRepository(Fixtures.accounts)
        val parent = DeviceWriter(Fixtures.parentPhone)
        val kid = DeviceWriter(Fixtures.kidTablet)
        val allowance = parent.allowance(500)
        val debit = parent.adjust(-200, reason = "broken window")
        listOf(allowance, debit).forEach { assertEquals(AppendResult.Accepted, repository.append(it)) }
        kid.observe(repository.latestLamport())

        // The kid saw the parent's debit and writes a harmless event under the same id, hoping to cancel it.
        val reused = kid.writeWithId(debit.eventId, Fixtures.main) {
            GamePlayed(it, Fixtures.gbp, Money(5), "g", 1, Fixtures.today)
        }
        assertIs<AppendResult.Conflict>(repository.append(reused))

        assertEquals(listOf(Money(300)), repository.observeBalances(Fixtures.kid).first().map { it.amount })
        val conflict = assertIs<LedgerAnomaly.ConflictingDuplicate>(
            repository.observeAnomalies(Fixtures.kid).first().single(),
        )
        assertEquals(debit.envelope.hash, conflict.applied?.hash)
        assertEquals(setOf(Role.PARENT, Role.KID), conflict.copies.map { it.authorRole }.toSet())
    }

    @Test
    fun copiesFromTheSameRoleAreAllExcluded() = runTest {
        val repository = createRepository(Fixtures.accounts)
        val phone = DeviceWriter(Fixtures.parentPhone)
        val tablet = DeviceWriter(Fixtures.parentTablet)
        val allowance = phone.allowance(500)
        val chore = phone.chore(100)
        listOf(allowance, chore).forEach { assertEquals(AppendResult.Accepted, repository.append(it)) }

        // A second parent device reuses the id: two parent copies, so neither applies.
        val reused = tablet.writeWithId(chore.eventId, Fixtures.main) {
            ChoreRewarded(it, Fixtures.gbp, Money(900), "other")
        }
        assertIs<AppendResult.Conflict>(repository.append(reused))

        assertEquals(listOf(Money(500)), repository.observeBalances(Fixtures.kid).first().map { it.amount })
        val conflict = assertIs<LedgerAnomaly.ConflictingDuplicate>(
            repository.observeAnomalies(Fixtures.kid).first().single(),
        )
        assertEquals(null, conflict.applied)
        assertEquals(2, conflict.copies.size)
    }

    @Test
    fun aConflictingCopyMustStillChain() = runTest {
        val repository = createRepository(Fixtures.accounts)
        val parent = DeviceWriter(Fixtures.parentPhone)
        val chore = parent.chore(100) as ChoreRewarded
        assertEquals(AppendResult.Accepted, repository.append(chore))

        val forged = chore.copy(amount = Money(9_999)).let {
            it.mutateEnvelope { e -> e.copy(hash = HashChain().hashOf(it)) }
        }
        assertEquals(CHAIN_REJECTION, assertIs<AppendResult.Rejected>(repository.append(forged)).reason)
        assertEquals(emptyList(), repository.observeAnomalies(Fixtures.kid).first())
        assertEquals(listOf(Money(100)), repository.observeBalances(Fixtures.kid).first().map { it.amount })
    }

    @Test
    fun aLamportFarAheadOfEverythingSeenIsRejected() = runTest {
        val repository = createRepository(Fixtures.accounts)
        val jumper =
            DeviceWriter(Fixtures.parentPhone, lamport = LamportClock(start = LedgerPolicy.DEFAULT_MAX_LAMPORT_JUMP))
        val event = jumper.chore(10)

        val rejected = assertIs<AppendResult.Rejected>(repository.append(event))
        assertEquals(INVARIANT_REJECTION, rejected.reason)
        assertEquals(
            listOf(
                Violation.LamportTooFarAhead(
                    latest = 0,
                    actual = event.lamport,
                    maxJump = LedgerPolicy.DEFAULT_MAX_LAMPORT_JUMP,
                ),
            ),
            rejected.violations,
        )
        assertEquals(0, repository.latestLamport())
    }

    companion object {
        /** [AppendResult.Rejected.reason] when the event does not link to its author's stored chain head. */
        const val CHAIN_REJECTION: String = "chain verification failed"

        /** [AppendResult.Rejected.reason] when [LedgerRules] rejects the event. */
        const val INVARIANT_REJECTION: String = "invariant violated"
    }
}
