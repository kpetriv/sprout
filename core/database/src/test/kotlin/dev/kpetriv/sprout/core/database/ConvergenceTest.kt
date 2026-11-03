package dev.kpetriv.sprout.core.database

import dev.kpetriv.sprout.core.database.entity.AuditAction
import dev.kpetriv.sprout.core.database.usecase.ApprovalResult
import dev.kpetriv.sprout.core.database.usecase.PurchaseResult
import dev.kpetriv.sprout.core.domainhousehold.Chore
import dev.kpetriv.sprout.core.domainhousehold.ChoreReward
import dev.kpetriv.sprout.core.domainhousehold.Reward
import dev.kpetriv.sprout.core.domainledger.AllowancePaid
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.DeviceStatus
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.domainledger.QuarantineReason
import dev.kpetriv.sprout.core.model.AccountId
import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.model.ChoreId
import dev.kpetriv.sprout.core.model.ChoreSchedule
import dev.kpetriv.sprout.core.model.RewardId
import dev.kpetriv.sprout.core.model.RewardKind
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.model.SubmissionStatus
import dev.kpetriv.sprout.core.sync.DocTypes
import dev.kpetriv.sprout.core.sync.FakeBackend
import dev.kpetriv.sprout.core.sync.FakeSyncClock
import dev.kpetriv.sprout.core.sync.FakeSyncTransport
import dev.kpetriv.sprout.core.sync.SyncEngine
import dev.kpetriv.sprout.core.sync.SyncError
import dev.kpetriv.sprout.core.sync.SyncReport
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Two devices of one household, each with the real data layer (Room, ledger ingest, device keys, the `Verifier`),
 * synced through `SyncEngine`s over one `FakeBackend` (M3 §4): a parent device and a kid device linked to [KID_X].
 */
@RunWith(RobolectricTestRunner::class)
class ConvergenceTest {
    private val parent = parentDevice()
    private val kid = kidDevice()
    private val backend = FakeBackend(HOME)
    private val clock = FakeSyncClock()
    private val parentTransport = FakeSyncTransport(backend, parent.deviceId, parent.session.uid, Role.PARENT)
    private val kidTransport = FakeSyncTransport(backend, kid.deviceId, kid.session.uid, Role.KID)
    private val parentEngine = SyncEngine(parentTransport, parent.outbox, parent.applier, clock)
    private val kidEngine = SyncEngine(kidTransport, kid.outbox, kid.applier, clock)
    private val today = CalendarDate(2026, 11, 2)
    private val bed = ChoreId("bed")
    private val screenTime = RewardId("screen-time")

    @After
    fun close() {
        parent.close()
        kid.close()
    }

    private suspend fun SyncEngine.synced(): SyncReport = syncOnce().also { assertNull(it.error, it.toString()) }

    private suspend fun balances(device: TestHousehold): Map<AccountId, Money> =
        device.ledger.observeBalances(KID_X).first().associate { it.accountId to it.amount }

    /**
     * The household as the backend's functions would leave it: both devices ACTIVE in the registry, the kid with
     * its accounts, the parent key registered, a chore paying 150 and a 30-minute screen-time reward costing 100.
     */
    private suspend fun household() {
        backend.registerDevice(parent.registered())
        backend.registerDevice(kid.registered())
        parent.addKid(KID_X.value)
        parent.registerParentKey()
        // registerParentKey on the server (D-M3-34): the key reaches the other devices through the registry only.
        parent.serverRegisteredKeys().forEach(backend::addParentKey)
        parent.chores.upsert(
            Chore(
                id = bed,
                householdId = HOME,
                kidId = KID_X,
                title = "Make your bed",
                reward = ChoreReward(money = Money(150)),
                schedule = ChoreSchedule.Daily.EVERY_DAY,
                createdAt = parent.clock.now(),
            ),
        )
        parent.rewards.upsert(
            Reward(screenTime, HOME, "30 minutes", RewardKind.SCREEN_TIME, Money(100), parent.gbp, minutesGranted = 30),
        )
        parentEngine.synced()
        kidEngine.synced()
    }

    /** The kid submits the chore, the parent approves it (one parent prompt), and both devices sync. */
    private suspend fun approvedChore() {
        kid.chores.submit(bed, today)
        kidEngine.synced()
        parentEngine.synced()
        val submission = parent.chores.observeSubmissions(SubmissionFilter.PendingForParent).first().single()
        assertIs<ApprovalResult.Approved>(parent.approve(submission.id) { parent.authorization() })
        parentEngine.synced()
        kidEngine.synced()
    }

    @Test
    fun aChoreApprovedOnTheParentDevicePaysOnTheKidDevice() = runTest {
        household()
        assertEquals(listOf(bed), kid.chores.observeChores(KID_X).first().map { it.id })

        approvedChore()

        val money = with(kid) { kid.accounts.accountsFor(KID_X).first().money() }
        assertEquals(Money(150), balances(kid)[money.id])
        assertEquals(balances(parent), balances(kid))
        val payment = kid.database.ledgerEventDao().all().single()
        assertTrue(payment.parentSignature != null, "the parent-signed ChoreRewarded reached the kid device")
        assertEquals(
            listOf(SubmissionStatus.APPROVED),
            kid.chores.observeSubmissions(SubmissionFilter.ForKid(KID_X)).first().map { it.status },
        )
    }

    @Test
    fun aPurchaseOnTheKidDeviceShowsOnTheParentDevice() = runTest {
        household()
        approvedChore()

        assertIs<PurchaseResult.Purchased>(kid.purchase(KID_X, screenTime))
        kidEngine.synced()
        val pulled = parentEngine.synced()

        assertEquals(2, pulled.pulled.events.applied)
        val accounts = kid.accounts.accountsFor(KID_X).first()
        val money = with(kid) { accounts.money() }
        val minutes = with(kid) { accounts.minutes() }
        assertEquals(Money(50), balances(parent)[money.id])
        assertEquals(Money(30), balances(parent)[minutes.id])
        assertEquals(balances(kid), balances(parent))
        assertEquals(
            Currency.Minutes,
            parent.accounts.accountsFor(KID_X).first().single {
                it.id == minutes.id
            }.currency,
        )
    }

    @Test
    fun aParentOnlyEventForgedOnTheKidDeviceIsQuarantinedOnTheParentDevice() = runTest {
        household()
        approvedChore()
        val money = with(kid) { kid.accounts.accountsFor(KID_X).first().money() }
        // The kid device signs an allowance as a "parent", with no parent signature. The rules refuse it from a kid
        // member, so it can only reach the server through a rules bug or a compromised server.
        val forged = kid.craft(KID_X, money.id, role = Role.PARENT) {
            AllowancePaid(it, kid.gbp, Money(10_000), "weekly", "2026-W45")
        }
        val pushed = kidTransport.push(listOf(forged), emptyList())
        assertEquals(FakeBackend.PERMISSION_DENIED, (pushed.rejected.single().second as SyncError.Permanent).code)
        backend.injectEvent(forged)

        val report = parentEngine.synced()
        kidEngine.synced()

        assertEquals(1, report.quarantined)
        val rejected = parent.ledger.observeQuarantined().first().single()
        assertEquals(kid.deviceId, rejected.authorDeviceId)
        assertEquals(QuarantineReason.ROLE_NOT_ALLOWED.name, rejected.reason)
        assertEquals(Money(150), balances(parent)[money.id])
        // The kid device refuses its own forgery the same way when it pulls it back.
        assertEquals(
            QuarantineReason.ROLE_NOT_ALLOWED.name,
            kid.ledger.observeQuarantined().first().single().reason,
        )
        assertEquals(balances(kid), balances(parent))
    }

    @Test
    fun anEventRefusedByTheBackendBlocksTheKidsOutboxUntilTheDeviceIsApprovedAgain() = runTest {
        household()
        approvedChore()
        // The kid device is back to PENDING on the server (D-M3-33): the rules refuse everything it writes.
        backend.setStatus(kid.deviceId, DeviceStatus.Pending)
        assertIs<PurchaseResult.Purchased>(kid.purchase(KID_X, screenTime))

        val refused = kidEngine.syncOnce()

        assertTrue(refused.rejections.isNotEmpty())
        assertTrue(refused.rejections.all { it.blocking && it.code == FakeBackend.PERMISSION_DENIED })
        kid.outbox.auditRejections(refused.rejections)
        val blocked = assertNotNull(kid.outbox.observeBlocked().first())
        assertEquals(refused.rejections.minOf { it.seq }, blocked.seq)
        assertEquals(FakeBackend.PERMISSION_DENIED, blocked.code)
        val audited = kid.database.auditLogDao().all().count { it.action == AuditAction.SYNC_REJECTED }
        assertEquals(refused.rejections.size, audited)

        // Offered again an hour later and refused again: still blocked, not audited again.
        clock.advanceBy(SyncEngine.EVENT_REJECTION_RETRY_MS)
        kid.outbox.auditRejections(kidEngine.syncOnce().rejections)
        assertEquals(audited, kid.database.auditLogDao().all().count { it.action == AuditAction.SYNC_REJECTED })
        assertEquals(blocked, kid.outbox.observeBlocked().first())

        // Approved again: the next attempt goes through, nothing blocks, and both devices agree.
        backend.approve(kid.deviceId)
        clock.advanceBy(SyncEngine.EVENT_REJECTION_RETRY_MS)
        kidEngine.synced()
        assertNull(kid.outbox.observeBlocked().first())
        parentEngine.synced()
        assertEquals(balances(kid), balances(parent))
        val minutes = with(kid) { kid.accounts.accountsFor(KID_X).first().minutes() }
        assertEquals(Money(30), balances(parent)[minutes.id])
    }

    @Test
    fun aRewardEditedOnTheKidDeviceIsRejectedByTheBackendAndNeverApplied() = runTest {
        household()
        val reward = kid.rewards.get(screenTime)!!
        kid.rewards.upsert(reward.copy(cost = Money(1)))

        val report = kidEngine.synced()

        val rejection = report.rejections.single()
        assertEquals("document/${DocTypes.REWARD}/${screenTime.value}", rejection.id.value)
        assertEquals(FakeBackend.PERMISSION_DENIED, rejection.code)
        kid.outbox.auditRejections(report.rejections)
        assertTrue(kid.database.auditLogDao().all().any { it.action == AuditAction.SYNC_REJECTED })
        assertEquals(emptyList(), kid.database.outboxDao().all(), "acked: retrying cannot help")

        parentEngine.synced()
        assertEquals(Money(100), parent.rewards.get(screenTime)?.cost)
        assertEquals(
            Money(100),
            backend.document(DocTypes.REWARD, screenTime.value)?.payload?.let {
                DocumentPayloads.decodeOrNull(Reward.serializer(), it)?.cost
            },
        )
    }
}
