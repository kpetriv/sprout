package dev.kpetriv.sprout.chat

import androidx.lifecycle.SavedStateHandle
import dev.kpetriv.sprout.core.database.MessageSendState
import dev.kpetriv.sprout.core.database.MessageWrites
import dev.kpetriv.sprout.core.database.Session
import dev.kpetriv.sprout.core.database.SessionStore
import dev.kpetriv.sprout.core.database.StoredMessage
import dev.kpetriv.sprout.core.database.ThreadKeyAvailability
import dev.kpetriv.sprout.core.database.ThreadKeyStatusReader
import dev.kpetriv.sprout.core.database.ThreadSummary
import dev.kpetriv.sprout.core.domainledger.JvmSha256Hasher
import dev.kpetriv.sprout.core.domainledger.chat.ChatPhotoRef
import dev.kpetriv.sprout.core.domainledger.chat.MessagePlaintext
import dev.kpetriv.sprout.core.domainledger.toHex
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.MessageId
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.model.ThreadId
import dev.kpetriv.sprout.di.ApplicationScope
import dev.kpetriv.sprout.feature.chat.ChatMessage
import dev.kpetriv.sprout.feature.chat.ChatMode
import dev.kpetriv.sprout.feature.chat.ChatThreadSummary
import dev.kpetriv.sprout.feature.chat.ChatThreads
import dev.kpetriv.sprout.feature.chat.ChatViewModel
import dev.kpetriv.sprout.feature.chat.MessageDirection
import dev.kpetriv.sprout.feature.chat.MessageKind
import dev.kpetriv.sprout.feature.chat.MessagePage
import dev.kpetriv.sprout.feature.chat.MessageReader
import dev.kpetriv.sprout.feature.chat.MessageSender
import dev.kpetriv.sprout.feature.chat.OutgoingMessage
import dev.kpetriv.sprout.feature.chat.SendState
import dev.kpetriv.sprout.feature.chat.ThreadKeyState
import dev.kpetriv.sprout.feature.chat.ThreadKeyStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import dev.kpetriv.sprout.core.database.ChatThreads as StoredChatThreads
import dev.kpetriv.sprout.core.database.MessageReader as StoredMessageReader

/**
 * Whose point of view a chat screen shows (from the screen's [SavedStateHandle] arguments): the kid's own thread in
 * kid mode ([ChatMode.KID], on the kid's phone or in kid mode on a parent's phone, D-M3-12) or a parent's
 * ([ChatMode.PARENT], also the thread list, which has no mode argument).
 */
class ChatPerspective @Inject constructor(savedStateHandle: SavedStateHandle) {
    val kidMode: Boolean = savedStateHandle.get<String>(ChatViewModel.MODE_KEY) == ChatMode.KID.name
}

/** [ChatThreads] over core/database's thread list and read marks. */
class RoomChatThreads @Inject constructor(private val threads: StoredChatThreads) : ChatThreads {
    override fun observeThreads(): Flow<List<ChatThreadSummary>> =
        threads.observeThreads().map { list -> list.map { it.toSummary() } }

    override fun observeThread(threadId: KidId): Flow<ChatThreadSummary?> = threads.observeThreads()
        .map { list -> list.firstOrNull { it.kidId == threadId }?.toSummary() }
        .distinctUntilChanged()

    override suspend fun markRead(threadId: KidId) = threads.markRead(ThreadId.of(threadId))

    private fun ThreadSummary.toSummary() = ChatThreadSummary(
        threadId = kidId,
        kidName = kidName,
        avatarId = avatarId,
        unreadCount = unreadCount,
        lastMessageAt = lastMessage?.createdAt,
    )
}

/**
 * [MessageReader] over core/database's decrypted messages. Direction: in kid mode every KID-role message is the
 * kid's own (its phone or kid mode on a parent's phone) and parents' messages are theirs; for a parent, a message is
 * theirs unless this account wrote it as a parent. A kid's message shown to a parent carries the kid's first name.
 */
class RoomMessageReader @Inject constructor(
    private val messages: StoredMessageReader,
    private val threads: StoredChatThreads,
    private val sessions: SessionStore,
    private val perspective: ChatPerspective,
) : MessageReader {
    override fun observe(threadId: KidId, limit: Int): Flow<MessagePage> = combine(
        messages.observe(ThreadId.of(threadId), limit),
        sessions.observe(),
        threads.observeThreads().map { list -> list.firstOrNull { it.kidId == threadId }?.kidName }
            .distinctUntilChanged(),
    ) { page, session, kidName ->
        MessagePage(page.messages.map { it.toChatMessage(session, kidName) }, page.hasMore)
    }

    private fun StoredMessage.toChatMessage(session: Session?, kidName: String?): ChatMessage {
        val kidView = perspective.kidMode || session?.role == Role.KID
        val mine = if (kidView) {
            authorRole == Role.KID
        } else {
            authorRole == Role.PARENT && authorUid == session?.uid
        }
        val base = ChatMessage(
            id = messageId.value,
            direction = if (mine) MessageDirection.MINE else MessageDirection.THEIRS,
            kind = MessageKind.valueOf(kind.name),
            createdAt = createdAt,
            sendState = sendState.toPort(),
            locked = locked,
            blobId = blobId?.value,
            authorName = kidName.takeIf { !mine && authorRole == Role.KID },
            authorDeviceId = authorDeviceId.value,
            fromParentPhone = authorRole == Role.KID && authorDeviceRole == Role.PARENT,
            late = late,
        )
        return when (val content = content) {
            is MessagePlaintext.Text -> base.copy(text = content.text)
            is MessagePlaintext.Preset -> base.copy(presetId = content.presetId, emoji = content.emoji)
            is MessagePlaintext.Voice -> base.copy(durationMs = content.durationMs)
            is MessagePlaintext.Photo -> base.copy(text = content.caption, photoSha256 = content.photoRef.sha256)
            null -> base
        }
    }

    private fun MessageSendState.toPort(): SendState = when (this) {
        MessageSendState.SENDING -> SendState.SENDING
        MessageSendState.SENT -> SendState.SENT
        MessageSendState.FAILED -> SendState.FAILED
        MessageSendState.BLOCKED -> SendState.BLOCKED
    }
}

/**
 * [MessageSender] over [MessageWrites]: media goes through [MessageWrites.sendWithBlob], which encrypts it with the
 * thread key and queues its chunks and the message that names it in one transaction (audit A2-L4: a failed send
 * leaves no chunks queued). In kid mode on a parent's phone the message is written as the kid
 * (`asKid`, D-M3-12); a KID session always writes as its kid.
 *
 * Every write runs on the application scope, not cancellable (review M6): the screen waits for the result, but a
 * screen that goes away mid-send never cuts a blob off from the message that names it.
 */
class RoomMessageSender @Inject constructor(
    private val writes: MessageWrites,
    private val perspective: ChatPerspective,
    @param:ApplicationScope private val scope: CoroutineScope,
) : MessageSender {
    override suspend fun send(threadId: KidId, message: OutgoingMessage): Result<Unit> {
        val asKid = asKid()
        return onAppScope { write(ThreadId.of(threadId), message, asKid) }
    }

    override suspend fun retry(threadId: KidId, messageId: String): Result<Unit> = onAppScope {
        check(writes.retry(MessageId(messageId))) { "not queued" }
    }

    override suspend fun discard(threadId: KidId, messageId: String): Result<Unit> = onAppScope {
        check(writes.discard(MessageId(messageId))) { "not blocked" }
    }

    /** Runs [block] to the end on the application scope; its failure (not a cancellation) is the result. */
    @Suppress("TooGenericExceptionCaught") // Any storage or key failure is a failed send the screen can retry.
    private suspend fun onAppScope(block: suspend () -> Unit): Result<Unit> = scope.async(NonCancellable) {
        try {
            block()
            Result.success(Unit)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            Result.failure(failure)
        }
    }.await()

    private suspend fun write(thread: ThreadId, message: OutgoingMessage, asKid: Boolean) {
        when (message) {
            is OutgoingMessage.Text -> writes.send(thread, MessagePlaintext.Text(message.text), asKid = asKid)

            is OutgoingMessage.Preset ->
                writes.send(thread, MessagePlaintext.Preset(message.presetId, message.emoji), asKid = asKid)

            is OutgoingMessage.Voice ->
                writes.sendWithBlob(thread, MessagePlaintext.Voice(message.clip.durationMs), message.clip.bytes, asKid)

            is OutgoingMessage.Photo -> {
                val size = JpegSize.of(message.jpeg)
                val ref = ChatPhotoRef(JvmSha256Hasher.sha256(message.jpeg).toHex(), size.width, size.height)
                writes.sendWithBlob(thread, MessagePlaintext.Photo(ref, message.caption), message.jpeg, asKid)
            }
        }
    }

    private fun asKid(): Boolean = perspective.kidMode
}

/**
 * [ThreadKeyStatus] over [ThreadKeyStatusReader]: not sendable means "waiting for a parent device", unless this
 * phone's chat key conflicts with the registry (audit I2): then the screen says the phone must be set up again.
 */
class RoomThreadKeyStatus @Inject constructor(private val reader: ThreadKeyStatusReader) : ThreadKeyStatus {
    override fun observe(threadId: KidId): Flow<ThreadKeyState> = reader.observe(ThreadId.of(threadId)).map {
        when (it) {
            ThreadKeyAvailability.SENDABLE -> ThreadKeyState.USABLE
            ThreadKeyAvailability.WAITING -> ThreadKeyState.WAITING_FOR_PARENT
            ThreadKeyAvailability.KEY_CONFLICT -> ThreadKeyState.KEY_CONFLICT
        }
    }
}
