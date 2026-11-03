package dev.kpetriv.sprout.core.database

import androidx.room.withTransaction
import dev.kpetriv.sprout.core.database.entity.MessageEntity
import dev.kpetriv.sprout.core.database.entity.MessageStatus
import dev.kpetriv.sprout.core.database.entity.OutboxEntity
import dev.kpetriv.sprout.core.database.entity.OutboxKind
import dev.kpetriv.sprout.core.database.entity.ReadMarkEntity
import dev.kpetriv.sprout.core.domainledger.SignatureCheck
import dev.kpetriv.sprout.core.domainledger.chat.MessageKind
import dev.kpetriv.sprout.core.domainledger.chat.MessagePlaintext
import dev.kpetriv.sprout.core.domainledger.chat.MessagePlaintextCodec
import dev.kpetriv.sprout.core.domainledger.chat.ThreadKeyPolicy
import dev.kpetriv.sprout.core.domainledger.chat.ThreadMembers
import dev.kpetriv.sprout.core.model.BlobId
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.MessageId
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.model.ThreadId
import dev.kpetriv.sprout.core.model.UserId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject
import kotlin.time.Instant

/** Where one of this device's own messages stands. Messages from others are always [SENT]. */
enum class MessageSendState {
    /** Queued in the outbox, not yet accepted by the server. */
    SENDING,
    SENT,

    /** Still queued after [MessageReader.FAILED_AFTER_ATTEMPTS] failed attempts; [MessageWrites.retry] retries. */
    FAILED,
}

/**
 * One visible chat message, decrypted when this device can (M4 §4).
 *
 * @property content the decrypted content; null while [locked].
 * @property locked no key of its epoch on this device yet ("waiting for a parent device").
 * @property fromThisDevice written on this device ([MessageStatus.SENT_LOCAL]).
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
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(threadId: ThreadId, limit: Int = DEFAULT_LIMIT): Flow<MessagePage> {
        require(limit > 0) { "limit must be positive" }
        return combine(
            database.messageDao().observeNewest(threadId.value, limit + 1),
            database.outboxDao().observeKind(OutboxKind.MESSAGE),
        ) { rows, queued ->
            val pending = queued.associateBy { it.refId }
            MessagePage(rows.take(limit).reversed().map { it.toStored(pending) }, rows.size > limit)
        }.distinctUntilChanged { old, new -> old.sameAs(new) }
    }

    companion object {
        const val DEFAULT_LIMIT: Int = 50

        /** A queued message counts as failed after this many failed push attempts. */
        const val FAILED_AFTER_ATTEMPTS: Int = 3

        private fun MessagePage.sameAs(other: MessagePage): Boolean = hasMore == other.hasMore &&
            messages.size == other.messages.size &&
            messages.zip(other.messages).all { (a, b) ->
                a.messageId == b.messageId && a.locked == b.locked && a.sendState == b.sendState
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
                    lastMessage = latestByThread[kid.kidId.value]?.toStored(pending),
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

/**
 * Whether this device can send in a thread (M4 §1.2): the latest epoch's key is here and its wrap set holds no
 * device that must not have it. False means "waiting for a parent device".
 */
class ThreadKeyStatusReader @Inject constructor(
    private val database: SproutDatabase,
    private val check: SignatureCheck,
) {
    fun observeSendable(threadId: ThreadId): Flow<Boolean> = combine(
        database.threadKeyDao().observeThread(threadId.value),
        database.deviceDao().observeAll(),
        database.sessionDao().observe(),
    ) { rows, devices, session ->
        val latest = ThreadKeyStore.latestOf(rows)?.let(ThreadKeyStore::documentOf)
        if (session == null || latest == null) {
            false
        } else {
            val archived = database.isArchivedKid(threadId)
            val membership = ThreadMembers.resolve(threadId, devices.map { it.toRegistered() }, check, archived)
            val held = rows.filter { it.key != null }.mapTo(HashSet()) { it.epoch }
            ThreadKeyPolicy.sendingEpoch(latest, held, membership.excluded) != null
        }
    }.distinctUntilChanged()
}

private fun MessageEntity.toStored(pending: Map<String, OutboxEntity>): StoredMessage {
    val queued = pending[messageId]
    val sendState = when {
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
        createdAt = Instant.fromEpochMilliseconds(createdAtEpochMs),
        lamport = lamport,
        blobId = blobId?.let(::BlobId),
        content = content,
        locked = content == null,
        fromThisDevice = status == MessageStatus.SENT_LOCAL,
        sendState = sendState,
    )
}
