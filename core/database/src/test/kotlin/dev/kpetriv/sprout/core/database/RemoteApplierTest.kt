package dev.kpetriv.sprout.core.database

import dev.kpetriv.sprout.core.database.entity.AuditAction
import dev.kpetriv.sprout.core.domainhousehold.Chore
import dev.kpetriv.sprout.core.domainhousehold.ChoreReward
import dev.kpetriv.sprout.core.domainhousehold.SavingsGoal
import dev.kpetriv.sprout.core.domainledger.DeviceOwner
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.domainledger.RegisteredDevice
import dev.kpetriv.sprout.core.domainledger.chat.ThreadMembers
import dev.kpetriv.sprout.core.model.ChoreId
import dev.kpetriv.sprout.core.model.ChoreSchedule
import dev.kpetriv.sprout.core.model.GoalId
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.model.SubmissionStatus
import dev.kpetriv.sprout.core.model.UserId
import dev.kpetriv.sprout.core.sync.DocTypes
import dev.kpetriv.sprout.core.sync.DocumentApplyReport
import dev.kpetriv.sprout.core.sync.DocumentRejection
import dev.kpetriv.sprout.core.sync.SyncCursor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [RoomRemoteApplier] between a parent device and a kid device of one household. */
@RunWith(RobolectricTestRunner::class)
class RemoteApplierTest {
    private val parent = parentDevice()
    private val kid = kidDevice()
    private val today = dev.kpetriv.sprout.core.model.CalendarDate(2026, 11, 2)

    @After
    fun close() {
        parent.close()
        kid.close()
    }

    private fun chore(id: String = "bed", title: String = "Make your bed") = Chore(
        id = ChoreId(id),
        householdId = HOME,
        kidId = KID_X,
        title = title,
        reward = ChoreReward(money = Money(20)),
        schedule = ChoreSchedule.Daily.EVERY_DAY,
        createdAt = parent.clock.now(),
    )

    @Test
    fun documentsApplyInRegistryOrderWhateverOrderTheyArrive() = runTest {
        registerEachOther(parent, kid)
        parent.addKid(KID_X.value)
        parent.chores.upsert(chore())
        val documents = parent.queuedDocuments()

        val report = kid.applier.applyDocuments(documents.reversed())

        assertEquals(DocumentApplyReport(applied = documents.size), report)
        assertNotNull(kid.kids.getKid(KID_X))
        assertEquals(3, kid.accounts.accountsFor(KID_X).first().size)
        assertEquals(listOf(ChoreId("bed")), kid.chores.observeChores(KID_X).first().map { it.id })
        val applied = kid.database.auditLogDao().all().filter { it.action == AuditAction.DOCUMENT_APPLIED }
        assertTrue("\"docType\":\"kid\"" in applied.first().detailsJson)
        assertTrue("\"docType\":\"chore\"" in applied.last().detailsJson)
        val accountIds = kid.accounts.accountsFor(KID_X).first().map { it.id }.toSet()
        assertEquals(parent.accounts.accountsFor(KID_X).first().map { it.id }.toSet(), accountIds)
        // The rows carry the author's stamp and lamport.
        val row = kid.database.choreDao().get(ChoreId("bed"))!!
        assertEquals(parent.deviceId, row.stamp.authorDeviceId)
        assertEquals(documents.single { it.docType == DocTypes.CHORE }.signature, row.stamp.signature)
        // Applying the same documents again is idempotent.
        assertEquals(DocumentApplyReport(ignored = documents.size), kid.applier.applyDocuments(documents))
    }

    @Test
    fun aTombstoneHidesTheRowAndALateUpdateCannotResurrectIt() = runTest {
        registerEachOther(parent, kid)
        parent.addKid(KID_X.value)
        kid.applier.applyDocuments(parent.queuedDocuments())
        val goal = SavingsGoal(GoalId("bike"), KID_X, "Bike", Money(5_000), today)
        parent.goals.upsert(goal)
        val live = parent.queuedDocuments().single { it.docType == DocTypes.SAVINGS_GOAL }
        kid.applier.applyDocuments(listOf(live))
        assertEquals(listOf(goal.id), kid.goals.observeGoals(KID_X).first().map { it.id })

        parent.goals.delete(goal.id)
        val tombstone = parent.queuedDocuments().single { it.docType == DocTypes.SAVINGS_GOAL }
        assertTrue(tombstone.deleted)
        assertEquals(DocumentApplyReport(applied = 1), kid.applier.applyDocuments(listOf(tombstone)))

        assertEquals(emptyList(), kid.goals.observeGoals(KID_X).first())
        assertNull(kid.goals.get(goal.id))
        assertTrue(kid.database.savingsGoalDao().get(goal.id)!!.stamp.deleted)
        // The older live copy arriving late is ignored.
        assertEquals(DocumentApplyReport(ignored = 1), kid.applier.applyDocuments(listOf(live)))
        assertNull(kid.goals.get(goal.id))
        assertNull(parent.goals.get(goal.id))
    }

    @Test
    fun aKidDeviceMayOnlyWriteItsOwnSubmissionsAndRejectionsAreAuditedWithoutContent() = runTest {
        registerEachOther(parent, kid)
        parent.addKid(KID_X.value)
        parent.chores.upsert(chore())
        kid.applier.applyDocuments(parent.queuedDocuments())
        // The kid device writes a chore of its own (allowed locally, refused by every receiver) and submits.
        kid.chores.upsert(chore(id = "candy", title = "Secret title"))
        kid.chores.submit(ChoreId("bed"), today)
        val fromKid = kid.queuedDocuments()

        val report = parent.applier.applyDocuments(fromKid)

        assertEquals(1, report.applied)
        assertEquals(mapOf(DocumentRejection.ROLE_NOT_ALLOWED to 1), report.rejected)
        assertNull(parent.chores.get(ChoreId("candy")))
        assertEquals(
            listOf(SubmissionStatus.SUBMITTED),
            parent.chores.observeSubmissions(SubmissionFilter.PendingForParent).first().map { it.status },
        )
        val rejected = parent.database.auditLogDao().all().single { it.action == AuditAction.DOCUMENT_REJECTED }
        assertEquals("candy", rejected.target)
        assertEquals(kid.deviceId, rejected.deviceId)
        assertTrue(DocumentRejection.ROLE_NOT_ALLOWED.name in rejected.detailsJson)
        assertFalse("Secret" in rejected.detailsJson)
    }

    @Test
    fun aDocumentTooFarAheadGoesToTheDocumentInbox() = runTest {
        registerEachOther(parent, kid)
        val signer = parent.signer
        val ahead = dev.kpetriv.sprout.core.sync.DocumentEnvelope(
            householdId = HOME,
            docType = DocTypes.CHORE,
            docId = "bed",
            authorUid = parent.session.uid,
            authorDeviceId = parent.deviceId,
            lamport = dev.kpetriv.sprout.core.domainledger.LedgerPolicy.DEFAULT_MAX_LAMPORT_JUMP + 10,
            payload = DocumentPayloads.encode(Chore.serializer(), chore()),
        ).let { it.copy(signature = kotlin.io.encoding.Base64.Default.encode(signer.sign(it.signedPayload()))) }

        val report = kid.applier.applyDocuments(listOf(ahead))

        assertEquals(mapOf(DocumentRejection.LAMPORT_TOO_FAR_AHEAD to 1), report.rejected)
        assertEquals(listOf("bed"), kid.database.documentInboxDao().all().map { it.docId })
        assertNull(kid.chores.get(ChoreId("bed")))
        assertTrue(kid.applier.localMaxLamport() < ahead.lamport)
    }

    @Test
    fun theCursorIsKeptPerCollection() = runTest {
        assertEquals(SyncCursor.START, kid.applier.cursor())
        kid.applier.saveCursor(SyncCursor(mapOf("events" to "4", "documents" to "2")))
        kid.applier.saveCursor(SyncCursor(mapOf("events" to "9")))
        assertEquals(SyncCursor(mapOf("events" to "9", "documents" to "2")), kid.applier.cursor())
    }

    @Test
    fun theCursorBelongsToTheSessionsHousehold() = runTest {
        kid.applier.saveCursor(SyncCursor(mapOf("events" to "4")))
        val home = kid.sessions.get()!!

        kid.sessions.save(home.copy(householdId = dev.kpetriv.sprout.core.model.HouseholdId("other-home")))
        assertEquals(SyncCursor.START, kid.applier.cursor(), "another household starts from the beginning")
        kid.applier.saveCursor(SyncCursor(mapOf("events" to "1")))

        kid.sessions.save(home)
        assertEquals(SyncCursor(mapOf("events" to "4")), kid.applier.cursor())
        kid.sessions.clear()
        assertEquals(SyncCursor.START, kid.applier.cursor(), "nothing to resume without a session")
        kid.applier.saveCursor(SyncCursor(mapOf("events" to "7")))
        val rows = kid.database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM sync_cursor").use {
            it.moveToFirst()
            it.getInt(0)
        }
        assertEquals(2, rows, "a position without a session is not saved")
    }

    @Test
    fun aKnownDevicesOwnerIsPinnedAndAChangeExcludesIt() = runTest {
        // Audit I1: the server writes a device row once; a row that later names another owner is not believed.
        val registered = kid.registeredWithKey()
        parent.applier.applyRegistry(listOf(registered), emptyList())
        fun owned(owner: DeviceOwner) = RegisteredDevice(
            registered.deviceId,
            owner,
            registered.publicKey,
            registered.status,
            registered.attestation,
            registered.encryptionKey,
        )
        val promoted = owned(DeviceOwner(registered.uid, Role.PARENT))
        repeat(2) { parent.applier.applyRegistry(listOf(promoted), emptyList()) }
        parent.applier.applyRegistry(listOf(owned(DeviceOwner(UserId("someone-else"), Role.KID, KID_X))), emptyList())

        val pinned = assertNotNull(parent.registry.device(kid.deviceId))
        assertEquals(registered.owner, pinned.owner)
        assertTrue(pinned.encryptionKeyConflict)
        val changed = parent.database.auditLogDao().all().filter { it.action == AuditAction.DEVICE_KEY_CHANGED }
        assertEquals(1, changed.size, "audited once")
        assertTrue("\"key\":\"owner\"" in changed.single().detailsJson)
        val membership = ThreadMembers.resolve(THREAD_X, parent.registry.devicesOf(HOME), parent.signatureCheck)
        assertTrue(kid.deviceId in membership.excluded)
    }
}
