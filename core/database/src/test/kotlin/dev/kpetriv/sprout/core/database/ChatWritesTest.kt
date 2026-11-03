package dev.kpetriv.sprout.core.database

import dev.kpetriv.sprout.core.database.entity.AuditAction
import dev.kpetriv.sprout.core.database.entity.MessageStatus
import dev.kpetriv.sprout.core.database.entity.OutboxKind
import dev.kpetriv.sprout.core.domainledger.JvmSha256Hasher
import dev.kpetriv.sprout.core.domainledger.chat.BlobJson
import dev.kpetriv.sprout.core.domainledger.chat.ChatPhotoRef
import dev.kpetriv.sprout.core.domainledger.chat.MessageKind
import dev.kpetriv.sprout.core.domainledger.chat.MessagePlaintext
import dev.kpetriv.sprout.core.domainledger.toHex
import dev.kpetriv.sprout.core.model.BlobId
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.model.SubmissionId
import dev.kpetriv.sprout.core.model.ThreadId
import dev.kpetriv.sprout.core.sync.BlobChunkJson
import dev.kpetriv.sprout.core.sync.OutboxItem
import dev.kpetriv.sprout.core.sync.SyncError
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class ChatWritesTest {
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
        kid.applier.applyDocuments(parent.queuedDocuments())
    }

    @Test
    fun withoutAThreadKeyNothingIsSentOrQueued() = runTest {
        registerChatDevices(parent, kid)
        parent.addKid(KID_X.value)
        val outboxBefore = parent.database.outboxDao().count()

        assertFailsWith<ThreadKeyUnavailableException> { parent.messageWrites.send(THREAD_X, text("hi")) }
        assertFailsWith<ThreadKeyUnavailableException> {
            parent.blobWrites.store(BlobTarget.Thread(THREAD_X), byteArrayOf(1, 2, 3))
        }
        assertEquals(outboxBefore, parent.database.outboxDao().count())
        assertTrue(parent.database.messageDao().all().isEmpty())
        assertFalse(parent.threadKeyStatus.observeSendable(THREAD_X).first())
    }

    @Test
    fun sendsTakeMonotonicSeqsAndLamportsAndQueueSignedEnvelopes() = runTest {
        linked()
        assertTrue(kid.threadKeyStatus.observeSendable(THREAD_X).first())
        val ids = listOf("one", "two", "three").map { kid.messageWrites.send(THREAD_X, text(it)) }

        val queued = kid.queuedMessages().map { it.envelope() }
        assertEquals(ids, queued.map { it.messageId })
        assertEquals(listOf(1L, 2L, 3L), queued.map { it.seq })
        assertEquals(queued.map { it.lamport }.sorted().distinct(), queued.map { it.lamport })
        assertTrue(queued.all { it.authorRole == Role.KID && it.authorDeviceId == kid.deviceId && it.epoch == 0L })
        assertTrue(queued.all { it.verifyWith(kid.signatureCheck, kid.keyStore.publicKey().encoded) })
        val rows = ids.map { assertNotNull(kid.row(it.value)) }
        assertTrue(rows.all { it.status == MessageStatus.SENT_LOCAL })
        assertEquals(text("two"), rows[1].content())
        assertEquals(3, kid.database.auditLogDao().all().count { it.action == AuditAction.MESSAGE_SENT })
        assertTrue(kid.database.auditLogDao().all().none { it.detailsJson.contains("three") })
        // The parent reads them in order.
        parent.applier.applyMessages(kid.queuedMessages())
        assertEquals(listOf("one", "two", "three").map(::text), ids.map { parent.row(it.value)?.content() })
    }

    @Test
    fun aKidDeviceCannotWriteIntoAnotherKidsThread() = runTest {
        linked()
        assertFailsWith<IllegalArgumentException> {
            kid.messageWrites.send(ThreadId("kid-other"), text("hi"))
        }
        assertFailsWith<IllegalArgumentException> {
            kid.blobWrites.store(BlobTarget.ChorePhoto(KidId("kid-other"), SubmissionId("s")), byteArrayOf(1))
        }
    }

    @Test
    fun aBlobIsQueuedBeforeTheMessageThatNamesIt() = runTest {
        linked()
        val photo = Random(7).nextBytes(PHOTO_BYTES)
        val blobId = kid.blobWrites.store(BlobTarget.Thread(THREAD_X), photo)
        val content = MessagePlaintext.Photo(ChatPhotoRef(JvmSha256Hasher.sha256(photo).toHex(), 640, 480))
        val messageId = kid.messageWrites.send(THREAD_X, content, blobId)

        val due = kid.outbox.due(Long.MAX_VALUE, limit = 10)
        assertEquals(
            listOf(OutboxItem.Kind.BLOB_CHUNK, OutboxItem.Kind.BLOB_CHUNK, OutboxItem.Kind.MESSAGE),
            due.map { it.kind },
        )
        val headers = due.take(2).map { assertNotNull(BlobJson.decodeOrNull(requireNotNull(it.blobChunk).header)) }
        assertEquals(listOf(0, 1), headers.map { it.chunk })
        assertTrue(
            headers.zip(due.take(2)).all { (header, item) ->
                header.verifyWith(kid.signatureCheck, kid.keyStore.publicKey().encoded, item.blobChunk!!.data)
            },
        )
        val message = requireNotNull(due.last().messageJson).envelope()
        assertEquals(messageId, message.messageId)
        assertEquals(blobId, message.blobId)
        assertEquals(MessageKind.PHOTO, message.kind)
        // The sender reads its own blob from the cache.
        assertContentEquals(photo, kid.chatBlobs.open(blobId))
        // The parent fetches the chunks, verifies, caches and decrypts them.
        parent.applier.applyMessages(kid.queuedMessages())
        assertNull(parent.chatBlobs.open(blobId))
        assertContentEquals(photo, parent.chatBlobs.ingest(blobId, kid.queuedBlobChunks()))
        assertContentEquals(photo, parent.chatBlobs.open(blobId))
    }

    @Test
    fun tamperedOrForeignBlobChunksAreRefused() = runTest {
        linked()
        val blobId = kid.blobWrites.store(BlobTarget.Thread(THREAD_X), byteArrayOf(1, 2, 3, 4))
        val chunk = kid.queuedBlobChunks().single()
        val flipped = chunk.data.also { it[0] = (it[0].toInt() xor 1).toByte() }

        val invalid = assertFailsWith<BlobUnavailableException> {
            parent.chatBlobs.ingest(blobId, listOf(BlobChunkJson(chunk.header, flipped)))
        }
        assertEquals(BlobUnavailableException.Reason.INVALID, invalid.reason)
        assertEquals(
            BlobUnavailableException.Reason.INVALID,
            assertFailsWith<BlobUnavailableException> {
                parent.chatBlobs.ingest(BlobId("another"), listOf(chunk))
            }.reason,
        )
        assertEquals(
            BlobUnavailableException.Reason.NOT_FOUND,
            assertFailsWith<BlobUnavailableException> { parent.chatBlobs.ingest(blobId, emptyList()) }.reason,
        )
    }

    @Test
    fun aMessageNamingAnUnknownBlobIsRefused() = runTest {
        linked()
        val content = MessagePlaintext.Voice(durationMs = 1_000)
        assertFailsWith<IllegalArgumentException> {
            kid.messageWrites.send(THREAD_X, content, BlobId("nowhere"))
        }
        assertFailsWith<IllegalArgumentException> { kid.messageWrites.send(THREAD_X, content) }
    }

    @Test
    fun theReaderShowsSendStatesAndTheThreadListCountsUnread() = runTest {
        linked()
        val mine = parent.messageWrites.send(THREAD_X, text("from mum"))
        var page = parent.messageReader.observe(THREAD_X).first()
        assertEquals(MessageSendState.SENDING, page.messages.single().sendState)
        assertTrue(page.messages.single().fromThisDevice)

        val sent = parent.queuedMessages()
        val seq = parent.database.outboxDao().all().single { it.kind == OutboxKind.MESSAGE }.seq
        parent.outbox.ack(seq)
        page = parent.messageReader.observe(THREAD_X).first()
        assertEquals(MessageSendState.SENT, page.messages.single().sendState)

        // The kid has seen mum's message, so its reply sorts after it (lamport order).
        kid.applier.applyMessages(sent)
        val theirs = kid.messageWrites.send(THREAD_X, text("from leo"))
        parent.applier.applyMessages(kid.queuedMessages())
        page = parent.messageReader.observe(THREAD_X, limit = 1).first()
        assertEquals(listOf(theirs), page.messages.map { it.messageId })
        assertTrue(page.hasMore)
        assertEquals(text("from leo"), page.messages.single().content)
        assertEquals(mine, parent.messageReader.observe(THREAD_X).first().messages.first().messageId)

        var thread = parent.chatThreads.observeThreads().first().single()
        assertEquals(1, thread.unreadCount)
        assertEquals(theirs, thread.lastMessage?.messageId)
        assertFalse(thread.toString().contains("Leo"))

        parent.chatThreads.markRead(THREAD_X)
        thread = parent.chatThreads.observeThreads().first().single()
        assertEquals(0, thread.unreadCount)
    }

    @Test
    fun retryMakesAQueuedMessageDueAtOnce() = runTest {
        linked()
        val id = kid.messageWrites.send(THREAD_X, text("again"))
        val row = kid.database.outboxDao().all().single { it.kind == OutboxKind.MESSAGE }
        kid.outbox.fail(row.seq, SyncError.Transient(), Long.MAX_VALUE)
        assertTrue(kid.outbox.due(0, 10).isEmpty())

        assertTrue(kid.messageWrites.retry(id))
        assertEquals(1, kid.outbox.due(0, 10).size)
    }

    private companion object {
        /** Large enough to need two chunks. */
        const val PHOTO_BYTES = 900 * 1024
    }
}
