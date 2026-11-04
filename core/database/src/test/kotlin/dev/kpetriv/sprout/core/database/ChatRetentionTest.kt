package dev.kpetriv.sprout.core.database

import dev.kpetriv.sprout.core.database.entity.AuditAction
import dev.kpetriv.sprout.core.database.entity.OutboxKind
import dev.kpetriv.sprout.core.domainledger.chat.MessagePlaintext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

/** The chat retention on a device (M7 §2, D-M7-8, ADR-0011): [PruneChatUseCase] and [PurgeChatUseCase]. */
@RunWith(RobolectricTestRunner::class)
class ChatRetentionTest {
    private val parent = parentDevice()
    private val kid = kidDevice()

    @After
    fun close() {
        parent.close()
        kid.close()
    }

    private suspend fun linked() {
        registerChatDevices(parent, kid)
        parent.addKid(KID_X.value)
        parent.threadKeyMaintenance()
        parent.confirmThreadKeys()
        kid.applier.applyDocuments(parent.queuedDocuments())
    }

    private fun advance(by: Duration) {
        parent.clock.advanceBy(by)
        kid.clock.advanceBy(by)
    }

    /** What the server would have acknowledged: this device's queued chat rows leave its outbox. */
    private suspend fun TestHousehold.acknowledgeChat() {
        database.outboxDao().all()
            .filter { it.kind == OutboxKind.MESSAGE || it.kind == OutboxKind.BLOB_CHUNK }
            .forEach { database.outboxDao().delete(it.seq) }
    }

    private suspend fun TestHousehold.unread(): Int =
        chatThreads.observeThreads().first().single { it.threadId == THREAD_X }.unreadCount

    private suspend fun TestHousehold.messageIds(): Set<String> =
        database.messageDao().all().map { it.messageId }.toSet()

    @Test
    fun withoutARetentionNothingIsPruned() = runTest {
        linked()
        kid.messageWrites.send(THREAD_X, text("hello"))
        parent.applier.applyMessages(kid.queuedMessages())
        advance(400.days)

        assertEquals(ChatPruneOutcome.KeepAll, parent.pruneChat())
        assertEquals(1, parent.database.messageDao().all().size)
    }

    @Test
    fun messagesOlderThanTheRetentionGoAndTheUnreadCountStaysRight() = runTest {
        linked()
        parent.policies.setChatRetention(30)
        val old = listOf("one", "two").map { kid.messageWrites.send(THREAD_X, text(it)) }
        parent.applier.applyMessages(kid.queuedMessages())
        parent.chatThreads.markRead(THREAD_X)
        // The parent's own reply is still waiting for the server: it must survive any prune.
        val queued = parent.messageWrites.send(THREAD_X, text("on its way"))
        advance(40.days)
        val recent = kid.messageWrites.send(THREAD_X, text("three"))
        parent.applier.applyMessages(kid.queuedMessages())
        assertEquals(1, parent.unread())
        val keysBefore = parent.database.threadKeyDao().all().size
        val seqBefore = parent.database.messageDao().seq(THREAD_X.value, kid.deviceId)

        val outcome = assertIs<ChatPruneOutcome.Pruned>(parent.pruneChat())

        val result = outcome.result
        assertEquals(parent.clock.now() - 30.days, result.before)
        assertEquals(2, result.messages)
        assertEquals(1, result.readMarks, "the read mark predates the cut")
        assertEquals(1, result.keptQueued)
        assertEquals(setOf(queued.value, recent.value), parent.messageIds())
        assertEquals(1, parent.unread(), "the unread count is what it was")
        assertEquals(keysBefore, parent.database.threadKeyDao().all().size, "thread keys stay")
        assertEquals(seqBefore, parent.database.messageDao().seq(THREAD_X.value, kid.deviceId), "message_seq stays")
        val audit = parent.database.auditLogDao().all().single { it.action == AuditAction.CHAT_PRUNED }
        assertTrue("\"messages\":2" in audit.detailsJson)
        assertFalse(listOf("one", "two", "three").any { it in audit.detailsJson })

        // Pulling the pruned messages again (another page, a re-delivery) stores nothing: their seqs are used up.
        parent.applier.applyMessages(kid.queuedMessages())
        assertEquals(setOf(queued.value, recent.value), parent.messageIds())
        assertTrue(old.none { parent.row(it.value) != null })

        // Idempotent.
        val again = assertIs<ChatPruneOutcome.Pruned>(parent.pruneChat()).result
        assertEquals(0, again.messages + again.blobs + again.readMarks)
        assertEquals(1, parent.database.auditLogDao().all().count { it.action == AuditAction.CHAT_PRUNED })

        // Once the server has the queued reply, the next prune takes it too.
        parent.acknowledgeChat()
        assertEquals(1, assertIs<ChatPruneOutcome.Pruned>(parent.pruneChat()).result.messages)
        assertEquals(setOf(recent.value), parent.messageIds())
    }

    @Test
    fun aPrunedMediaMessageTakesItsCachedBlobAndNothingElse() = runTest {
        linked()
        parent.policies.setChatRetention(7)
        kid.applier.applyDocuments(parent.queuedDocuments())
        val clip = Random(5).nextBytes(64)
        val (oldMessage, oldBlob) = kid.messageWrites.sendWithBlob(THREAD_X, MessagePlaintext.Voice(1_000), clip)
        kid.acknowledgeChat()
        advance(10.days)
        val (_, newBlob) = kid.messageWrites.sendWithBlob(THREAD_X, MessagePlaintext.Voice(2_000), clip)
        kid.acknowledgeChat()
        val files = kid.blobDirectory.listFiles().orEmpty().size
        assertEquals(2, files)

        val result = assertIs<ChatPruneOutcome.Pruned>(kid.pruneChat()).result

        assertEquals(1, result.messages)
        assertEquals(1, result.blobs)
        assertNull(kid.row(oldMessage.value))
        assertNull(kid.database.blobDao().get(oldBlob.value))
        assertNotNull(kid.database.blobDao().get(newBlob.value))
        assertEquals(1, kid.blobDirectory.listFiles().orEmpty().size, "the pruned blob's file is gone")
    }

    @Test
    fun aFutureDatedMessageGoesByWhenThisDeviceReceivedIt() = runTest {
        linked()
        parent.policies.setChatRetention(30)
        // The kid's clock runs a year ahead: its message claims a date the retention would never reach.
        kid.clock.advanceBy(365.days)
        kid.messageWrites.send(THREAD_X, text("from the future"))
        parent.applier.applyMessages(kid.queuedMessages())
        parent.clock.advanceBy(31.days)

        assertEquals(1, assertIs<ChatPruneOutcome.Pruned>(parent.pruneChat()).result.messages)
        assertTrue(parent.database.messageDao().all().isEmpty())
    }

    @Test
    fun deleteChatHistoryNowIsAParentActionThatTakesEverythingSent() = runTest {
        linked()
        kid.messageWrites.send(THREAD_X, text("one"))
        parent.applier.applyMessages(kid.queuedMessages())
        kid.acknowledgeChat()
        advance(1.minutes)

        assertFailsWith<IllegalStateException> { kid.purgeChat() }
        val result = parent.purgeChat()
        assertEquals(1, result.messages)
        assertEquals(parent.clock.now(), result.before)
        assertTrue(parent.database.messageDao().all().isEmpty())
        // An instant in the future is clamped to now.
        assertEquals(parent.clock.now(), parent.purgeChat(parent.clock.now() + 1.days).before)
    }
}
