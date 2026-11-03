package dev.kpetriv.sprout.core.database

import dev.kpetriv.sprout.core.database.entity.AuditAction
import dev.kpetriv.sprout.core.domainledger.DeviceStatus
import dev.kpetriv.sprout.core.domainledger.chat.ThreadKeyDocument
import dev.kpetriv.sprout.core.sync.DocTypes
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class ThreadKeyMaintenanceTest {
    private val parent = parentDevice()
    private val kid = kidDevice()
    private val secondParent = TestHousehold(
        RuntimeEnvironment.getApplication(),
        sessionFor = TestHousehold.parentSession(HOME, "parent-2"),
    )

    @After
    fun close() {
        parent.close()
        kid.close()
        secondParent.close()
    }

    private suspend fun TestHousehold.published(): List<ThreadKeyDocument> = queuedDocuments()
        .filter { it.docType == DocTypes.THREAD_KEY }
        .map { assertNotNull(ThreadKeyDocument.fromPayload(it.payload)) }

    private suspend fun TestHousehold.keyOf(epoch: Long): ByteArray? =
        database.threadKeyDao().all().filter { it.epoch == epoch }.firstNotNullOfOrNull { it.key }

    @Test
    fun theFirstParentWithAKeyCreatesEpochZeroForEveryMember() = runTest {
        registerChatDevices(parent, kid)
        parent.addKid(KID_X.value)

        assertEquals(1, parent.threadKeyMaintenance())

        val document = parent.published().single()
        assertEquals(THREAD_X, document.threadId)
        assertEquals(0, document.epoch)
        assertEquals(setOf(parent.deviceId, kid.deviceId), document.recipients)
        assertNotNull(parent.keyOf(0))
        assertEquals(1, parent.database.auditLogDao().all().count { it.action == AuditAction.THREAD_KEY_PUBLISHED })
        // Nothing changed: nothing more to publish.
        assertEquals(0, parent.threadKeyMaintenance())
        // Kid devices never publish.
        assertEquals(0, kid.threadKeyMaintenance())
    }

    @Test
    fun aNewMemberGetsTheCurrentEpochReWrapped() = runTest {
        registerChatDevices(parent)
        parent.addKid(KID_X.value)
        parent.threadKeyMaintenance()
        val epochZero = assertNotNull(parent.keyOf(0))

        // The kid device links later.
        parent.applier.applyRegistry(listOf(kid.registeredWithKey()), emptyList())
        kid.applier.applyRegistry(listOf(parent.registeredWithKey(), kid.registeredWithKey()), emptyList())
        assertEquals(1, parent.threadKeyMaintenance())

        val document = parent.published().single()
        assertEquals(0, document.epoch, "same epoch, superset of recipients")
        assertEquals(setOf(parent.deviceId, kid.deviceId), document.recipients)
        kid.applier.applyDocuments(parent.queuedDocuments())
        assertContentEquals(epochZero, kid.keyOf(0))
    }

    @Test
    fun aRevokedMemberRotatesToTheNextEpochForTheRemainingMembers() = runTest {
        registerChatDevices(parent, kid)
        parent.addKid(KID_X.value)
        parent.threadKeyMaintenance()

        parent.applier.applyRegistry(listOf(kid.registeredWithKey(DeviceStatus.Revoked(atLamport = 500))), emptyList())
        assertEquals(1, parent.threadKeyMaintenance())

        val rotated = parent.published().single { it.epoch == 1L }
        assertEquals(setOf(parent.deviceId), rotated.recipients)
        val zero = assertNotNull(parent.keyOf(0))
        val one = assertNotNull(parent.keyOf(1))
        assertTrue(!zero.contentEquals(one), "a fresh key")
        assertEquals(0, parent.threadKeyMaintenance())
    }

    @Test
    fun archivingTheKidRotatesItsDevicesOut() = runTest {
        registerChatDevices(parent, kid)
        val (kidX, _) = parent.addKid(KID_X.value)
        parent.threadKeyMaintenance()

        parent.kids.archive(kidX.id)
        assertEquals(1, parent.threadKeyMaintenance())

        assertEquals(setOf(parent.deviceId), parent.published().single { it.epoch == 1L }.recipients)
    }

    @Test
    fun aSecondParentAddedLaterIsWrappedIntoTheCurrentEpochAndCanReadIt() = runTest {
        registerChatDevices(parent, kid)
        parent.addKid(KID_X.value)
        parent.threadKeyMaintenance()
        parent.confirmThreadKeys()
        val id = parent.messageWrites.send(THREAD_X, text("for both parents"))

        registerChatDevices(parent, kid, secondParent)
        assertEquals(1, parent.threadKeyMaintenance())
        assertEquals(
            setOf(parent.deviceId, kid.deviceId, secondParent.deviceId),
            parent.published().single().recipients,
        )

        secondParent.pullChatFrom(parent)
        assertEquals(text("for both parents"), secondParent.row(id.value)?.content())
        // The second parent holds the latest key and sees nothing to do.
        assertEquals(0, secondParent.threadKeyMaintenance())
    }

    @Test
    fun concurrentRunsPlanInsideOneLockAndPublishOneKeyPerEpoch() = runTest {
        registerChatDevices(parent, kid)
        parent.addKid(KID_X.value)

        val published = List(4) { async { parent.threadKeyMaintenance() } }.awaitAll()

        assertEquals(1, published.sum())
        assertEquals(1, parent.database.threadKeyDao().all().size)
        assertEquals(1, parent.published().size)
    }

    @Test
    fun aThreadThatCannotBeKeyedDoesNotStopTheOthers() = runTest {
        registerChatDevices(parent, kid)
        // A kid id so long that "<kidId>.<epoch>.<creator>" is no valid document id: that thread is skipped.
        parent.addKid("k".repeat(100))
        parent.addKid(KID_X.value)

        assertEquals(1, parent.threadKeyMaintenance())
        assertEquals(THREAD_X, parent.published().single().threadId)
    }

    @Test
    fun anUnconfirmedKeyIsSettledByTheNextRun() = runTest {
        registerChatDevices(parent, kid)
        parent.addKid(KID_X.value)
        parent.threadKeyMaintenance()
        assertTrue(parent.database.threadKeyDao().all().none { it.confirmed })
        parent.database.outboxDao().all().forEach { parent.outbox.ack(it.seq) }

        assertEquals(0, parent.threadKeyMaintenance())

        assertTrue(parent.database.threadKeyDao().all().all { it.confirmed })
        assertTrue(parent.threadKeyWrites.confirmed(parent.database.threadKeyDao().all().single().sourceDocId))
    }
}
