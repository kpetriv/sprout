package dev.kpetriv.sprout.core.database

import dev.kpetriv.sprout.core.database.entity.AuditAction
import dev.kpetriv.sprout.core.database.entity.MessageEntity
import dev.kpetriv.sprout.core.database.entity.MessageStatus
import dev.kpetriv.sprout.core.database.entity.OutboxEntity
import dev.kpetriv.sprout.core.database.entity.OutboxKind
import dev.kpetriv.sprout.core.domainledger.chat.MessageJson
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
        parent.policies.ensure(parent.gbp)
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

    /** A copy of this row under [id] (another `seq`), in [threadId], received at [receivedAt]. */
    private fun MessageEntity.cloneAs(id: String, receivedAt: Long, threadId: String = this.threadId) = MessageEntity(
        messageId = id, householdId = householdId, threadId = threadId, authorUid = authorUid,
        authorDeviceId = authorDeviceId, authorRole = authorRole, seq = seq + 1_000, lamport = lamport,
        createdAtEpochMs = createdAtEpochMs, epoch = epoch, kind = kind, ciphertext = ciphertext, blobId = blobId,
        signature = signature, schemaVersion = schemaVersion, plaintext = plaintext, status = status, reason = reason,
        receivedAtEpochMs = receivedAt,
    )

    @Test
    fun aMessageFromAClockFarBehindIsKeptUntilTheRetentionPassesSinceItArrived() = runTest {
        linked()
        parent.policies.setChatRetention(30)
        // The kid's phone runs 400 days behind (a reset clock, or set back on purpose): its message is dated long
        // before the cut, yet arrives now (D-M7-52).
        parent.clock.advanceBy(400.days)
        val backdated = kid.messageWrites.send(THREAD_X, text("my clock is wrong"))
        parent.applier.applyMessages(kid.queuedMessages())
        assertNotNull(parent.row(backdated.value))

        assertEquals(0, assertIs<ChatPruneOutcome.Pruned>(parent.pruneChat()).result.messages)
        assertNotNull(parent.row(backdated.value))

        parent.clock.advanceBy(31.days)
        assertEquals(1, assertIs<ChatPruneOutcome.Pruned>(parent.pruneChat()).result.messages)
        assertNull(parent.row(backdated.value))
    }

    @Test
    fun heldAndQuarantinedRowsGoToo() = runTest {
        linked()
        parent.policies.setChatRetention(30)
        listOf("one", "two").forEach { kid.messageWrites.send(THREAD_X, text(it)) }
        val (first, second) = kid.queuedMessages().map { it.envelope() }
        // A tampered first message is quarantined; the second waits for it (a seq gap).
        parent.applier.applyMessages(listOf(MessageJson.toJsonObject(first.copy(lamport = first.lamport + 1))))
        parent.applier.applyMessages(listOf(MessageJson.toJsonObject(second)))
        assertEquals(MessageStatus.QUARANTINED, parent.row(first.messageId.value)?.status)
        assertEquals(MessageStatus.HELD, parent.row(second.messageId.value)?.status)
        parent.clock.advanceBy(31.days)

        assertEquals(2, assertIs<ChatPruneOutcome.Pruned>(parent.pruneChat()).result.messages)
        assertTrue(parent.database.messageDao().all().isEmpty())
    }

    @Test
    fun aBlobAnotherMessageOrAQueuedChunkNamesStaysAndEveryThreadIsPruned() = runTest {
        linked()
        parent.policies.setChatRetention(7)
        kid.applier.applyDocuments(parent.queuedDocuments())
        val clip = Random(7).nextBytes(64)
        val (sharedMessage, shared) = kid.messageWrites.sendWithBlob(THREAD_X, MessagePlaintext.Voice(1_000), clip)
        val (queuedMessage, queued) = kid.messageWrites.sendWithBlob(THREAD_X, MessagePlaintext.Voice(2_000), clip)
        kid.acknowledgeChat()
        advance(10.days)
        val now = kid.clock.now().toEpochMilliseconds()
        val sharedRow = assertNotNull(kid.row(sharedMessage.value))
        val oldRow = assertNotNull(kid.row(queuedMessage.value))
        // A recent message names the first blob; a chunk of the second is queued again (a re-upload).
        kid.database.messageDao().upsert(sharedRow.cloneAs("m-recent", receivedAt = now))
        kid.database.outboxDao().insert(
            OutboxEntity(
                kind = OutboxKind.BLOB_CHUNK,
                refId = "${queued.value}.0",
                payload = "{}",
                createdAtEpochMs = now,
            ),
        )
        // Old rows in another thread go as well.
        val nineDaysAgo = now - 9.days.inWholeMilliseconds
        kid.database.messageDao().upsert(oldRow.cloneAs("m-other", receivedAt = nineDaysAgo, threadId = "kid-y"))

        val result = assertIs<ChatPruneOutcome.Pruned>(kid.pruneChat()).result

        assertEquals(3, result.messages)
        assertEquals(0, result.blobs)
        assertEquals(setOf("m-recent"), kid.messageIds())
        assertNotNull(kid.database.blobDao().get(shared.value))
        assertNotNull(kid.database.blobDao().get(queued.value))
        assertEquals(2, kid.blobDirectory.listFiles().orEmpty().size)
    }

    @Test
    fun deleteChatHistoryNowDropsThisPhonesQueuedOlderMessagesAndTheirBlobs() = runTest {
        linked()
        kid.applier.applyDocuments(parent.queuedDocuments())
        val waiting = parent.messageWrites.send(THREAD_X, text("not uploaded yet"))
        val (voice, blob) =
            parent.messageWrites.sendWithBlob(THREAD_X, MessagePlaintext.Voice(1_000), Random(3).nextBytes(64))
        advance(1.minutes)
        val cut = parent.clock.now()
        advance(1.minutes)
        val newer = parent.messageWrites.send(THREAD_X, text("after the cut"))

        val result = parent.purgeChat(cut)

        assertEquals(2, result.droppedQueued)
        assertEquals(2, result.messages)
        assertEquals(1, result.blobs)
        assertNull(parent.row(waiting.value))
        assertNull(parent.row(voice.value))
        assertNull(parent.database.blobDao().get(blob.value))
        val outbox = parent.database.outboxDao().all()
            .filter { it.kind == OutboxKind.MESSAGE || it.kind == OutboxKind.BLOB_CHUNK }
        assertEquals(listOf(newer.value), outbox.map { it.refId }, "only the message after the cut still uploads")
        assertEquals(setOf(newer.value), parent.messageIds())
        val audit = parent.database.auditLogDao().all().single { it.action == AuditAction.CHAT_PRUNED }
        assertTrue("\"droppedQueued\":2" in audit.detailsJson)
    }

    @Test
    fun cacheFilesNoRowNamesAreSwept() = runTest {
        linked()
        kid.blobDirectory.mkdirs()
        val orphan = java.io.File(kid.blobDirectory, "left-behind.blob").apply { writeBytes(byteArrayOf(1)) }
        orphan.setLastModified(0)
        val temp = java.io.File(kid.blobDirectory, "half.blob.tmp").apply { writeBytes(byteArrayOf(2)) }
        temp.setLastModified(0)
        val fresh = java.io.File(kid.blobDirectory, "being-written.blob").apply { writeBytes(byteArrayOf(3)) }

        assertEquals(ChatPruneOutcome.KeepAll, kid.pruneChat())

        assertFalse(orphan.exists())
        assertFalse(temp.exists())
        assertTrue(fresh.exists(), "a file written moments ago may still be getting its row")
        assertEquals(0, kid.blobCache.sweepOrphans())
    }

    @Test
    fun queuedChunksMatchTheirBlobIdExactly() = runTest {
        val outbox = parent.database.outboxDao()
        listOf("a_c.0", "a_c.1", "abc.0", "a%c.0").forEach {
            outbox.insert(OutboxEntity(kind = OutboxKind.BLOB_CHUNK, refId = it, payload = "{}", createdAtEpochMs = 0))
        }

        assertEquals(2, outbox.countBlobChunks("a_c"))
        assertEquals(1, outbox.countBlobChunks("abc"))
        assertEquals(0, outbox.countBlobChunks("a"))
        assertEquals(1, outbox.deleteBlobChunks("a%c"))
        assertEquals(1, outbox.countBlobChunks("abc"), "a wildcard in an id matches nothing else")
    }
}
