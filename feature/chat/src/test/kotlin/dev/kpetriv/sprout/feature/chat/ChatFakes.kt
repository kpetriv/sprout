package dev.kpetriv.sprout.feature.chat

import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.KidUiTier
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Keeps a WhileSubscribed state flow hot for the rest of the test. */
@OptIn(ExperimentalCoroutinesApi::class)
fun <T> TestScope.subscribe(state: StateFlow<T>): StateFlow<T> {
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { state.collect { } }
    return state
}

object ChatTestData {
    val leo = KidId("kid-leo")
    val start: Instant = Instant.parse("2026-11-04T16:00:00Z")

    fun summary(kid: KidId = leo, name: String = "Leo", unread: Int = 0) =
        ChatThreadSummary(kid, name, "avatar-1", unread, start)

    fun text(id: String, text: String, direction: MessageDirection = MessageDirection.THEIRS, minute: Int = 0) =
        ChatMessage(id, direction, MessageKind.TEXT, start + minute.minutes, text = text, authorName = "Leo")

    fun photo(id: String, blobId: String, author: String = "kid-device", sha256: String = "ab".repeat(32)) =
        ChatMessage(
            id,
            MessageDirection.THEIRS,
            MessageKind.PHOTO,
            start,
            blobId = blobId,
            authorDeviceId = author,
            photoSha256 = sha256,
        )

    fun voice(id: String, blobId: String) =
        ChatMessage(id, MessageDirection.THEIRS, MessageKind.VOICE, start, durationMs = 4_000, blobId = blobId)
}

class FakeChatThreads(initial: List<ChatThreadSummary> = listOf(ChatTestData.summary())) : ChatThreads {
    val threads = MutableStateFlow(initial)
    var failure: Throwable? = null
    val markedRead = mutableListOf<KidId>()

    override fun observeThreads(): Flow<List<ChatThreadSummary>> = threads.map { failure?.let { e -> throw e } ?: it }

    override fun observeThread(threadId: KidId): Flow<ChatThreadSummary?> =
        threads.map { list -> list.firstOrNull { it.threadId == threadId } }

    override suspend fun markRead(threadId: KidId) {
        markedRead += threadId
    }
}

class FakeMessageReader : MessageReader {
    val messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    var failure: Throwable? = null
    val limits = mutableListOf<Int>()

    override fun observe(threadId: KidId, limit: Int): Flow<MessagePage> {
        limits += limit
        return messages.map { all ->
            failure?.let { throw it }
            MessagePage(all.takeLast(limit), hasMore = all.size > limit)
        }
    }
}

class FakeMessageSender : MessageSender {
    val sent = mutableListOf<OutgoingMessage>()
    val retried = mutableListOf<String>()
    val discarded = mutableListOf<String>()
    var result: Result<Unit> = Result.success(Unit)
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun send(threadId: KidId, message: OutgoingMessage): Result<Unit> {
        sent += message
        gate?.await()
        return result
    }

    override suspend fun retry(threadId: KidId, messageId: String): Result<Unit> {
        retried += messageId
        return result
    }

    override suspend fun discard(threadId: KidId, messageId: String): Result<Unit> {
        discarded += messageId
        return result
    }
}

class FakeKeyStatus(initial: ThreadKeyState = ThreadKeyState.USABLE) : ThreadKeyStatus {
    val state = MutableStateFlow(initial)

    override fun observe(threadId: KidId): Flow<ThreadKeyState> = state
}

class FakeBlobLoader : BlobLoader {
    val blobs = mutableMapOf<String, ByteArray>()
    val loads = mutableListOf<String>()
    val expectations = mutableListOf<BlobExpectation>()

    override suspend fun load(blobId: String, expected: BlobExpectation): Result<ByteArray> {
        loads += blobId
        expectations += expected
        return blobs[blobId]?.let { Result.success(it) } ?: Result.failure(IllegalStateException("missing"))
    }
}

class FakeVoiceRecorder : VoiceRecorder {
    var startResult: Result<Unit> = Result.success(Unit)
    var clip = VoiceClip(byteArrayOf(1, 2, 3), durationMs = 5_000)
    var startGate: CompletableDeferred<Unit>? = null
    val calls = mutableListOf<String>()

    override suspend fun start(): Result<Unit> {
        calls += "start"
        startGate?.await()
        return startResult
    }

    override suspend fun stop(): Result<VoiceClip> {
        calls += "stop"
        return Result.success(clip)
    }

    override fun cancel() {
        calls += "cancel"
    }
}

class FakeVoicePlayer : VoicePlayer {
    val played = mutableListOf<ByteArray>()
    var stops = 0
    var playback = CompletableDeferred<Unit>()

    override suspend fun play(bytes: ByteArray) {
        played += bytes
        playback.await()
    }

    override fun stop() {
        stops++
    }
}

class FakeFilter : OutgoingMessageFilter {
    var verdict: FilterVerdict = FilterVerdict.Ok
    var failure: Throwable? = null
    val checked = mutableListOf<Pair<String, KidUiTier>>()

    override suspend fun check(text: String, tier: KidUiTier): FilterVerdict {
        checked += text to tier
        failure?.let { throw it }
        return verdict
    }
}

class FakePickPhoto : PickPhoto {
    var result: Result<ByteArray> = Result.success(byteArrayOf(9, 9))
    val reads = mutableListOf<String>()

    override suspend fun read(uri: String): Result<ByteArray> {
        reads += uri
        return result
    }
}
