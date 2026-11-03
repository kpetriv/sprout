package dev.kpetriv.sprout.core.database

import androidx.room.withTransaction
import dev.kpetriv.sprout.core.common.Dispatcher
import dev.kpetriv.sprout.core.common.SproutDispatchers
import dev.kpetriv.sprout.core.database.dao.OutboxHead
import dev.kpetriv.sprout.core.database.entity.DeviceEntity
import dev.kpetriv.sprout.core.database.entity.MessageEntity
import dev.kpetriv.sprout.core.database.entity.MessageStatus
import dev.kpetriv.sprout.core.database.entity.OutboxKind
import dev.kpetriv.sprout.core.database.entity.ReadMarkEntity
import dev.kpetriv.sprout.core.domainledger.SignatureCheck
import dev.kpetriv.sprout.core.domainledger.chat.MessageKind
import dev.kpetriv.sprout.core.domainledger.chat.MessagePlaintext
import dev.kpetriv.sprout.core.domainledger.chat.MessagePlaintextCodec
import dev.kpetriv.sprout.core.domainledger.chat.ThreadMembers
import dev.kpetriv.sprout.core.model.BlobId
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.MessageId
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.model.ThreadId
import dev.kpetriv.sprout.core.model.UserId
import dev.kpetriv.sprout.core.sync.OutboxItem
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import java.security.MessageDigest
import javax.inject.Inject
import kotlin.time.Instant

/** Where one of this device's own messages stands. Messages from others are always [SENT]. */
enum class MessageSendState {
    /** Queued in the outbox, not yet accepted by the server. */
    SENDING,
    SENT,

    /** Still queued after [MessageReader.FAILED_AFTER_ATTEMPTS] failed attempts; [MessageWrites.retry] retries. */
    FAILED,

    /**
     * The server refused it (or its blob) for good and it holds back the chat lane ([RoomOutboxSource.observeBlocked]):
     * "sending is blocked"; [MessageWrites.discard] removes it (D-M4-19).
     */
    BLOCKED,
}

/**
 * One visible chat message, decrypted when this device can (M4 §4).
 *
 * @property content the decrypted content; null while [locked].
 * @property locked no key of its epoch on this device yet ("waiting for a parent device").
 * @property fromThisDevice written on this device ([MessageStatus.SENT_LOCAL]).
 * @property createdAt the time to show: the signed `createdAt`, never earlier than the author's previous message
 *   (audit M4).
 * @property authorDeviceRole the role the registry gives the author device (a KID-role message from a PARENT device was
 *   written in kid mode on a parent's phone, D-M3-12); null when the device is not in the registry mirror.
 * @property late accepted after its `seq` gap had been skipped (audit L3).
 */
@Suppress("LongParameterList") // One field per envelope fact the screens show.
class StoredMessage(
    val messageId: MessageId,
    val threadId: ThreadId,
    val authorUid: UserId,
    val authorDeviceId: DeviceId,
    val authorRole: Role,
    val kind: MessageKind,
    val createdAt: Instant,
    val lamport: Long,
    val blobId: BlobId?,
    val content: MessagePlaintext?,
    val locked: Boolean,
    val fromThisDevice: Boolean,
    val sendState: MessageSendState,
    val authorDeviceRole: Role? = null,
    val late: Boolean = false,
) {
    override fun toString(): String =
        "StoredMessage(messageId=$messageId, threadId=$threadId, kind=$kind, locked=$locked, sendState=$sendState)"
}

/** The newest messages of a thread, oldest first; [hasMore] when older ones exist beyond the limit. */
class MessagePage(val messages: List<StoredMessage>, val hasMore: Boolean) {
    override fun toString(): String = "MessagePage(messages=${messages.size}, hasMore=$hasMore)"
}

/**
 * One kid's thread for the parent's thread list (M4 §5.1).
 *
 * @property kidName the kid's first name (personal data: not in `toString`).
 * @property unreadCount visible messages from other devices received after the thread's read mark.
 */
class ThreadSummary(
    val threadId: ThreadId,
    val kidId: KidId,
    val kidName: String,
    val avatarId: String,
    val unreadCount: Int,
    val lastMessage: StoredMessage?,
) {
    override fun toString(): String = "ThreadSummary(threadId=$threadId, unreadCount=$unreadCount)"
}

/** Reads chat messages for the screens (M4 §4). Plaintext is decoded here and never logged. */
class MessageReader @Inject constructor(private val database: SproutDatabase) {
    /**
     * The newest [limit] visible messages of [threadId] (RECEIVED, LOCKED and this device's own), oldest first,
     * ordered by lamport (then author device and seq); re-emits on every change, including send states.
     */
    fun observe(threadId: ThreadId, limit: Int = DEFAULT_LIMIT): Flow<MessagePage> {
        require(limit > 0) { "limit must be positive" }
        return combine(
            database.messageDao().observeNewest(threadId.value, limit + 1),
            database.outboxDao().observeKind(OutboxKind.MESSAGE),
            observeBlockedPush(database, OutboxItem.Lane.CHAT),
            database.deviceDao().observeAll(),
        ) { rows, queued, blocked, devices ->
            val pending = queued.associateBy { it.refId }
            val roles = devices.associate { it.deviceId to it.role }
            val page = rows.take(limit).reversed().map { it.toStored(pending, blocked, roles) }
            MessagePage(page, rows.size > limit)
        }.distinctUntilChanged { old, new -> old.sameAs(new) }
    }

    companion object {
        const val DEFAULT_LIMIT: Int = 50

        /** A queued message counts as failed after this many failed push attempts. */
        const val FAILED_AFTER_ATTEMPTS: Int = 3

        private fun MessagePage.sameAs(other: MessagePage): Boolean = hasMore == other.hasMore &&
            messages.size == other.messages.size &&
            messages.zip(other.messages).all { (a, b) ->
                a.messageId == b.messageId && a.locked == b.locked && a.sendState == b.sendState &&
                    a.authorDeviceRole == b.authorDeviceRole && a.createdAt == b.createdAt
            }
    }
}

/** The parent's thread list and read marks (M4 §5.1): one thread per active kid. */
class ChatThreads @Inject constructor(private val database: SproutDatabase) {
    /** Every active kid's thread, in the household's kid order, with unread counts and the newest message. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeThreads(): Flow<List<ThreadSummary>> = database.sessionDao().observe().flatMapLatest { session ->
        val self = session?.deviceId ?: return@flatMapLatest flowOf(emptyList())
        combine(
            database.chatHouseholdDao().observeActiveKids(),
            database.messageDao().observeLatestPerThread(),
            database.messageDao().observeUnread(self),
            database.outboxDao().observeKind(OutboxKind.MESSAGE),
        ) { kids, latest, unread, queued ->
            val latestByThread = latest.associateBy { it.threadId }
            val unreadByThread = unread.associate { it.threadId to it.unread }
            val pending = queued.associateBy { it.refId }
            kids.map { kid ->
                ThreadSummary(
                    threadId = ThreadId(kid.kidId.value),
                    kidId = kid.kidId,
                    kidName = kid.firstName,
                    avatarId = kid.avatarId,
                    unreadCount = unreadByThread[kid.kidId.value] ?: 0,
                    lastMessage = latestByThread[kid.kidId.value]?.toStored(
                        pending,
                        blocked = null,
                        roles = emptyMap(),
                    ),
                )
            }
        }
    }

    /** Everything in [threadId] received so far counts as read on this device. */
    suspend fun markRead(threadId: ThreadId) {
        database.withTransaction {
            val latest = database.messageDao().latestReceivedAt(threadId.value) ?: return@withTransaction
            database.messageDao().upsertReadMark(ReadMarkEntity(threadId.value, latest))
        }
    }
}

/** Whether this device can send in a thread, and why not ([ThreadKeyStatusReader.observe]). */
enum class ThreadKeyAvailability {
    /** A key this device may send with is here. */
    SENDABLE,

    /** No usable key yet: "waiting for a parent device". */
    WAITING,

    /**
     * The registry lists another encryption key for this device than the one it holds, or this device lost its key
     * (D-M4-14, audit I2): no thread key will ever be wrapped for it, so only removing and re-linking the phone helps.
     */
    KEY_CONFLICT,
}

/**
 * Whether this device can send in a thread (M4 §1.2, review H2): the latest epoch's key this device would send with
 * is here (its own only once the server confirmed it) and no latest wrap set holds a device that must not have it,
 * the kid's archiving included. False means "waiting for a parent device". The same rule as `MessageWrites`.
 * [observe] also tells a key conflict of this device apart (audit I2).
 */
class ThreadKeyStatusReader @Inject constructor(
    private val database: SproutDatabase,
    private val check: SignatureCheck,
    private val crypto: ChatCrypto,
    @param:Dispatcher(SproutDispatchers.IO) private val dispatcher: CoroutineDispatcher,
) {
    fun observeSendable(threadId: ThreadId): Flow<Boolean> = combine(
        database.threadKeyDao().observeThread(threadId.value),
        database.deviceDao().observeAll(),
        database.sessionDao().observe(),
        database.chatHouseholdDao().observeArchived(threadId.value),
    ) { rows, devices, session, archived ->
        if (session == null) {
            false
        } else {
            val membership = ThreadMembers.resolve(threadId, devices.map { it.toRegistered() }, check, archived)
            ThreadKeyStore.sendable(rows, session.deviceId, membership.excluded) != null
        }
    }.distinctUntilChanged()

    /**
     * [observeSendable] with the reason it is not: [ThreadKeyAvailability.KEY_CONFLICT] when this device's registry row
     * was flagged with a different key ([DeviceEntity.encryptionKeyConflict]) or lists a key this device does not
     * hold; otherwise [ThreadKeyAvailability.WAITING]. The local key is read off the main thread.
     */
    fun observe(threadId: ThreadId): Flow<ThreadKeyAvailability> = combine(
        observeSendable(threadId),
        database.deviceDao().observeAll(),
        database.sessionDao().observe(),
    ) { sendable, devices, session ->
        val own = session?.let { current -> devices.firstOrNull { it.deviceId == current.deviceId } }
        when {
            sendable -> ThreadKeyAvailability.SENDABLE
            own != null && keyConflict(own) -> ThreadKeyAvailability.KEY_CONFLICT
            else -> ThreadKeyAvailability.WAITING
        }
    }.distinctUntilChanged().flowOn(dispatcher)

    private suspend fun keyConflict(own: DeviceEntity): Boolean {
        val listed = own.encryptionKey
        return when {
            own.encryptionKeyConflict -> true
            listed == null -> false
            else -> crypto.keyPairOrNull()?.use { MessageDigest.isEqual(it.publicKey.bytes, listed) } != true
        }
    }
}

private fun MessageEntity.toStored(
    pending: Map<String, OutboxHead>,
    blocked: BlockedPush?,
    roles: Map<DeviceId, Role>,
): StoredMessage {
    val queued = pending[messageId]
    val isBlocked = blocked != null && status == MessageStatus.SENT_LOCAL && (
        (blocked.kind == OutboxKind.MESSAGE && blocked.refId == messageId) ||
            (blocked.kind == OutboxKind.BLOB_CHUNK && blobId != null && blocked.refId.startsWith("$blobId."))
        )
    val sendState = when {
        isBlocked -> MessageSendState.BLOCKED
        status != MessageStatus.SENT_LOCAL || queued == null -> MessageSendState.SENT
        queued.attempts >= MessageReader.FAILED_AFTER_ATTEMPTS -> MessageSendState.FAILED
        else -> MessageSendState.SENDING
    }
    val content = plaintext?.let { MessagePlaintextCodec.decode(kind, it) }
    return StoredMessage(
        messageId = MessageId(messageId),
        threadId = ThreadId(threadId),
        authorUid = authorUid,
        authorDeviceId = authorDeviceId,
        authorRole = authorRole,
        kind = kind,
        createdAt = Instant.fromEpochMilliseconds(displayedAtEpochMs),
        lamport = lamport,
        blobId = blobId?.let(::BlobId),
        content = content,
        locked = content == null,
        fromThisDevice = status == MessageStatus.SENT_LOCAL,
        sendState = sendState,
        authorDeviceRole = roles[authorDeviceId],
        late = late,
    )
}
