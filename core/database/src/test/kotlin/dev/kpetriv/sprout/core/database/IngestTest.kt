package dev.kpetriv.sprout.core.database

import dev.kpetriv.sprout.core.database.entity.AuditAction
import dev.kpetriv.sprout.core.database.entity.EventOrigin
import dev.kpetriv.sprout.core.database.entity.OutboxKind
import dev.kpetriv.sprout.core.domainledger.AllowancePaid
import dev.kpetriv.sprout.core.domainledger.AppendResult
import dev.kpetriv.sprout.core.domainledger.GamePlayed
import dev.kpetriv.sprout.core.domainledger.LedgerAnomaly
import dev.kpetriv.sprout.core.domainledger.LedgerJson
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.domainledger.QuarantineReason
import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.sync.EventApplyReport
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [RoomLedgerRepository.ingest] between a parent device and a kid device of one household. */
@RunWith(RobolectricTestRunner::class)
class IngestTest {
    private val parent = parentDevice()
    private val kid = kidDevice()
    private val day = CalendarDate(2026, 11, 2)

    @After
    fun close() {
        parent.close()
        kid.close()
    }

    /** Registers both devices, creates [KID_X] with its accounts on the parent and applies them on the kid. */
    private suspend fun linked(): dev.kpetriv.sprout.core.domainledger.Account {
        registerEachOther(parent, kid)
        val (_, accounts) = parent.addKid(KID_X.value)
        kid.applier.applyDocuments(parent.queuedDocuments())
        return with(parent) { accounts.money() }
    }

    private suspend fun game(on: TestHousehold, amount: Long = 5): AppendResult {
        val main = with(on) { on.database.accountDao().forKid(KID_X).map { it.toDomain() }.money() }
        return on.writer.append(KID_X, main.id) { GamePlayed(it, on.gbp, Money(amount), "memory", 3, day) }
    }

    private suspend fun balanceOf(on: TestHousehold): Money =
        on.ledger.observeBalances(KID_X).first().filter { it.currency == on.gbp }.sumOf { it.amount.minorUnits }
            .let(::Money)

    @Test
    fun anAcceptedRemoteEventIsStoredWithoutAnOutboxRowAndAudited() = runTest {
        linked()
        assertEquals(AppendResult.Accepted, game(parent))

        val report = kid.applier.applyEvents(parent.queuedEvents())

        assertEquals(EventApplyReport(applied = 1), report)
        val row = kid.database.ledgerEventDao().all().single()
        assertEquals(EventOrigin.REMOTE, row.origin)
        assertFalse(row.quarantined)
        assertNotNull(row.ingestedAtEpochMs)
        assertEquals(parent.queuedEvents().single(), LedgerJson.json.parseToJsonElement(row.payloadJson))
        assertTrue(kid.database.outboxDao().all().none { it.kind == OutboxKind.LEDGER_EVENT })
        val audit = kid.database.auditLogDao().all().single { it.action == AuditAction.LEDGER_EVENT_INGESTED }
        assertEquals(row.eventId.value, audit.target)
        assertEquals(parent.deviceId, audit.deviceId)
        assertFalse("memory" in audit.detailsJson, "ids only, no event content")
        assertEquals(Money(5), balanceOf(kid))
    }

    @Test
    fun duplicatesAreCountedAndTheClockObservesTheAppliedLamport() = runTest {
        linked()
        game(parent)
        val events = parent.queuedEvents()
        val lamport = parent.database.ledgerEventDao().all().single().lamport

        kid.applier.applyEvents(events)
        assertEquals(EventApplyReport(duplicates = 1), kid.applier.applyEvents(events))

        assertTrue(kid.database.deviceStateDao().get()!!.lamport >= lamport)
        assertTrue(kid.applier.localMaxLamport() >= lamport)
        assertEquals(1, kid.database.ledgerEventDao().count())
    }

    @Test
    fun anEventAfterAGapIsHeldUntilTheGapIsFilled() = runTest {
        linked()
        game(parent, 5)
        game(parent, 6)
        val (first, second) = parent.queuedEvents()

        assertEquals(EventApplyReport(held = 1), kid.applier.applyEvents(listOf(second)))
        assertEquals(1, kid.database.ledgerInboxDao().count())
        assertEquals(0, kid.database.ledgerEventDao().count())
        assertEquals(EventApplyReport(held = 1), kid.applier.retryHeld())

        assertEquals(EventApplyReport(applied = 2), kid.applier.applyEvents(listOf(first)))
        assertEquals(0, kid.database.ledgerInboxDao().count())
        assertEquals(Money(11), balanceOf(kid))
        assertEquals(EventApplyReport(), kid.applier.retryHeld())
    }

    @Test
    fun refusedEventsAreStoredQuarantinedWithTheirReasonAndListed() = runTest {
        val main = linked()
        val savings = with(parent) { parent.database.accountDao().forKid(KID_X).map { it.toDomain() }.savings() }
        // A parent-only event the kid device signs as a parent, without a parent signature.
        val forged = kid.craft(KID_X, main.id, role = dev.kpetriv.sprout.core.model.Role.PARENT) {
            AllowancePaid(it, kid.gbp, Money(10_000), "weekly", "2026-W45")
        }
        // A game credited to a savings account: well signed, but breaks a ledger rule of its own.
        val misplaced = kid.craft(KID_X, savings.id) { GamePlayed(it, kid.gbp, Money(5), "memory", 3, day) }
        // A device the household never registered.
        val stranger =
            TestHousehold(RuntimeEnvironment.getApplication(), sessionFor = TestHousehold.kidSession(HOME, "x", KID_X))
        val unknown = stranger.craft(KID_X, main.id) { GamePlayed(it, kid.gbp, Money(5), "memory", 3, day) }
        stranger.close()
        val broken = JsonObject(mapOf("envelope" to JsonObject(mapOf("eventId" to JsonPrimitive("e")))))

        val report = parent.applier.applyEvents(listOf(forged, misplaced, unknown, broken))

        // The unknown device's event is held for the registry (D-M3-31), not quarantined.
        assertEquals(EventApplyReport(quarantined = 3, held = 1), report)
        assertEquals(QuarantineReason.UNKNOWN_DEVICE.name, parent.database.ledgerInboxDao().all().single().reason)
        val rows = parent.database.ledgerEventDao().all().filter { it.origin == EventOrigin.REMOTE }
        assertTrue(rows.all { it.quarantined })
        assertEquals(
            setOf(
                QuarantineReason.ROLE_NOT_ALLOWED.name,
                RoomLedgerRepository.INVARIANT_QUARANTINE + "WrongAccountKind",
            ),
            rows.map { it.quarantineReason }.toSet(),
        )
        // The forged document is kept as it arrived, as evidence.
        assertEquals(forged, LedgerJson.json.parseToJsonElement(rows.first().payloadJson))
        val listed = parent.ledger.observeQuarantined().first()
        assertEquals(rows.map { it.eventId }.toSet(), listed.map { it.eventId }.toSet())
        assertEquals(3, parent.database.auditLogDao().all().count { it.action == AuditAction.LEDGER_EVENT_QUARANTINED })
        assertEquals(Money.ZERO, balanceOf(parent))
        // Pulling the same documents again changes nothing.
        assertEquals(3, parent.applier.applyEvents(listOf(forged, misplaced, unknown)).duplicates)
        assertEquals(1, parent.database.ledgerInboxDao().count())
    }

    @Test
    fun aConflictingCopyIsKeptAsEvidenceAndTheParentDevicesCopyApplies() = runTest {
        val main = linked()
        game(parent, 5)
        val parentRow = parent.database.ledgerEventDao().all().single()
        // The kid device reuses the parent's eventId for its own event.
        val copy = kid.craft(KID_X, main.id, eventId = parentRow.eventId) {
            GamePlayed(it, kid.gbp, Money(40), "memory", 3, day)
        }

        assertEquals(EventApplyReport(applied = 1), parent.applier.applyEvents(listOf(copy)))

        val evidence = parent.database.ledgerEventDao().all().single { it.origin == EventOrigin.REMOTE }
        assertEquals(parentRow.eventId, evidence.eventId)
        assertFalse(evidence.quarantined)
        assertTrue(parent.database.auditLogDao().all().any { it.action == AuditAction.LEDGER_EVENT_CONFLICT })
        val conflict =
            assertIs<LedgerAnomaly.ConflictingDuplicate>(parent.ledger.observeAnomalies(KID_X).first().single())
        // Registry roles: the parent device's copy applies, the kid device's copy is excluded.
        assertEquals(parentRow.hash, conflict.applied?.hash)
        assertEquals(Money(5), balanceOf(parent))
        val cached = parent.database.anomalyDao().forKid(KID_X).single()
        assertTrue(cached.appliedCopyJson!!.contains(parentRow.hash))
    }

    @Test
    fun maintenanceVerifiesRemoteRowsAgainstTheRegistryAndLeavesUnknownDevicesAlone() = runTest {
        linked()
        game(parent, 5)
        kid.applier.applyEvents(parent.queuedEvents())
        // A remote row whose author the registry does not know (stored by an older client) stays as it is.
        val stranger =
            TestHousehold(
                RuntimeEnvironment.getApplication(),
                sessionFor = TestHousehold.kidSession(HOME, "x", KidId("y")),
            )
        val foreign = LedgerJson.decode(
            LedgerJson.json.encodeToString(
                JsonObject.serializer(),
                stranger.craft(KidId("y"), dev.kpetriv.sprout.core.model.AccountId("acc-y")) {
                    GamePlayed(it, kid.gbp, Money(5), "memory", 3, day)
                },
            ),
        )
        stranger.close()
        kid.database.ledgerEventDao().insert(LedgerEventMapper.toEntity(foreign, EventOrigin.REMOTE, 0))
        assertEquals(0, kid.ledger.quarantineUnverifiable())

        // A remote row whose signature no longer verifies against its registered device is quarantined.
        val row = kid.database.ledgerEventDao().all().first { it.authorDeviceId == parent.deviceId }
        val other = assertNotNull(foreign.envelope.signature)
        kid.database.openHelper.writableDatabase.execSQL(
            "UPDATE ledger_events SET payloadJson = ?, signature = ? WHERE eventId = ?",
            arrayOf(row.payloadJson.replace(row.signature!!, other), other, row.eventId.value),
        )
        assertEquals(1, kid.ledger.quarantineUnverifiable())
        val quarantined = kid.database.ledgerEventDao().all().single { it.eventId == row.eventId }
        assertEquals(LedgerEventMapper.SIGNATURE_INVALID, quarantined.quarantineReason)
        assertNull(kid.database.ledgerEventDao().all().single { it.eventId == foreign.eventId }.quarantineReason)
    }
}
