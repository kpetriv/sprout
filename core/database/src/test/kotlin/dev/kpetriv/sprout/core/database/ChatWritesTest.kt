package dev.kpetriv.sprout.core.database

import androidx.room.withTransaction
import dev.kpetriv.sprout.core.crypto.Aead
import dev.kpetriv.sprout.core.crypto.SymmetricKey
import dev.kpetriv.sprout.core.crypto.chat.MessageCipher
import dev.kpetriv.sprout.core.database.entity.AuditAction
import dev.kpetriv.sprout.core.database.entity.MessageStatus
import dev.kpetriv.sprout.core.database.entity.OutboxEntity
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
import dev.kpetriv.sprout.core.sync.PushItemId
import dev.kpetriv.sprout.core.sync.PushRejection
import dev.kpetriv.sprout.core.sync.SyncError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.security.GeneralSecurityException
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
        parent.confirmThreadKeys()
        kid.applier.applyDocuments(parent.queuedDocuments())
    }

    private fun threadBlob(author: TestHousehold, plaintext: ByteArray? = null) = BlobExpectation(
        BlobTarget.Thread(THREAD_X),
        author.deviceId,
        plaintext?.let { JvmSha256Hasher.sha256(it).toHex() },
    )

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
        assertContentEquals(photo, kid.chatBlobs.open(blobId, threadBlob(kid, photo)))
        // The parent fetches the chunks, verifies, caches and decrypts them.
        parent.applier.applyMessages(kid.queuedMessages())
        assertNull(parent.chatBlobs.open(blobId, threadBlob(kid, photo)))
        assertContentEquals(photo, parent.chatBlobs.ingest(blobId, kid.queuedBlobChunks(), threadBlob(kid, photo)))
        assertContentEquals(photo, parent.chatBlobs.open(blobId, threadBlob(kid, photo)))
    }

    @Test
    fun aBlobThatIsNotWhatTheMessageNamesIsInvalid() = runTest {
        linked()
        val photo = byteArrayOf(9, 8, 7)
        val blobId = kid.blobWrites.store(BlobTarget.Thread(THREAD_X), photo)
        val chunks = kid.queuedBlobChunks()
        suspend fun refused(expectation: BlobExpectation) = assertEquals(
            BlobUnavailableException.Reason.INVALID,
            assertFailsWith<BlobUnavailableException> { parent.chatBlobs.ingest(blobId, chunks, expectation) }.reason,
        )
        // Another author, another target, another plaintext hash.
        refused(threadBlob(parent, photo))
        refused(BlobExpectation(BlobTarget.ChorePhoto(KID_X, SubmissionId("s-1")), kid.deviceId))
        refused(threadBlob(kid, byteArrayOf(1)))
        assertNull(parent.blobCache.get(blobId), "nothing invalid is cached")
        // The cached copy is checked the same way.
        assertContentEquals(photo, parent.chatBlobs.ingest(blobId, chunks, threadBlob(kid, photo)))
        assertEquals(
            BlobUnavailableException.Reason.INVALID,
            assertFailsWith<BlobUnavailableException> { parent.chatBlobs.open(blobId, threadBlob(parent)) }.reason,
        )
    }

    @Test
    fun aDueBatchCarriesAtMostTwoBlobChunksAndNoRowBytesAreReadForTheRest() = runTest {
        linked()
        val first = kid.blobWrites.store(BlobTarget.Thread(THREAD_X), Random(1).nextBytes(PHOTO_BYTES))
        kid.blobWrites.store(BlobTarget.Thread(THREAD_X), Random(2).nextBytes(PHOTO_BYTES))
        val due = kid.outbox.due(Long.MAX_VALUE, limit = 10)
        assertEquals(2, due.size)
        assertTrue(due.all { it.blobChunk?.header?.get("blobId")?.toString()?.contains(first.value) == true })
        assertTrue(kid.database.outboxDao().head(10).size == 4)
    }

    @Test
    fun aBlockedLedgerItemQueuedEarlierNeverHidesABlockedMessage() = runTest {
        linked()
        // A ledger event refused for good, queued before the message (a lower seq), blocks the ledger lane only.
        val eventSeq = kid.database.outboxDao().insert(
            OutboxEntity(
                kind = OutboxKind.LEDGER_EVENT,
                refId = "event-1:h",
                payload = "{}",
                createdAtEpochMs = 0,
                lamport = 1,
            ),
        )
        kid.outbox.fail(eventSeq, SyncError.Permanent("permission-denied"), Long.MAX_VALUE)
        kid.outbox.auditRejections(
            listOf(PushRejection(eventSeq, PushItemId("event/event-1"), "permission-denied", blocking = true)),
        )
        val id = kid.messageWrites.send(THREAD_X, text("refused"))
        val row = kid.database.outboxDao().all().single { it.kind == OutboxKind.MESSAGE }
        assertTrue(eventSeq < row.seq)
        assertEquals(MessageSendState.SENDING, kid.messageReader.observe(THREAD_X).first().messages.single().sendState)
        assertFalse(kid.messageWrites.discard(id), "only the ledger lane is blocked")
        kid.outbox.fail(row.seq, SyncError.Permanent("permission-denied"), Long.MAX_VALUE)
        kid.outbox.auditRejections(
            listOf(PushRejection(row.seq, PushItemId("message/${id.value}"), "permission-denied", blocking = true)),
        )

        assertEquals(MessageSendState.BLOCKED, kid.messageReader.observe(THREAD_X).first().messages.single().sendState)
        assertTrue(kid.messageWrites.discard(id))
        assertEquals(listOf(eventSeq), kid.database.outboxDao().all().map { it.seq }, "the event stays queued")
    }

    @Test
    fun onlyABlockedMessageCanBeDiscardedAndItsSeqStaysUsed() = runTest {
        linked()
        val id = kid.messageWrites.send(THREAD_X, text("refused"))
        assertFalse(kid.messageWrites.discard(id), "not blocked")
        val row = kid.database.outboxDao().all().single { it.kind == OutboxKind.MESSAGE }
        kid.outbox.fail(row.seq, SyncError.Permanent("permission-denied"), Long.MAX_VALUE)
        kid.outbox.auditRejections(
            listOf(PushRejection(row.seq, PushItemId("message/${id.value}"), "permission-denied", blocking = true)),
        )
        assertEquals(MessageSendState.BLOCKED, kid.messageReader.observe(THREAD_X).first().messages.single().sendState)
        assertEquals(OutboxKind.MESSAGE, kid.outbox.observeBlocked().first()?.kind)

        assertTrue(kid.messageWrites.discard(id))
        assertNull(kid.row(id.value))
        assertEquals(0, kid.database.outboxDao().count())
        assertNull(kid.outbox.observeBlocked().first())
        assertEquals(1, kid.database.auditLogDao().all().count { it.action == AuditAction.MESSAGE_DISCARDED })
        val next = kid.messageWrites.send(THREAD_X, text("next"))
        assertEquals(2, kid.queuedMessages().single().envelope().seq, "receivers see seq 1 as a gap")
        assertEquals(next, kid.queuedMessages().single().envelope().messageId)
    }

    @Test
    fun theSenderNeverUsesItsOwnUnconfirmedKey() = runTest {
        registerChatDevices(parent, kid)
        parent.addKid(KID_X.value)
        parent.threadKeyMaintenance()
        assertFailsWith<ThreadKeyUnavailableException> { parent.messageWrites.send(THREAD_X, text("too soon")) }
        assertFalse(parent.threadKeyStatus.observeSendable(THREAD_X).first())
        // The push was accepted (the outbox row is gone, no refusal): settling confirms it.
        parent.database.outboxDao().all().forEach { parent.outbox.ack(it.seq) }
        parent.threadKeyWrites.settle()
        assertTrue(parent.threadKeyStatus.observeSendable(THREAD_X).first())
        parent.messageWrites.send(THREAD_X, text("now"))
    }

    @Test
    fun theSendableStatusFollowsTheKidsArchiving() = runTest {
        registerChatDevices(parent, kid)
        val (kidX, _) = parent.addKid(KID_X.value)
        parent.threadKeyMaintenance()
        parent.confirmThreadKeys()
        val values = Channel<Boolean>(Channel.UNLIMITED)
        val collector =
            launch(Dispatchers.IO) { parent.threadKeyStatus.observeSendable(THREAD_X).collect(values::send) }
        suspend fun next() = withContext(Dispatchers.IO) { withTimeout(5_000) { values.receive() } }
        assertTrue(next())

        // Archiving the kid alone (no key or registry change) makes its devices excluded: not sendable until rotated.
        parent.kids.archive(kidX.id)

        assertFalse(next())
        collector.cancel()
    }

    @Test
    fun aRefusedNewKeyIsWithdrawn() = runTest {
        registerChatDevices(parent, kid)
        parent.addKid(KID_X.value)
        parent.threadKeyMaintenance()
        val document = parent.database.outboxDao().all().single {
            it.kind == OutboxKind.DOCUMENT &&
                it.docType == "thread_key"
        }
        parent.outbox.ack(document.seq)
        parent.outbox.auditRejections(
            listOf(
                PushRejection(document.seq, PushItemId("document/thread_key/${document.docId}"), "permission-denied"),
            ),
        )
        assertEquals(1, parent.threadKeyWrites.settle())
        assertTrue(parent.database.threadKeyDao().all().isEmpty())
        assertEquals(1, parent.database.auditLogDao().all().count { it.action == AuditAction.THREAD_KEY_WITHDRAWN })
    }

    @Test
    fun aCrashBetweenTheAckAndTheRefusalAuditNeverConfirmsTheKey() = runTest {
        registerChatDevices(parent, kid)
        parent.addKid(KID_X.value)
        parent.threadKeyMaintenance()
        val document = parent.database.outboxDao().all().single {
            it.kind == OutboxKind.DOCUMENT && it.docType == "thread_key"
        }
        val rejection =
            PushRejection(document.seq, PushItemId("document/thread_key/${document.docId}"), "permission-denied")
        // The process dies right after the refused document's ack: the ack and its audit row are one transaction
        // (audit A2-L3), so neither is kept and the document is still queued.
        assertFailsWith<IllegalStateException> {
            parent.database.withTransaction {
                parent.outbox.ackRejected(document.seq, rejection)
                error("process died")
            }
        }
        assertEquals(0, parent.threadKeyWrites.settle())
        assertFalse(parent.threadKeyWrites.confirmed(requireNotNull(document.docId)), "still queued: not confirmed")
        assertEquals(1, parent.database.threadKeyDao().all().size, "nor withdrawn")
        assertFalse(parent.threadKeyStatus.observeSendable(THREAD_X).first())
        // The refusal's ack and audit row land together: settling withdraws the key instead of confirming it.
        parent.outbox.ackRejected(document.seq, rejection)
        assertEquals(1, parent.threadKeyWrites.settle())
        assertTrue(parent.database.threadKeyDao().all().isEmpty())
    }

    @Test
    fun aMediaMessageQueuesItsBlobBeforeItselfInOneGo() = runTest {
        linked()
        val clip = Random(3).nextBytes(64)
        val (messageId, blobId) = kid.messageWrites.sendWithBlob(THREAD_X, MessagePlaintext.Voice(1_500), clip)

        val due = kid.outbox.due(Long.MAX_VALUE, limit = 10)
        assertEquals(listOf(OutboxItem.Kind.BLOB_CHUNK, OutboxItem.Kind.MESSAGE), due.map { it.kind })
        val message = requireNotNull(due.last().messageJson).envelope()
        assertEquals(messageId, message.messageId)
        assertEquals(blobId, message.blobId)
        assertContentEquals(clip, kid.chatBlobs.open(blobId, threadBlob(kid, clip)))
        parent.applier.applyMessages(kid.queuedMessages())
        assertContentEquals(clip, parent.chatBlobs.ingest(blobId, kid.queuedBlobChunks(), threadBlob(kid, clip)))
    }

    @Test
    fun aMediaMessageThatFailsAfterItsBlobWasEncryptedLeavesNoChunksAndNoCachedCopy() = runTest {
        linked()
        // The message itself cannot be written (here its encryption fails; in the app, a rotation in between or a
        // crash): the blob's chunks were queued in the same transaction, so they roll back with it (audit A2-L4).
        val failingMessages = MessageCipher(
            object : Aead {
                override fun encrypt(key: SymmetricKey, plaintext: ByteArray, associatedData: ByteArray): ByteArray =
                    throw GeneralSecurityException("message encryption failed")

                override fun decrypt(key: SymmetricKey, ciphertext: ByteArray, associatedData: ByteArray): ByteArray =
                    throw GeneralSecurityException("unused")
            },
        )
        val crypto = ChatCrypto(kid.chatCrypto.keyring, failingMessages, kid.chatCrypto.blobs, kid.encryptionKeys)
        val blobs = with(kid) { BlobWrites(database, clock, signer, authors, crypto, signatureCheck, blobCache) }
        val writes = with(kid) { MessageWrites(database, clock, signer, authors, crypto, signatureCheck, blobs) }

        assertFailsWith<GeneralSecurityException> {
            writes.sendWithBlob(THREAD_X, MessagePlaintext.Voice(1_000), byteArrayOf(1, 2, 3))
        }

        val queued = kid.database.outboxDao().all().map { it.kind }
        assertTrue(OutboxKind.BLOB_CHUNK !in queued && OutboxKind.MESSAGE !in queued)
        assertTrue(kid.database.blobDao().all().isEmpty(), "the cached copy is dropped")
        assertTrue(kid.database.auditLogDao().all().none { it.action == AuditAction.BLOB_QUEUED })
        assertFailsWith<IllegalArgumentException> {
            kid.messageWrites.sendWithBlob(THREAD_X, text("no blob"), byteArrayOf(1))
        }
    }

    @Test
    fun aRegistryKeyThatIsNotThisDevicesShowsAKeyConflict() = runTest {
        linked()
        assertEquals(ThreadKeyAvailability.SENDABLE, kid.threadKeyStatus.observe(THREAD_X).first())
        // No key for a new epoch yet: waiting.
        val own = assertNotNull(kid.database.deviceDao().get(kid.deviceId))
        kid.database.threadKeyDao().deleteAll()
        assertEquals(ThreadKeyAvailability.WAITING, kid.threadKeyStatus.observe(THREAD_X).first())
        // The registry later listed another key for this device (D-M4-14).
        kid.database.deviceDao().upsert(own.copy(encryptionKeyConflict = true))
        assertEquals(ThreadKeyAvailability.KEY_CONFLICT, kid.threadKeyStatus.observe(THREAD_X).first())
        // Or the listed key is not the one this device holds.
        val other = Random(9).nextBytes(32)
        kid.database.deviceDao().upsert(own.copy(encryptionKey = other))
        assertEquals(ThreadKeyAvailability.KEY_CONFLICT, kid.threadKeyStatus.observe(THREAD_X).first())
    }

    @Test
    fun tamperedOrForeignBlobChunksAreRefused() = runTest {
        linked()
        val blobId = kid.blobWrites.store(BlobTarget.Thread(THREAD_X), byteArrayOf(1, 2, 3, 4))
        val chunk = kid.queuedBlobChunks().single()
        val flipped = chunk.data.also { it[0] = (it[0].toInt() xor 1).toByte() }

        val invalid = assertFailsWith<BlobUnavailableException> {
            parent.chatBlobs.ingest(blobId, listOf(BlobChunkJson(chunk.header, flipped)), threadBlob(kid))
        }
        assertEquals(BlobUnavailableException.Reason.INVALID, invalid.reason)
        assertEquals(
            BlobUnavailableException.Reason.INVALID,
            assertFailsWith<BlobUnavailableException> {
                parent.chatBlobs.ingest(BlobId("another"), listOf(chunk), threadBlob(kid))
            }.reason,
        )
        assertEquals(
            BlobUnavailableException.Reason.NOT_FOUND,
            assertFailsWith<BlobUnavailableException> {
                parent.chatBlobs.ingest(blobId, emptyList(), threadBlob(kid))
            }.reason,
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
