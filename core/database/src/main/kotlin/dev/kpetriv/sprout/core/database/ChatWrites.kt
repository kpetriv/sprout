package dev.kpetriv.sprout.core.database

import androidx.room.withTransaction
import dev.kpetriv.sprout.core.crypto.DeviceSigner
import dev.kpetriv.sprout.core.crypto.chat.MessageSigner
import dev.kpetriv.sprout.core.database.entity.AuditAction
import dev.kpetriv.sprout.core.database.entity.MessageEntity
import dev.kpetriv.sprout.core.database.entity.MessageSeqEntity
import dev.kpetriv.sprout.core.database.entity.MessageStatus
import dev.kpetriv.sprout.core.database.entity.OutboxEntity
import dev.kpetriv.sprout.core.database.entity.OutboxKind
import dev.kpetriv.sprout.core.database.entity.ThreadKeyEntity
import dev.kpetriv.sprout.core.domainledger.EventAuthor
import dev.kpetriv.sprout.core.domainledger.SignatureCheck
import dev.kpetriv.sprout.core.domainledger.chat.MessageEnvelope
import dev.kpetriv.sprout.core.domainledger.chat.MessageHeader
import dev.kpetriv.sprout.core.domainledger.chat.MessageJson
import dev.kpetriv.sprout.core.domainledger.chat.MessagePlaintext
import dev.kpetriv.sprout.core.domainledger.chat.MessagePlaintextCodec
import dev.kpetriv.sprout.core.domainledger.chat.ThreadKeyDocument
import dev.kpetriv.sprout.core.model.BlobId
import dev.kpetriv.sprout.core.model.MessageId
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.model.ThreadId
import dev.kpetriv.sprout.core.sync.DocTypes
import dev.kpetriv.sprout.core.time.Clock
import kotlinx.serialization.json.put
import java.util.UUID
import javax.inject.Inject
import kotlin.time.Instant

/**
 * Sends chat messages (M4 §1.3). In one transaction under the device clock lock: picks the key this device may send
 * with ([ThreadKeyStore.sendingKey], M4 §1.2, checked there so a rotation or a withdrawn key between the screen's
 * check and the write cannot slip through), encrypts, allocates the next `seq` of this device in the thread and the
 * next device lamport, signs the envelope with the device key ([MessageSigner]), stores it as SENT_LOCAL (with its
 * plaintext, for this device's own view) and queues it in the outbox (`MESSAGE`), with a `MESSAGE_SENT` audit row (ids
 * only). A VOICE or PHOTO message names a blob that [BlobWrites.store] already queued, so the outbox sends the blob
 * first.
 */
class MessageWrites @Inject constructor(
    private val database: SproutDatabase,
    private val clock: Clock,
    signer: DeviceSigner,
    private val authors: SessionAuthorProvider,
    private val crypto: ChatCrypto,
    private val check: SignatureCheck,
) {
    private val messageSigner = MessageSigner(signer)
    private val deviceClock = DeviceClock.of(database)
    private val messages = database.messageDao()

    /**
     * Encrypts, signs, stores and queues [plaintext] in [threadId]; returns the new message's id.
     *
     * The author is this session's: a KID session writes as its kid (only into that kid's thread); a PARENT session
     * writes as the parent, or as the thread's kid in kid mode when [asKid] (D-M3-12).
     *
     * @param blobId the blob of a VOICE or PHOTO message ([BlobWrites.store] first); null for TEXT and PRESET.
     * @throws ThreadKeyUnavailableException when this device holds no key it may send with ("waiting for a parent
     *   device").
     * @throws IllegalArgumentException for a [blobId] that does not match the kind, an unknown blob, or a KID session
     *   writing into another kid's thread.
     * @throws NoSessionException without a session.
     */
    suspend fun send(
        threadId: ThreadId,
        plaintext: MessagePlaintext,
        blobId: BlobId? = null,
        asKid: Boolean = false,
    ): MessageId {
        require((blobId != null) == plaintext.kind.hasBlob) { "a blob is named exactly by VOICE and PHOTO messages" }
        if (blobId != null) require(database.blobDao().get(blobId.value) != null) { "unknown blob" }
        val session = database.sessionDao().get()?.toDomain() ?: throw NoSessionException()
        val author = if (session.role == Role.KID || asKid) {
            authors.kidAuthor(threadId.kidId)
        } else {
            authors.currentAuthor()
        }
        return store(author, session, threadId, plaintext, blobId)
    }

    /** Makes a queued message due at once (the user's "retry" after a failure). False if it is not queued. */
    suspend fun retry(messageId: MessageId): Boolean =
        database.outboxDao().retryNow(OutboxKind.MESSAGE, messageId.value) > 0

    /**
     * Removes this device's message [messageId] whose push (or whose blob's) the server refused for good, the item
     * that blocks the outbox ([RoomOutboxSource.observeBlocked], D-M4-19): the message row, its outbox row and its
     * blob's chunks, audited `MESSAGE_DISCARDED`. Its `seq` stays used: receivers see a gap that they skip after the
     * gap timeout (`ChatApplier.HELD_GAP_TIMEOUT_MS`). False when the message is not this device's or not blocked.
     */
    suspend fun discard(messageId: MessageId): Boolean = deviceClock.withLock {
        database.withTransaction {
            val row = messages.get(messageId.value)?.takeIf { it.status == MessageStatus.SENT_LOCAL }
            val blocked = currentBlockedPush(database)
            val blocks = row != null && blocked != null && (
                (blocked.kind == OutboxKind.MESSAGE && blocked.refId == row.messageId) ||
                    (
                        blocked.kind == OutboxKind.BLOB_CHUNK && row.blobId != null &&
                            blocked.refId.startsWith(row.blobId + ".")
                        )
                )
            if (row != null && blocks) {
                messages.delete(row.messageId)
                database.outboxDao().deleteRef(OutboxKind.MESSAGE, row.messageId)
                row.blobId?.let { database.outboxDao().deleteBlobChunks(it) }
                database.auditLogDao().insert(
                    auditEntry(
                        authors.currentAuthor(),
                        clock.now().toEpochMilliseconds(),
                        AuditAction.MESSAGE_DISCARDED,
                        row.messageId,
                    ) {
                        put("threadId", row.threadId)
                        put("seq", row.seq)
                    },
                )
            }
            blocks
        }
    }

    @Suppress("LongParameterList") // The message and who writes it.
    private suspend fun store(
        author: EventAuthor,
        session: Session,
        threadId: ThreadId,
        plaintext: MessagePlaintext,
        blobId: BlobId?,
    ): MessageId = deviceClock.withLock {
        database.withTransaction {
            val devices = RoomDeviceRegistry(database).devicesOf(session.householdId)
            val archived = database.isArchivedKid(threadId)
            val key = ThreadKeyStore(database).sendingKey(threadId, session.deviceId, devices, check, archived)
                ?: throw ThreadKeyUnavailableException(threadId)
            val header = MessageHeader(
                householdId = author.householdId,
                threadId = threadId,
                messageId = MessageId(UUID.randomUUID().toString()),
                authorUid = author.uid,
                authorDeviceId = author.deviceId,
                authorRole = author.role,
                epoch = key.epoch,
                kind = plaintext.kind,
                blobId = blobId,
            )
            val ciphertext = key.use { crypto.messages.encrypt(it, header, plaintext) }
            val previous = messages.seq(threadId.value, author.deviceId)
            val seq = previous?.nextSeq ?: 1
            val lamport = deviceClock.takeNext(author.deviceId)
            val now = clock.now().toEpochMilliseconds()
            val displayed = maxOf(now, previous?.lastDisplayedAtEpochMs ?: 0)
            val unsigned = MessageEnvelope.of(header, seq, lamport, Instant.fromEpochMilliseconds(now), ciphertext)
            val envelope = messageSigner.sign(unsigned)
            val bytes = MessagePlaintextCodec.encode(plaintext)
            messages.upsert(MessageEntity.of(envelope, MessageStatus.SENT_LOCAL, bytes, null, now, displayed))
            messages.upsertSeq(
                (previous ?: MessageSeqEntity(threadId.value, author.deviceId, 1)).copy(
                    nextSeq = seq + 1,
                    lastLamport = lamport,
                    lastDisplayedAtEpochMs = displayed,
                ),
            )
            database.outboxDao().insert(
                OutboxEntity(
                    kind = OutboxKind.MESSAGE,
                    refId = envelope.messageId.value,
                    payload = MessageJson.encode(envelope),
                    createdAtEpochMs = now,
                    lamport = lamport,
                ),
            )
            database.auditLogDao().insert(
                auditEntry(author, now, AuditAction.MESSAGE_SENT, envelope.messageId.value) {
                    put("threadId", threadId.value)
                    put("seq", seq)
                    put("lamport", lamport)
                    put("kind", envelope.kind.name)
                    put("epoch", envelope.epoch)
                },
            )
            header.messageId
        }
    }
}

/**
 * Publishes `thread_key` documents (M4 §1.2) through the signed document path ([DocumentWrites]): PARENT sessions only,
 * and only documents this device created. The document must wrap the key for this device too: the wrap is opened
 * with this device's key pair before anything is written (so a document nobody could use is never published), and the
 * key is stored in `thread_keys` with the document in the same transaction.
 *
 * A **new** key stays unconfirmed ([ThreadKeyEntity.confirmed]) until the server has the document (review H2): its own
 * copy came back in a pull (`ChatApplier`), or [settle] finds its outbox row gone without a refusal. Until then this
 * device does not send under it; if the server refused it, [settle] withdraws the key (nothing was encrypted with it).
 * A re-wrap of a confirmed key for new members keeps it confirmed: the server already has that key.
 */
class ThreadKeyWrites @Inject constructor(
    private val database: SproutDatabase,
    private val clock: Clock,
    signer: DeviceSigner,
    private val authors: SessionAuthorProvider,
    private val crypto: ChatCrypto,
) {
    private val writes = DocumentWrites(database, clock, signer)
    private val threadKeys = database.threadKeyDao()

    /**
     * Signs and queues [document] and stores its key.
     *
     * @throws IllegalStateException without a PARENT session, or without this device's key pair.
     * @throws IllegalArgumentException when [document] was not created by this device.
     * @throws dev.kpetriv.sprout.core.crypto.chat.ThreadKeyUnwrapException when its wrap for this device does not
     *   open with this device's key pair.
     */
    suspend fun publish(document: ThreadKeyDocument) {
        val author = authors.currentAuthor()
        check(author.role == Role.PARENT) { "only a parent device publishes thread keys" }
        require(document.createdByDeviceId == author.deviceId) { "the document was created by another device" }
        val key = checkNotNull(crypto.keyPairOrNull()) { "this device has no encryption key" }.use { pair ->
            crypto.keyring.unwrap(author.householdId, document, author.deviceId, pair.privateKey, pair.publicKey)
                .use { it.copyBytes() }
        }
        try {
            writes.transaction {
                val existing = threadKeys.get(document.threadId.value, document.epoch, document.createdByDeviceId)
                val sameKey = existing?.key?.contentEquals(key) == true
                val lamport = writes.nextLamport(author.deviceId)
                val stamp = writes.record(
                    author,
                    DocTypes.THREAD_KEY,
                    document.docId,
                    lamport,
                    document.toPayload(),
                    AuditAction.THREAD_KEY_PUBLISHED,
                ) {
                    put("threadId", document.threadId.value)
                    put("epoch", document.epoch)
                    put("recipients", document.recipients.size)
                }
                threadKeys.upsert(
                    ThreadKeyEntity(
                        threadId = document.threadId.value,
                        epoch = document.epoch,
                        createdByDeviceId = document.createdByDeviceId,
                        key = key,
                        sourceDocId = document.docId,
                        wrapSetJson = ThreadKeyStore.wrapSetJson(document),
                        lamport = lamport,
                        authorUid = author.uid,
                        signature = stamp.signature,
                        receivedAtEpochMs = clock.now().toEpochMilliseconds(),
                        confirmed = sameKey && existing.confirmed,
                        olderKeys = if (sameKey) existing?.olderKeys else olderKeysWith(existing),
                    ),
                )
            }
        } finally {
            key.fill(0)
        }
    }

    /** Whether the server has confirmed this device's document [docId] (see the class documentation). */
    suspend fun confirmed(docId: String): Boolean =
        threadKeys.all().firstOrNull { it.sourceDocId == docId }?.confirmed == true

    /**
     * Settles this device's unconfirmed documents from the outbox state: still queued, they wait; refused for good
     * (a `SYNC_REJECTED` row for the document since it was written), the new key is withdrawn (the previous key of
     * that document, if any, is restored), audited `THREAD_KEY_WITHDRAWN`; otherwise the push was accepted and the
     * row is confirmed. Call it after the sync's refusals were audited (the app runs it in `ThreadKeyMaintenance`,
     * after each sync); a pulled copy of the document confirms it at any time. Returns how many were withdrawn.
     */
    suspend fun settle(): Int {
        val self = database.sessionDao().get()?.deviceId ?: return 0
        return database.withTransaction {
            var withdrawn = 0
            for (row in threadKeys.unconfirmed(self)) {
                val queued = database.outboxDao().countRef(
                    OutboxKind.DOCUMENT,
                    "${DocTypes.THREAD_KEY}/${row.sourceDocId}",
                )
                if (queued > 0) continue
                val target = "document/${DocTypes.THREAD_KEY}/${row.sourceDocId}"
                val refused = database.auditLogDao().countSince(
                    AuditAction.SYNC_REJECTED,
                    target,
                    row.receivedAtEpochMs,
                )
                if (refused == 0) {
                    threadKeys.upsert(row.withConfirmed(true))
                } else {
                    withdraw(row)
                    withdrawn++
                }
            }
            withdrawn
        }
    }

    private suspend fun withdraw(row: ThreadKeyEntity) {
        val older = row.allKeys().drop(1)
        if (older.isEmpty()) {
            threadKeys.delete(row.threadId, row.epoch, row.createdByDeviceId)
        } else {
            val restored = ThreadKeyEntity(
                row.threadId, row.epoch, row.createdByDeviceId, older.last(), row.sourceDocId, row.wrapSetJson,
                row.lamport, row.authorUid, row.signature, row.receivedAtEpochMs, confirmed = true,
                olderKeys = older.dropLast(1).takeIf { it.isNotEmpty() }?.reduce { a, b -> a + b },
            )
            threadKeys.upsert(restored)
        }
        writes.auditOnly(authors.currentAuthor(), AuditAction.THREAD_KEY_WITHDRAWN, row.sourceDocId) {
            put("threadId", row.threadId)
            put("epoch", row.epoch)
        }
    }
}
