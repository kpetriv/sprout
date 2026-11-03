package dev.kpetriv.sprout.core.database

import dev.kpetriv.sprout.core.database.entity.AnomalyKind
import dev.kpetriv.sprout.core.database.entity.AuditAction
import dev.kpetriv.sprout.core.database.entity.EventOrigin
import dev.kpetriv.sprout.core.database.entity.OutboxKind
import dev.kpetriv.sprout.core.domainledger.Account
import dev.kpetriv.sprout.core.domainledger.AccountKind
import dev.kpetriv.sprout.core.domainledger.AppendResult
import dev.kpetriv.sprout.core.domainledger.ChoreRewarded
import dev.kpetriv.sprout.core.domainledger.DeviceWriter
import dev.kpetriv.sprout.core.domainledger.Fixtures
import dev.kpetriv.sprout.core.domainledger.GamePlayed
import dev.kpetriv.sprout.core.domainledger.InMemoryLedgerRepository
import dev.kpetriv.sprout.core.domainledger.LamportClock
import dev.kpetriv.sprout.core.domainledger.LedgerAnomaly
import dev.kpetriv.sprout.core.domainledger.LedgerEventFactory
import dev.kpetriv.sprout.core.domainledger.LedgerJson
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.domainledger.Violation
import dev.kpetriv.sprout.core.domainledger.anomaliesFor
import dev.kpetriv.sprout.core.model.AccountId
import dev.kpetriv.sprout.core.model.KidId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class RoomLedgerRepositoryTest {
    private val database = inMemoryDatabase()
    private val repository = multiDeviceRepository(database)
    private val parent = DeviceWriter(Fixtures.parentPhone)
    private val kid = DeviceWriter(Fixtures.kidTablet)

    @After
    fun close() = database.close()

    @Test
    fun acceptedAppendWritesEventBalanceOutboxAndAuditTogether() = runTest {
        database.register(Fixtures.accounts)
        val event = parent.adjust(250, reason = "birthday money from grandma")

        assertEquals(AppendResult.Accepted, repository.append(event))

        val row = database.ledgerEventDao().allVerified().single()
        assertEquals(event.eventId, row.eventId)
        assertEquals("manual_adjustment", row.type)
        assertEquals(EventOrigin.LOCAL, row.origin)
        assertFalse(row.quarantined)
        assertEquals(event, LedgerJson.decode(row.payloadJson))

        val balance = database.balanceDao().forKid(Fixtures.kid).single()
        assertEquals(Fixtures.main.id, balance.accountId)
        assertEquals(Money(250), balance.amountMinor)
        assertEquals(event.lamport, balance.lastLamport)

        val outbox = database.outboxDao().all().single()
        assertEquals(OutboxKind.LEDGER_EVENT, outbox.kind)
        assertEquals("${event.eventId.value}:${event.envelope.hash}", outbox.refId)
        assertEquals(row.payloadJson, outbox.payload)
        assertEquals(0, outbox.attempts)

        val audit = database.auditLogDao().all().single()
        assertEquals(AuditAction.LEDGER_EVENT_APPENDED, audit.action)
        assertEquals(event.eventId.value, audit.target)
        assertEquals(Fixtures.parentPhone.uid, audit.actorUid)
        assertEquals(Fixtures.parentPhone.deviceId, audit.deviceId)
        assertFalse("grandma" in audit.detailsJson, "free text must not reach the audit log")
    }

    @Test
    fun rejectedAppendWritesNothing() = runTest {
        database.register(Fixtures.accounts)

        val result = assertIs<AppendResult.Rejected>(repository.append(kid.purchase(500)))

        assertIs<Violation.InsufficientFunds>(result.violations.single())
        assertEquals(0, database.ledgerEventDao().count())
        assertEquals(emptyList(), database.balanceDao().forKid(Fixtures.kid))
        assertEquals(emptyList(), database.anomalyDao().forKid(Fixtures.kid))
        assertEquals(emptyList(), database.outboxDao().all())
        assertEquals(emptyList(), database.auditLogDao().all())
    }

    @Test
    fun unknownAccountIsRejected() = runTest {
        val result = assertIs<AppendResult.Rejected>(repository.append(parent.chore(100)))
        assertIs<Violation.UnknownAccount>(result.violations.single())
        assertEquals(0, database.ledgerEventDao().count())
    }

    @Test
    fun duplicateIsANoOp() = runTest {
        database.register(Fixtures.accounts)
        val chore = parent.chore(100)

        assertEquals(AppendResult.Accepted, repository.append(chore))
        assertEquals(AppendResult.Duplicate, repository.append(chore))

        assertEquals(1, database.ledgerEventDao().count())
        assertEquals(1, database.outboxDao().all().size)
        assertEquals(1, database.auditLogDao().all().size)
    }

    @Test
    fun eventBelowItsAuthorsHighestLamportIsRejectedEvenIfItLinks() = runTest {
        database.register(Fixtures.accounts)
        val first = LedgerEventFactory(testClock(), LamportClock(start = 4), Fixtures.seededIds(1))
            .create(Fixtures.parentPhone, Fixtures.kid, Fixtures.main.id, headHash = null) {
                ChoreRewarded(it, Fixtures.gbp, Money(100), "c1", Fixtures.today)
            }
        assertEquals(AppendResult.Accepted, repository.append(first))

        val stale = LedgerEventFactory(testClock(), LamportClock(start = 2), Fixtures.seededIds(2))
            .create(Fixtures.parentPhone, Fixtures.kid, Fixtures.main.id, headHash = first.envelope.hash) {
                ChoreRewarded(it, Fixtures.gbp, Money(100), "c2", Fixtures.today)
            }

        val rejected = assertIs<AppendResult.Rejected>(repository.append(stale))
        assertEquals(RoomLedgerRepository.CHAIN_REJECTION, rejected.reason)
        assertEquals(1, database.ledgerEventDao().count())
    }

    @Test
    fun overdraftFromTimeBankIsCachedAsAnAnomalyWithAStableId() = runTest {
        database.register(Fixtures.accounts)
        assertEquals(AppendResult.Accepted, repository.append(parent.chore(10, Fixtures.minutes)))
        kid.observe(repository.latestLamport())
        val start = kid.timeStart("session-1")
        val stop = kid.timeStop("session-1", minutes = 30)
        assertEquals(AppendResult.Accepted, repository.append(start))
        assertEquals(AppendResult.Accepted, repository.append(stop))

        val anomaly = database.anomalyDao().forKid(Fixtures.kid).single()
        assertEquals(AnomalyKind.OVERDRAFT, anomaly.kind)
        assertEquals("overdraft:${Fixtures.minutes.id.value}:${stop.eventId.value}", anomaly.anomalyId)
        assertEquals(Money(20), anomaly.amountMinor)
        val balances = repository.observeBalances(Fixtures.kid).first().associate { it.accountId to it.amount }
        assertEquals(Money(-20), balances[Fixtures.minutes.id])
    }

    @Test
    fun quarantinedEventsAreInvisible() = runTest {
        database.register(Fixtures.accounts)
        val chore = parent.chore(100)
        assertEquals(AppendResult.Accepted, repository.append(chore))
        val forged = kid.chore(999)
        database.ledgerEventDao().insert(
            LedgerEventMapper.toEntity(forged, EventOrigin.REMOTE, 0, quarantineReason = "signature invalid")
                .copy(lamport = 1_000),
        )

        assertEquals(chore.lamport, repository.latestLamport())
        assertNull(repository.headHash(Fixtures.kidTablet.deviceId))
        assertEquals(listOf(chore), repository.observeEvents(Fixtures.kid, limit = 10).first())
        assertEquals(listOf(Money(100)), repository.observeBalances(Fixtures.kid).first().map { it.amount })
        assertEquals(AppendResult.Accepted, repository.append(parent.allowance(50)))
        assertTrue(Money(150) in repository.observeBalances(Fixtures.kid).first().map { it.amount })
    }

    @Test
    fun anEventIdReusedAcrossKidsIsAConflictForBothKids() = runTest {
        val otherKid = KidId("kid-2")
        val otherMain = Account(AccountId("main-2"), otherKid, Fixtures.gbp, AccountKind.MAIN)
        database.register(Fixtures.accounts + otherMain)
        val chore = parent.chore(100)
        assertEquals(AppendResult.Accepted, repository.append(chore))
        kid.observe(repository.latestLamport())

        // Another kid's device reuses the id for that kid's own account.
        val reused = kid.writeWithId(chore.eventId, otherMain) {
            GamePlayed(it, Fixtures.gbp, Money(5), Fixtures.GAME_ID, Fixtures.GAME_SCORE, Fixtures.today)
        }
        assertIs<AppendResult.Conflict>(repository.append(reused))

        // The parent's copy applies to the first kid; the kid's copy is excluded for the second.
        assertEquals(listOf(Money(100)), repository.observeBalances(Fixtures.kid).first().map { it.amount })
        assertEquals(emptyList(), repository.observeBalances(otherKid).first())
        val first =
            assertIs<LedgerAnomaly.ConflictingDuplicate>(repository.observeAnomalies(Fixtures.kid).first().single())
        val second =
            assertIs<LedgerAnomaly.ConflictingDuplicate>(repository.observeAnomalies(otherKid).first().single())
        assertEquals(first, second)
        assertEquals(setOf(Fixtures.kid, otherKid), first.copies.map { it.kidId }.toSet())
        assertEquals(chore.envelope.hash, first.applied?.hash)
        // Both copies are stored and the conflict was audited.
        assertEquals(2, database.ledgerEventDao().all().size)
        assertEquals(AuditAction.LEDGER_EVENT_CONFLICT, database.auditLogDao().all().last().action)

        // Same answer as the domain's reference implementation.
        val reference = InMemoryLedgerRepository(Fixtures.accounts + otherMain)
        reference.append(chore)
        reference.append(reused)
        assertEquals(reference.observeAnomalies(otherKid).first(), repository.observeAnomalies(otherKid).first())
    }

    @Test
    fun anomaliesReadBackEqualToTheProjection() = runTest {
        database.register(Fixtures.accounts)
        assertEquals(AppendResult.Accepted, repository.append(parent.chore(10, Fixtures.minutes)))
        kid.observe(repository.latestLamport())
        listOf(kid.timeStart("s"), kid.timeStop("s", minutes = 30)).forEach {
            assertEquals(AppendResult.Accepted, repository.append(it))
        }
        val projected = repository.projection().anomaliesFor(Fixtures.kid)
        assertTrue(projected.isNotEmpty())
        assertEquals(projected, repository.observeAnomalies(Fixtures.kid).first())
    }

    @Test
    fun aRowWhoseHashNoLongerMatchesIsSkippedAndQuarantined() = runTest {
        database.register(Fixtures.accounts)
        val chore = parent.chore(100)
        val allowance = parent.allowance(50)
        listOf(chore, allowance).forEach { assertEquals(AppendResult.Accepted, repository.append(it)) }

        // Someone edits the stored amount of the allowance without being able to re-hash it.
        val row = database.ledgerEventDao().all().single { it.eventId == allowance.eventId }
        database.openHelper.writableDatabase.execSQL(
            "UPDATE ledger_events SET payloadJson = ? WHERE eventId = ?",
            arrayOf(row.payloadJson.replace("\"amount\":50", "\"amount\":5000"), allowance.eventId.value),
        )

        // Reads skip it immediately and never throw.
        assertEquals(listOf(chore), repository.observeEvents(Fixtures.kid, limit = 10).first())
        // Maintenance marks it and rebuilds the caches without it.
        assertEquals(1, repository.quarantineUnverifiable())
        val stored = database.ledgerEventDao().all().single { it.eventId == allowance.eventId }
        assertTrue(stored.quarantined)
        assertEquals(LedgerEventMapper.HASH_MISMATCH, stored.quarantineReason)
        assertEquals(listOf(Money(100)), repository.observeBalances(Fixtures.kid).first().map { it.amount })
        assertEquals(chore.envelope.hash, repository.headHash(Fixtures.parentPhone.deviceId))
        assertEquals(0, repository.quarantineUnverifiable())
    }

    @Test
    fun anUndecodableRowIsQuarantinedDuringAppend() = runTest {
        database.register(Fixtures.accounts)
        val chore = parent.chore(100)
        assertEquals(AppendResult.Accepted, repository.append(chore))
        database.openHelper.writableDatabase.execSQL(
            "UPDATE ledger_events SET payloadJson = ? WHERE eventId = ?",
            arrayOf("{not json", chore.eventId.value),
        )
        assertEquals(emptyList(), repository.observeEvents(Fixtures.kid, limit = 10).first())

        // The next append quarantines it first, so the parent's next event starts a fresh chain from genesis.
        val fresh = DeviceWriter(Fixtures.parentPhone, seed = 7).allowance(20)
        assertEquals(AppendResult.Accepted, repository.append(fresh))
        assertEquals(LedgerEventMapper.UNDECODABLE, database.ledgerEventDao().all().first().quarantineReason)
        assertEquals(listOf(Money(20)), repository.observeBalances(Fixtures.kid).first().map { it.amount })
    }
}
