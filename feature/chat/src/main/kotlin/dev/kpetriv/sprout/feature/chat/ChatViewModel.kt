package dev.kpetriv.sprout.feature.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * One chat thread, for a parent ([ChatMode.PARENT]) or the thread's kid ([ChatMode.KID], per [KidUiTier]).
 *
 * Messages come from [MessageReader] (newest [PAGE_SIZE] at first, more on [ChatEvent.LoadOlder]); photos are fetched
 * on demand through [BlobLoader] and kept in memory for the life of the screen. Sending goes through [MessageSender]:
 * a message shows at once as "sending" and leaves this ViewModel's list when the sender has stored it (the reader
 * shows it from then on) or stays as "failed" with retry and discard. Nothing is sent while [ThreadKeyStatus] says
 * the device is waiting for a parent device.
 *
 * Kid text (Sprout and Sapling) passes [OutgoingMessageFilter] first; a nudge is shown but never blocks. The check has
 * [FILTER_TIMEOUT_MILLIS] (D-M6-10): a filter that fails or takes longer lets the text through (fail open). A
 * suggestion the kid takes with [ChatEvent.NudgeUseSuggestion] is sent without a second check while the draft is still
 * exactly that suggestion (D-M6-2). Seedling has no free text: preset tiles, the emoji row and voice notes held to
 * record (at most [VoiceRecorder.MAX_DURATION_MS]).
 *
 * Arguments from the [SavedStateHandle]: [THREAD_ID_KEY] (the kid id), [MODE_KEY] (a [ChatMode] name) and, in kid
 * mode, [TIER_KEY] (a [KidUiTier] name). Message text never reaches a log or a `toString`.
 */
@Suppress("LongParameterList", "TooManyFunctions") // One port per capability the thread screen combines.
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val threads: ChatThreads,
    private val reader: MessageReader,
    private val sender: MessageSender,
    private val keyStatus: ThreadKeyStatus,
    private val blobs: BlobLoader,
    private val recorder: VoiceRecorder,
    private val player: VoicePlayer,
    private val filter: OutgoingMessageFilter,
    private val pickPhoto: PickPhoto,
    private val clock: Clock,
) : ViewModel() {
    private val threadId = KidId(checkNotNull(savedStateHandle.get<String>(THREAD_ID_KEY)) { "threadId missing" })
    private val mode = ChatMode.valueOf(checkNotNull(savedStateHandle.get<String>(MODE_KEY)) { "mode missing" })
    private val tier: KidUiTier? = savedStateHandle.get<String>(TIER_KEY)
        ?.takeIf { mode == ChatMode.KID }
        ?.let(KidUiTier::valueOf)

    private val refreshes = MutableStateFlow(0)
    private val limit = MutableStateFlow(PAGE_SIZE)
    private val local = MutableStateFlow(LocalState())
    private val photos = MutableStateFlow<Map<String, PhotoUi>>(emptyMap())
    private var nextLocalId = 0L
    private var recordingEnd: CompletableDeferred<Boolean>? = null
    private var playback: Job? = null
    private var lastPage: MessagePage? = null
    private var lastReadId: String? = null

    val state: StateFlow<ChatUiState> = refreshes
        .flatMapLatest { content() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), ChatUiState.Loading)

    @Suppress("CyclomaticComplexMethod") // A flat dispatch: one branch per event.
    fun onEvent(event: ChatEvent) {
        when (event) {
            // Review M7: the field is disabled while the check runs, and an edit that still arrives is ignored, so
            // the text checked is the text sent.
            is ChatEvent.DraftChanged -> if (!local.value.checking) local.update {
                val draft = event.text.truncatedTo(OutgoingMessage.MAX_TEXT_LENGTH)
                val accepted = it.acceptedSuggestion?.takeIf { suggestion -> suggestion == draft }
                it.copy(draft = draft, nudge = null, acceptedSuggestion = accepted)
            }

            ChatEvent.SendText -> sendDraft()

            is ChatEvent.SendPreset -> if (ChatPresets.find(event.presetId) != null) {
                send(OutgoingMessage.Preset(event.presetId, emoji = null))
            }

            is ChatEvent.SelectPreset -> local.update {
                it.copy(selectedPreset = event.presetId.takeIf { id -> id != it.selectedPreset })
            }

            is ChatEvent.SelectEmoji -> local.update {
                it.copy(selectedEmoji = event.emoji.takeIf { emoji -> emoji != it.selectedEmoji })
            }

            ChatEvent.SendSelection -> sendSelection()

            ChatEvent.StartRecording -> startRecording()

            ChatEvent.StopRecording -> recordingEnd?.complete(true)

            ChatEvent.CancelRecording -> recordingEnd?.complete(false)

            ChatEvent.MicPermissionDenied -> notify(ChatNotice.MIC_PERMISSION_DENIED)

            is ChatEvent.PlayVoice -> togglePlayback(event.messageId)

            is ChatEvent.PhotoPicked -> attachPhoto(event.uri)

            is ChatEvent.Retry -> retry(event.messageId)

            is ChatEvent.Discard -> discard(event.messageId)

            ChatEvent.NudgeSendAnyway -> nudgeSendAnyway()

            ChatEvent.NudgeUseSuggestion -> local.update { s ->
                val suggestion = s.nudge?.suggestion?.truncatedTo(OutgoingMessage.MAX_TEXT_LENGTH)
                s.copy(
                    draft = suggestion ?: s.draft,
                    nudge = null,
                    acceptedSuggestion = suggestion ?: s.acceptedSuggestion,
                )
            }

            ChatEvent.NudgeDismiss -> local.update { it.copy(nudge = null) }

            ChatEvent.LoadOlder -> if ((state.value as? ChatUiState.Ready)?.hasMore == true) {
                limit.update { it + PAGE_SIZE }
            }

            ChatEvent.NoticeShown -> local.update { it.copy(notice = null) }

            ChatEvent.Reload -> {
                // Photos that could not be loaded are asked for again.
                photos.update { map -> map.filterValues { it != PhotoUi.Unavailable } }
                refreshes.update { it + 1 }
            }

            ChatEvent.ScreenStopped -> {
                // A hold the screen lost (backgrounded, locked, a call) is a cancel, never a send.
                recordingEnd?.complete(false)
                stopPlayback()
            }
        }
    }

    /** A failed message never stored leaves the list; a stored one whose sending is blocked is discarded. */
    private fun discard(messageId: String) {
        val pending = local.value.pending.firstOrNull { it.id == messageId }
        if (pending != null) {
            if (pending.failed) local.update { s -> s.copy(pending = s.pending.filterNot { it.id == messageId }) }
            return
        }
        val blocked = ready()?.messages?.any { it.id == messageId && it.sendState == SendState.BLOCKED } == true
        if (!blocked) return
        viewModelScope.launch {
            if (attempt { sender.discard(threadId, messageId) }.isFailure) notify(ChatNotice.DISCARD_FAILED)
        }
    }

    override fun onCleared() {
        if (recordingEnd != null) recorder.cancel()
        player.stop()
    }

    private fun content(): Flow<ChatUiState> {
        val pages = limit.flatMapLatest { reader.observe(threadId, it) }.onEach(::onPage)
        val thread = threads.observeThread(threadId)
        val keys = keyStatus.observe(threadId)
        return combine(thread, pages, keys, local, photos) { summary, page, key, localState, photoMap ->
            if (summary == null) {
                ChatUiState.Error(ChatError.THREAD_NOT_FOUND)
            } else {
                ready(summary.kidName, page, key, localState, photoMap)
            }
        }.catch { emit(ChatUiState.Error(ChatError.LOAD_FAILED)) }
    }

    private fun ready(
        kidName: String,
        page: MessagePage,
        key: ThreadKeyState,
        localState: LocalState,
        photoMap: Map<String, PhotoUi>,
    ) = ChatUiState.Ready(
        mode = mode,
        tier = tier,
        kidName = kidName,
        messages = page.messages.map { it.toUi(photoMap) } + localState.pending.map { it.toUi() },
        hasMore = page.hasMore,
        keyState = key,
        draft = localState.draft,
        selectedPreset = localState.selectedPreset,
        selectedEmoji = localState.selectedEmoji,
        recording = localState.recording,
        playingId = localState.playingId,
        checking = localState.checking,
        nudge = localState.nudge,
        notice = localState.notice,
    )

    private fun requestPhoto(message: ChatMessage) {
        val blobId = message.blobId
        if (message.kind != MessageKind.PHOTO || message.locked || blobId == null) return
        var requested = false
        photos.update {
            requested = blobId !in it
            if (requested) it + (blobId to PhotoUi.Loading) else it
        }
        if (!requested) return
        val expected = BlobExpectation(threadId, message.authorDeviceId, message.photoSha256)
        viewModelScope.launch {
            val photo = attempt { blobs.load(blobId, expected) }.fold({ PhotoUi.Loaded(it) }, { PhotoUi.Unavailable })
            photos.update { it + (blobId to photo) }
        }
    }

    /** Fetches the page's photos and marks the thread read when a new message from someone else arrived. */
    private fun onPage(page: MessagePage) {
        lastPage = page
        page.messages.forEach(::requestPhoto)
        val newestTheirs = page.messages.lastOrNull { it.direction == MessageDirection.THEIRS }?.id
        if (newestTheirs != null && newestTheirs != lastReadId) {
            lastReadId = newestTheirs
            viewModelScope.launch {
                attempt {
                    threads.markRead(threadId)
                    Result.success(Unit)
                }
            }
        }
    }

    private fun ready(): ChatUiState.Ready? = state.value as? ChatUiState.Ready

    private fun sendDraft() {
        // Seedling has no free text; canSendDraft means a usable key and a non-blank draft.
        val ready = ready()?.takeIf { it.canSendDraft && tier != KidUiTier.SEEDLING } ?: return
        val text = ready.draft.trim()
        when (val kidTier = tier) {
            null -> {
                local.update { it.copy(draft = "") }
                send(OutgoingMessage.Text(text))
            }

            else -> if (local.value.acceptedSuggestion == ready.draft) {
                // D-M6-2: the kid took the filter's own suggestion and did not change it; checking it again could
                // flag it and loop.
                local.update { it.copy(draft = "", acceptedSuggestion = null) }
                send(OutgoingMessage.Text(text))
            } else {
                checkThenSend(text, kidTier)
            }
        }
    }

    /**
     * Runs the kindness filter first; a nudge is shown instead of sending. A filter that fails or does not answer
     * within [FILTER_TIMEOUT_MILLIS] lets the text through (fail open, D-M6-10). `checking` is always cleared, however
     * the check ends (review M6).
     */
    private fun checkThenSend(text: String, kidTier: KidUiTier) {
        local.update { it.copy(checking = true) }
        viewModelScope.launch {
            try {
                when (val verdict = checkKindness(text, kidTier)) {
                    FilterVerdict.Ok -> {
                        local.update { it.copy(checking = false, draft = "", acceptedSuggestion = null) }
                        send(OutgoingMessage.Text(text))
                    }

                    is FilterVerdict.Nudge -> local.update {
                        it.copy(checking = false, nudge = NudgeUi(text, verdict.suggestion))
                    }
                }
            } finally {
                local.update { it.copy(checking = false) }
            }
        }
    }

    /**
     * The filter's verdict, or [FilterVerdict.Ok] when it fails, times out or throws a [CancellationException] of its
     * own (an ML Kit future cancelled by AICore or Play services while this coroutine is still active, review M6).
     * Cancellation of this coroutine itself (the screen left) still propagates.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // Fail open; nothing is logged.
    private suspend fun checkKindness(text: String, kidTier: KidUiTier): FilterVerdict = try {
        withTimeoutOrNull(FILTER_TIMEOUT_MILLIS.milliseconds) { filter.check(text, kidTier) } ?: FilterVerdict.Ok
    } catch (cancelled: CancellationException) {
        currentCoroutineContext().ensureActive()
        FilterVerdict.Ok
    } catch (failure: Exception) {
        FilterVerdict.Ok
    }

    private fun nudgeSendAnyway() {
        val nudge = local.value.nudge ?: return
        if (ready()?.canSend != true) return
        local.update { it.copy(nudge = null, draft = "", acceptedSuggestion = null) }
        send(OutgoingMessage.Text(nudge.text))
    }

    private fun sendSelection() {
        val ready = ready() ?: return
        if (!ready.canSendSelection) return
        val preset = ready.selectedPreset
        val emoji = ready.selectedEmoji
        local.update { it.copy(selectedPreset = null, selectedEmoji = null) }
        when {
            preset != null -> send(OutgoingMessage.Preset(preset, emoji))
            emoji != null -> send(OutgoingMessage.Text(emoji))
        }
    }

    /** Shows [message] as sending at once and hands it to [MessageSender]. */
    private fun send(message: OutgoingMessage) {
        if (ready()?.canSend != true) return
        val id = "$LOCAL_ID_PREFIX${nextLocalId++}"
        val pending = PendingMessage(id = id, message = message, createdAt = clock.now())
        local.update { it.copy(pending = it.pending + pending) }
        deliver(pending)
    }

    private fun deliver(pending: PendingMessage) {
        viewModelScope.launch {
            val stored = attempt { sender.send(threadId, pending.message) }.isSuccess
            local.update { s ->
                s.copy(
                    pending = if (stored) {
                        s.pending.filterNot { it.id == pending.id }
                    } else {
                        s.pending.map { if (it.id == pending.id) it.copy(failed = true) else it }
                    },
                )
            }
        }
    }

    private fun retry(messageId: String) {
        if (ready()?.canSend != true) return
        val pending = local.value.pending.firstOrNull { it.id == messageId }
        if (pending != null) {
            if (pending.failed) {
                local.update { s ->
                    s.copy(pending = s.pending.map { if (it.id == messageId) it.copy(failed = false) else it })
                }
                deliver(pending)
            }
        } else if (ready()?.messages?.any { it.id == messageId && it.sendState == SendState.FAILED } == true) {
            viewModelScope.launch {
                if (attempt { sender.retry(threadId, messageId) }.isFailure) notify(ChatNotice.RETRY_FAILED)
            }
        }
    }

    private fun attachPhoto(uri: String) {
        if (mode != ChatMode.PARENT || ready()?.canSend != true) return
        viewModelScope.launch {
            attempt { pickPhoto.read(uri) }.fold(
                onSuccess = { send(OutgoingMessage.Photo(it)) },
                onFailure = { notify(ChatNotice.PHOTO_FAILED) },
            )
        }
    }

    /**
     * Records until [ChatEvent.StopRecording] (send), [ChatEvent.CancelRecording] (discard) or the time limit (send).
     * A stop that arrives while the recorder is still starting is honoured once it has started.
     */
    private fun startRecording() {
        if (recordingEnd != null || mode != ChatMode.KID || ready()?.canSend != true) return
        val end = CompletableDeferred<Boolean>()
        recordingEnd = end
        stopPlayback()
        local.update { it.copy(recording = RecordingUi(elapsedMs = 0)) }
        viewModelScope.launch {
            if (attempt { recorder.start() }.isFailure) {
                finishRecording(ChatNotice.MIC_UNAVAILABLE)
                return@launch
            }
            val ticker = launch {
                var elapsed = 0L
                while (elapsed < VoiceRecorder.MAX_DURATION_MS) {
                    delay(RECORDING_TICK_MS)
                    elapsed += RECORDING_TICK_MS
                    local.update { s -> s.copy(recording = s.recording?.copy(elapsedMs = elapsed)) }
                }
                end.complete(true)
            }
            val keep = end.await()
            ticker.cancel()
            if (!keep) {
                recorder.cancel()
                finishRecording(null)
                return@launch
            }
            val clip = attempt { recorder.stop() }
            finishRecording(
                when {
                    clip.isFailure -> ChatNotice.RECORDING_FAILED
                    clip.getOrThrow().durationMs < VoiceRecorder.MIN_DURATION_MS -> ChatNotice.VOICE_TOO_SHORT
                    else -> null
                },
            )
            clip.getOrNull()
                ?.takeIf { it.durationMs >= VoiceRecorder.MIN_DURATION_MS }
                ?.let { send(OutgoingMessage.Voice(it)) }
        }
    }

    private fun finishRecording(notice: ChatNotice?) {
        recordingEnd = null
        local.update { it.copy(recording = null, notice = notice ?: it.notice) }
    }

    private fun togglePlayback(messageId: String) {
        val playing = local.value.playingId
        stopPlayback()
        val audio = audioOf(messageId)
        if (playing == messageId || recordingEnd != null || audio == null) return
        local.update { it.copy(playingId = messageId) }
        playback = viewModelScope.launch {
            val played = attempt { audio() }.fold(
                onSuccess = { bytes ->
                    attempt {
                        player.play(bytes)
                        Result.success(Unit)
                    }.isSuccess
                },
                onFailure = { false },
            )
            local.update { it.copy(playingId = null, notice = if (played) it.notice else ChatNotice.PLAYBACK_FAILED) }
        }
    }

    /** How to get [messageId]'s voice bytes: the clip still being sent, or the stored message's blob. */
    private fun audioOf(messageId: String): (suspend () -> Result<ByteArray>)? {
        if (ready()?.messages?.any { it.id == messageId && it.isVoice } != true) return null
        val clip = (local.value.pending.firstOrNull { it.id == messageId }?.message as? OutgoingMessage.Voice)?.clip
        val stored = lastPage?.messages?.firstOrNull { it.id == messageId }
        val blobId = stored?.blobId
        return when {
            clip != null -> {
                { Result.success(clip.bytes) }
            }

            blobId != null -> {
                { blobs.load(blobId, BlobExpectation(threadId, stored.authorDeviceId, sha256 = null)) }
            }

            else -> null
        }
    }

    private fun stopPlayback() {
        if (playback?.isActive == true) {
            playback?.cancel()
            player.stop()
        }
        playback = null
        local.update { it.copy(playingId = null) }
    }

    private fun ChatMessage.toUi(photoMap: Map<String, PhotoUi>): MessageUi = MessageUi(
        id = id,
        direction = direction,
        kind = kind,
        createdAt = createdAt,
        sendState = if (direction == MessageDirection.THEIRS) SendState.SENT else sendState,
        locked = locked,
        text = text.takeUnless { locked },
        presetId = presetId.takeUnless { locked },
        emoji = emoji.takeUnless { locked },
        durationMs = durationMs.takeUnless { locked },
        photo = if (kind == MessageKind.PHOTO && !locked) photoMap[blobId] ?: PhotoUi.Unavailable else null,
        authorName = authorName,
        fromParentPhone = fromParentPhone,
        late = late,
    )

    private fun notify(notice: ChatNotice) = local.update { it.copy(notice = notice) }

    /** [call]; a thrown exception (not cancellation) becomes a failed result. */
    @Suppress("TooGenericExceptionCaught") // A storage or media failure must surface as a notice, not crash the chat.
    private suspend fun <T> attempt(call: suspend () -> Result<T>): Result<T> = try {
        call()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    private data class PendingMessage(
        val id: String,
        val message: OutgoingMessage,
        val createdAt: Instant,
        val failed: Boolean = false,
    ) {
        fun toUi(): MessageUi {
            val base = MessageUi(
                id = id,
                direction = MessageDirection.MINE,
                kind = MessageKind.TEXT,
                createdAt = createdAt,
                sendState = if (failed) SendState.FAILED else SendState.SENDING,
                pending = true,
            )
            return when (message) {
                is OutgoingMessage.Text -> base.copy(text = message.text)

                is OutgoingMessage.Preset ->
                    base.copy(kind = MessageKind.PRESET, presetId = message.presetId, emoji = message.emoji)

                is OutgoingMessage.Voice -> base.copy(kind = MessageKind.VOICE, durationMs = message.clip.durationMs)

                is OutgoingMessage.Photo -> base.copy(
                    kind = MessageKind.PHOTO,
                    text = message.caption,
                    photo = PhotoUi.Loaded(message.jpeg),
                )
            }
        }

        override fun toString(): String = "PendingMessage(id=$id, failed=$failed)"
    }

    private data class LocalState(
        val draft: String = "",
        val pending: List<PendingMessage> = emptyList(),
        val selectedPreset: String? = null,
        val selectedEmoji: String? = null,
        val recording: RecordingUi? = null,
        val playingId: String? = null,
        val checking: Boolean = false,
        val nudge: NudgeUi? = null,
        val notice: ChatNotice? = null,
        /** The suggestion the kid took into the draft (D-M6-2); cleared as soon as the draft differs from it. */
        val acceptedSuggestion: String? = null,
    ) {
        override fun toString(): String = "LocalState(pending=${pending.size}, recording=$recording)"
    }

    companion object {
        /** [SavedStateHandle] key of the thread's kid id string. */
        const val THREAD_ID_KEY = "threadId"

        /** [SavedStateHandle] key of the [ChatMode] name. */
        const val MODE_KEY = "chatMode"

        /** [SavedStateHandle] key of the [KidUiTier] name (kid mode only). */
        const val TIER_KEY = "tier"

        /** Messages per page. */
        const val PAGE_SIZE = 50

        /** How long the kindness check may take before the text is sent unchecked (D-M6-10). */
        const val FILTER_TIMEOUT_MILLIS = 2_500L

        private const val LOCAL_ID_PREFIX = "local-"
        private const val RECORDING_TICK_MS = 1_000L
        private const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

/**
 * This text cut to at most [maxChars] UTF-16 units without splitting a surrogate pair (review M7): an emoji at the
 * boundary is dropped whole rather than leaving half of it, which could not be encoded or sent.
 */
internal fun String.truncatedTo(maxChars: Int): String {
    if (length <= maxChars) return this
    val end = if (maxChars > 0 && Character.isHighSurrogate(this[maxChars - 1])) maxChars - 1 else maxChars
    return substring(0, end)
}
