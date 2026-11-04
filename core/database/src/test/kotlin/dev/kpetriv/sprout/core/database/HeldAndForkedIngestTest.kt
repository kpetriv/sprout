package dev.kpetriv.sprout.core.database

import dev.kpetriv.sprout.core.database.entity.AuditAction
import dev.kpetriv.sprout.core.domainhousehold.Reward
import dev.kpetriv.sprout.core.domainledger.AllowancePaid
import dev.kpetriv.sprout.core.domainledger.DeviceStatus
import dev.kpetriv.sprout.core.domainledger.GamePlayed
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.domainledger.QuarantineReason
import dev.kpetriv.sprout.core.model.AccountId
import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.model.RewardId
import dev.kpetriv.sprout.core.model.RewardKind
import dev.kpetriv.sprout.core.sync.DocumentRejection
import dev.kpetriv.sprout.core.sync.EventApplyReport
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Held events and documents (D-M3-31), forks across ingests, and the synced parent-signature policy on pull
 * (D-M3-32), between a parent device and a kid device of one household.
 */
@RunWith(RobolectricTestRunner::class)
class HeldAndForkedIngestTest {
    private val parent = parentDevice()
    private val kid = kidDevice()
    private val day = CalendarDate(2026, 11, 2)
    private val closeLater = mutableListOf(parent, kid)

    @After
    fun close() = closeLater.forEach(TestHousehold::close)

    /** Registers both devices, creates [KID_X] with its accounts on the parent and applies them on the kid. */
    private suspend fun linked(): AccountId {
        registerEachOther(parent, kid)
        parent.addKid(KID_X.value)
        kid.applier.applyDocuments(parent.queuedDocuments())
        return mainOf(parent)
    }

    private suspend fun mainOf(device: TestHousehold): AccountId =
        with(device) { device.database.accountDao().forKid(KID_X).map { it.toDomain() }.money() }.id

    private suspend fun balanceOf(device: TestHousehold): Money =
        device.ledger.observeBalances(KID_X).first().filter { it.currency == device.gbp }
            .sumOf { it.amount.minorUnits }.let(::Money)

    private suspend fun TestHousehold.game(main: AccountId, lamport: Long, prevHash: String? = null) =
        craft(KID_X, main, lamport = lamport, prevHash = prevHash) {
            GamePlayed(it, gbp, Money(5), "memory.sprout", 6, day)
        }

    private fun JsonObject.hash(): String = getValue("envelope").jsonObject.getValue("hash").jsonPrimitive.content

    // ---- held events and documents (D-M3-31) ----

    @Test
    fun documentsAndEventsOfADeviceTheRegistryDoesNotKnowYetAreHeldThenApplied() = runTest {
        // The kid device knows only itself: the parent's registry entry has not been pulled yet.
        kid.applier.applyRegistry(listOf(kid.registered()), emptyList())
        parent.applier.applyRegistry(listOf(parent.registered(), kid.registered()), emptyList())
        parent.addKid(KID_X.value)
        val main = mainOf(parent)
        parent.writer.append(KID_X, main) { GamePlayed(it, parent.gbp, Money(5), "memory.sprout", 6, day) }

        val documents = kid.applier.applyDocuments(parent.queuedDocuments())
        val events = kid.applier.applyEvents(parent.queuedEvents())

        assertEquals(parent.queuedDocuments().size, documents.held)
        assertEquals(EventApplyReport(held = 1), events)
        assertEquals(
            setOf(DocumentRejection.UNKNOWN_DEVICE.name),
            kid.database.documentInboxDao().all().map { it.reason }.toSet(),
        )
        assertEquals(parent.deviceId, kid.database.documentInboxDao().all().first().authorDeviceId)
        assertEquals(QuarantineReason.UNKNOWN_DEVICE.name, kid.database.ledgerInboxDao().all().single().reason)
        // Still unknown: a retry keeps both, and nothing is quarantined or audited as rejected.
        assertEquals(EventApplyReport(held = 1), kid.applier.retryHeld())
        assertTrue(kid.database.auditLogDao().all().none { it.action == AuditAction.DOCUMENT_REJECTED })

        kid.applier.applyRegistry(listOf(parent.registered(), kid.registered()), emptyList())
        val retried = kid.applier.retryHeld()

        assertEquals(EventApplyReport(applied = 1), retried)
        assertEquals(0, kid.database.documentInboxDao().count())
        assertEquals(0, kid.database.ledgerInboxDao().count())
        assertEquals(Money(5), balanceOf(kid))
        assertEquals(balanceOf(parent), balanceOf(kid))
    }

    @Test
    fun aPendingDevicesEventIsHeldUntilApprovedAndARevokedOnesIsQuarantined() = runTest {
        val main = linked()
        kid.applier.applyRegistry(listOf(parent.registered(DeviceStatus.Pending)), emptyList())
        parent.writer.append(KID_X, main) { GamePlayed(it, parent.gbp, Money(5), "memory.sprout", 6, day) }

        assertEquals(EventApplyReport(held = 1), kid.applier.applyEvents(parent.queuedEvents()))
        assertEquals(QuarantineReason.DEVICE_NOT_ACTIVE.name, kid.database.ledgerInboxDao().all().single().reason)

        kid.applier.applyRegistry(listOf(parent.registered()), emptyList())
        assertEquals(EventApplyReport(applied = 1), kid.applier.retryHeld())
        assertEquals(Money(5), balanceOf(kid))

        // A REVOKED device's event at or above its revocation lamport is refused for good.
        parent.writer.append(KID_X, main) { GamePlayed(it, parent.gbp, Money(6), "memory.sprout", 6, day) }
        kid.applier.applyRegistry(listOf(parent.registered(DeviceStatus.Revoked(0))), emptyList())
        val revoked = kid.applier.applyEvents(parent.queuedEvents().takeLast(1))
        assertEquals(EventApplyReport(quarantined = 1), revoked)
        assertEquals(0, kid.database.ledgerInboxDao().count())
    }

    @Test
    fun heldRowsAreCappedPerAuthorDeviceAndTheOldestIsDroppedWithAnAuditRow() = runTest {
        val main = linked()
        val stranger = TestHousehold(
            RuntimeEnvironment.getApplication(),
            sessionFor = TestHousehold.kidSession(HOME, "stranger", KID_X),
        ).also { closeLater += it }
        val events = (1..RoomLedgerRepository.MAX_HELD_PER_AUTHOR + 1).map { stranger.game(main, lamport = 1) }

        val report = parent.applier.applyEvents(events)

        assertEquals(RoomLedgerRepository.MAX_HELD_PER_AUTHOR + 1, report.held)
        assertEquals(RoomLedgerRepository.MAX_HELD_PER_AUTHOR, parent.database.ledgerInboxDao().count())
        val dropped = parent.database.auditLogDao().all().single { it.action == AuditAction.HELD_DROPPED }
        val ids = events.map { it.getValue("envelope").jsonObject.getValue("eventId").jsonPrimitive.content }
        assertEquals(ids.min(), dropped.target)
        assertEquals(stranger.deviceId, dropped.deviceId)
        assertTrue(parent.database.ledgerInboxDao().all().none { it.eventId.value == ids.min() })
    }

    @Test
    fun heldDocumentsAreCappedPerAuthorDeviceToo() = runTest {
        linked()
        val stranger = TestHousehold(
            RuntimeEnvironment.getApplication(),
            sessionFor = TestHousehold.parentSession(HOME, "stranger"),
        ).also { closeLater += it }
        repeat(RoomRemoteApplier.MAX_HELD_PER_AUTHOR + 1) { index ->
            stranger.rewards.upsert(
                Reward(RewardId("r%03d".format(index)), HOME, "Treat", RewardKind.TREAT, Money(1), stranger.gbp),
            )
        }

        val report = kid.applier.applyDocuments(stranger.queuedDocuments())

        assertEquals(RoomRemoteApplier.MAX_HELD_PER_AUTHOR + 1, report.held)
        assertEquals(RoomRemoteApplier.MAX_HELD_PER_AUTHOR, kid.database.documentInboxDao().count())
        val dropped = kid.database.auditLogDao().all().single { it.action == AuditAction.HELD_DROPPED }
        assertEquals("r000", dropped.target)
        assertEquals(stranger.deviceId, dropped.deviceId)
    }

    // ---- forks (D-M3-31) ----

    @Test
    fun aForkSplitAcrossTwoIngestsQuarantinesBothBranchesAndTheirDescendants() = runTest {
        val main = linked()
        val branchA = kid.game(main, lamport = 10)
        val afterA = kid.game(main, lamport = 12, prevHash = branchA.hash())
        val branchB = kid.game(main, lamport = 11)

        assertEquals(EventApplyReport(applied = 2), parent.applier.applyEvents(listOf(branchA, afterA)))
        assertEquals(Money(10), balanceOf(parent))

        // The second branch arrives later: every branch is refused, including the one already applied.
        val report = parent.applier.applyEvents(listOf(branchB))

        assertEquals(EventApplyReport(quarantined = 1), report)
        val rows = parent.database.ledgerEventDao().all().filter { it.authorDeviceId == kid.deviceId }
        assertEquals(3, rows.size)
        assertTrue(rows.all { it.quarantined && it.quarantineReason == QuarantineReason.FORKED_CHAIN.name })
        assertEquals(Money.ZERO, balanceOf(parent))
        assertEquals(
            3,
            parent.database.auditLogDao().all().count {
                it.action == AuditAction.LEDGER_EVENT_QUARANTINED &&
                    QuarantineReason.FORKED_CHAIN.name in it.detailsJson
            },
        )
        // A later event extending a forked branch is refused with it, not held forever.
        val afterAfterA = kid.game(main, lamport = 13, prevHash = afterA.hash())
        assertEquals(EventApplyReport(quarantined = 1), parent.applier.applyEvents(listOf(afterAfterA)))
        assertEquals(0, parent.database.ledgerInboxDao().count())

        // A device that sees the whole fork in one pull ends the same way.
        val other = parentDevice().also { closeLater += it }
        registerEachOther(parent, kid, other)
        other.applier.applyDocuments(parent.queuedDocuments())
        val single = other.applier.applyEvents(listOf(afterAfterA, branchB, afterA, branchA))
        assertEquals(EventApplyReport(quarantined = 4), single)
        assertEquals(Money.ZERO, balanceOf(other))
        assertTrue(
            other.database.ledgerEventDao().all().all { it.quarantineReason == QuarantineReason.FORKED_CHAIN.name },
        )
    }

    @Test
    fun anEventTheVerifierRefusedIsNeverABranchOfAFork() = runTest {
        val main = linked()
        val genuine = kid.game(main, lamport = 10)
        // A tree claiming the kid device with the same predecessor, but not signed by it.
        val forged = kid.game(main, lamport = 11).let { tree ->
            val envelope = tree.getValue("envelope").jsonObject
            JsonObject(tree + ("envelope" to JsonObject(envelope + ("signature" to JsonPrimitive("AAAA")))))
        }

        assertEquals(EventApplyReport(quarantined = 1), parent.applier.applyEvents(listOf(forged)))
        assertEquals(EventApplyReport(applied = 1), parent.applier.applyEvents(listOf(genuine)))
        assertEquals(Money(5), balanceOf(parent))
    }

    // ---- the synced parent-signature policy (D-M3-32) ----

    private suspend fun unsignedAllowance(main: AccountId): JsonObject =
        parent.craft(KID_X, main) { AllowancePaid(it, parent.gbp, Money(300), "weekly", "2026-W45") }

    @Test
    fun withoutTheSyncedPolicyAnUnsignedParentEventWaitsAndAppliesWhenTheHouseholdNeedsNoSignature() = runTest {
        val main = linked()
        assertNull(kid.database.householdPolicyDao().get(), "no household_policy document pulled yet")

        assertEquals(EventApplyReport(held = 1), kid.applier.applyEvents(listOf(unsignedAllowance(main))))
        assertEquals(RoomLedgerRepository.HELD_POLICY_UNKNOWN, kid.database.ledgerInboxDao().all().single().reason)

        // The household has no strong biometric: requireParentSignature = false is synced to the kid device.
        parent.policies.ensure(parent.gbp)
        kid.applier.applyDocuments(parent.queuedDocuments())
        assertEquals(false, kid.database.householdPolicyDao().get()?.requireParentSignature)

        assertEquals(EventApplyReport(applied = 1), kid.applier.retryHeld())
        assertEquals(Money(300), balanceOf(kid))
    }

    @Test
    fun whenTheSyncedPolicyRequiresParentSignaturesAnUnsignedParentEventIsQuarantined() = runTest {
        val main = linked()
        parent.policies.ensure(parent.gbp)
        parent.registerParentKey()
        kid.applier.applyDocuments(parent.queuedDocuments())
        assertEquals(true, kid.database.householdPolicyDao().get()?.requireParentSignature)

        assertEquals(EventApplyReport(quarantined = 1), kid.applier.applyEvents(listOf(unsignedAllowance(main))))

        val row = kid.database.ledgerEventDao().all().single()
        assertEquals(QuarantineReason.PARENT_SIGNATURE_MISSING.name, row.quarantineReason)
        assertEquals(Money.ZERO, balanceOf(kid))
    }
}
