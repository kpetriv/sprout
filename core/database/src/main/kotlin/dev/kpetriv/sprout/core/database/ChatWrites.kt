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
 * Sends chat messages (M4 §1.3): encrypts with the key of the epoch [ThreadKeyStore.sendingKey] allows (M4 §1.2),
 * then, in one transaction under the device clock lock, allocates the next `seq` of this device in the thread and the
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
        val devices = RoomDeviceRegistry(database).devicesOf(session.householdId)
        val key = ThreadKeyStore(database).sendingKey(threadId, devices, check, database.isArchivedKid(threadId))
            ?: throw ThreadKeyUnavailableException(threadId)
        val (header, ciphertext) = key.use {
            val header = MessageHeader(
                householdId = author.householdId,
                threadId = threadId,
                messageId = MessageId(UUID.randomUUID().toString()),
                authorUid = author.uid,
                authorDeviceId = author.deviceId,
                authorRole = author.role,
                epoch = it.epoch,
                kind = plaintext.kind,
                blobId = blobId,
            )
            header to crypto.messages.encrypt(it, header, plaintext)
        }
        store(author, header, ciphertext, MessagePlaintextCodec.encode(plaintext))
        return header.messageId
    }

    /** Makes a queued message due at once (the user's "retry" after a failure). False if it is not queued. */
    suspend fun retry(messageId: MessageId): Boolean =
        database.outboxDao().retryNow(OutboxKind.MESSAGE, messageId.value) > 0

    private suspend fun store(author: EventAuthor, header: MessageHeader, ciphertext: ByteArray, plaintext: ByteArray) {
        deviceClock.withLock {
            database.withTransaction {
                val threadId = header.threadId.value
                val seq = messages.seq(threadId, author.deviceId)?.nextSeq ?: 1
                val lamport = deviceClock.takeNext(author.deviceId)
                val now = clock.now().toEpochMilliseconds()
                val unsigned = MessageEnvelope.of(header, seq, lamport, Instant.fromEpochMilliseconds(now), ciphertext)
                val envelope = messageSigner.sign(unsigned)
                messages.upsert(MessageEntity.of(envelope, MessageStatus.SENT_LOCAL, plaintext, null, now))
                messages.upsertSeq(MessageSeqEntity(threadId, author.deviceId, seq + 1))
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
                        put("threadId", threadId)
                        put("seq", seq)
                        put("lamport", lamport)
                        put("kind", envelope.kind.name)
                        put("epoch", envelope.epoch)
                    },
                )
            }
        }
    }
}

/**
 * Publishes `thread_key` documents (M4 §1.2) through the signed document path ([DocumentWrites]): PARENT sessions only,
 * and only documents this device created. The document must wrap the key for this device too: the wrap is opened
 * with this device's key pair before anything is written (so a document nobody could use is never published), and the
 * key is stored in `thread_keys` with the document in the same transaction.
 */
class ThreadKeyWrites @Inject constructor(
    private val database: SproutDatabase,
    private val clock: Clock,
    signer: DeviceSigner,
    private val authors: SessionAuthorProvider,
    private val crypto: ChatCrypto,
) {
    private val writes = DocumentWrites(database, clock, signer)

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
                database.threadKeyDao().upsert(
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
                    ),
                )
            }
        } finally {
            key.fill(0)
        }
    }
}
