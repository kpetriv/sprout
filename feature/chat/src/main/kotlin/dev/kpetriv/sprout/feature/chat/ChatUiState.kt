package dev.kpetriv.sprout.feature.chat

import dev.kpetriv.sprout.core.model.KidUiTier
import kotlin.time.Instant

/** Who is looking at a thread. */
enum class ChatMode {
    /** A parent, behind the parent gate: free text, photos, voice playback. */
    PARENT,

    /** The kid, in kid mode: content per [KidUiTier]. */
    KID,
}

/**
 * Everything a chat thread shows. One instance per emission of [ChatViewModel.state].
 *
 * Every state is [secure]: the app keeps `FLAG_SECURE` on the window while a chat screen is shown (`SecureWindow` from
 * `feature/lock`, which this module cannot depend on).
 */
sealed interface ChatUiState {
    /** The window must not appear in screenshots, recordings or recents. */
    val secure: Boolean get() = true

    data object Loading : ChatUiState

    data class Error(val reason: ChatError) : ChatUiState

    /**
     * @property tier the kid's tier in [ChatMode.KID]; null for a parent.
     * @property kidName the thread's kid; the parent's title.
     * @property messages oldest first: stored messages, then the ones still being handed to [MessageSender].
     * @property hasMore older messages exist ([ChatEvent.LoadOlder]).
     * @property keyState [ThreadKeyState.WAITING_FOR_PARENT] disables sending and shows a banner.
     * @property draft the free-text composer (Sprout, Sapling, parent).
     * @property selectedPreset the Seedling preset tile picked for [ChatEvent.SendSelection].
     * @property selectedEmoji the Seedling emoji picked for [ChatEvent.SendSelection].
     * @property recording a voice note is being recorded (Seedling).
     * @property playingId the message whose voice note is playing.
     * @property checking the kindness filter is looking at the draft.
     * @property nudge the kindness filter's nudge for the draft, until the kid sends anyway, edits or dismisses it.
     * @property notice a one-off message for a snackbar, until [ChatEvent.NoticeShown].
     */
    data class Ready(
        val mode: ChatMode,
        val tier: KidUiTier?,
        val kidName: String,
        val messages: List<MessageUi>,
        val hasMore: Boolean,
        val keyState: ThreadKeyState,
        val draft: String = "",
        val selectedPreset: String? = null,
        val selectedEmoji: String? = null,
        val recording: RecordingUi? = null,
        val playingId: String? = null,
        val checking: Boolean = false,
        val nudge: NudgeUi? = null,
        val notice: ChatNotice? = null,
    ) : ChatUiState {
        val canSend: Boolean get() = keyState == ThreadKeyState.USABLE

        val canSendDraft: Boolean get() = canSend && !checking && nudge == null && draft.isNotBlank()

        val canSendSelection: Boolean get() = canSend && (selectedPreset != null || selectedEmoji != null)

        override fun toString(): String =
            "Ready(mode=$mode, tier=$tier, messages=${messages.size}, keyState=$keyState, recording=$recording)"
    }
}

enum class ChatError {
    THREAD_NOT_FOUND,
    LOAD_FAILED,
}

/**
 * One bubble. Only the fields for [kind] are set (see [ChatMessage]); a [locked] message has no content.
 *
 * @property pending still being handed to [MessageSender] from this screen (not stored yet); a failed one can be
 *   retried or discarded.
 * @property fromParentPhone a kid's message written in kid mode on a parent's phone: labelled so.
 */
data class MessageUi(
    val id: String,
    val direction: MessageDirection,
    val kind: MessageKind,
    val createdAt: Instant,
    val sendState: SendState,
    val locked: Boolean = false,
    val text: String? = null,
    val presetId: String? = null,
    val emoji: String? = null,
    val durationMs: Long? = null,
    val photo: PhotoUi? = null,
    val authorName: String? = null,
    val pending: Boolean = false,
    val fromParentPhone: Boolean = false,
) {
    val isVoice: Boolean get() = kind == MessageKind.VOICE && !locked

    override fun toString(): String =
        "MessageUi(id=$id, direction=$direction, kind=$kind, sendState=$sendState, locked=$locked)"
}

/** A photo's bytes as far as they have come. */
sealed interface PhotoUi {
    data object Loading : PhotoUi

    /** Decrypted JPEG bytes. */
    class Loaded(val bytes: ByteArray) : PhotoUi {
        override fun equals(other: Any?): Boolean = other is Loaded && other.bytes.contentEquals(bytes)

        override fun hashCode(): Int = bytes.contentHashCode()

        override fun toString(): String = "Loaded(bytes=${bytes.size})"
    }

    /** The blob could not be fetched or decrypted. */
    data object Unavailable : PhotoUi
}

/** A voice note being recorded; [elapsedMs] of at most [VoiceRecorder.MAX_DURATION_MS]. */
data class RecordingUi(val elapsedMs: Long) {
    val remainingMs: Long get() = (VoiceRecorder.MAX_DURATION_MS - elapsedMs).coerceAtLeast(0)
}

/** The kindness nudge for [text]: send it anyway, take [suggestion], or edit. */
data class NudgeUi(val text: String, val suggestion: String?) {
    override fun toString(): String = "NudgeUi(hasSuggestion=${suggestion != null})"
}

/** One-off messages for a snackbar. */
enum class ChatNotice {
    MIC_PERMISSION_DENIED,
    MIC_UNAVAILABLE,
    RECORDING_FAILED,
    VOICE_TOO_SHORT,
    PLAYBACK_FAILED,
    PHOTO_FAILED,
    RETRY_FAILED,
    DISCARD_FAILED,
}

/** Everything a chat screen can ask the [ChatViewModel] to do. */
sealed interface ChatEvent {
    data class DraftChanged(val text: String) : ChatEvent {
        override fun toString(): String = "DraftChanged(length=${text.length})"
    }

    /** Sends the draft (after the kindness filter for Sprout and Sapling kids). */
    data object SendText : ChatEvent

    /** Sends a preset straight away (Sprout chips). */
    data class SendPreset(val presetId: String) : ChatEvent

    /** Picks or un-picks a Seedling preset tile. */
    data class SelectPreset(val presetId: String) : ChatEvent

    /** Picks or un-picks a Seedling emoji. */
    data class SelectEmoji(val emoji: String) : ChatEvent

    /** Sends the Seedling selection: the preset (with the emoji, if any), or the emoji alone. */
    data object SendSelection : ChatEvent

    data object StartRecording : ChatEvent

    /** Finger lifted: stop and send. */
    data object StopRecording : ChatEvent

    /** Finger slid away: stop and throw the recording away. */
    data object CancelRecording : ChatEvent

    /** `RECORD_AUDIO` was refused. */
    data object MicPermissionDenied : ChatEvent

    /** Plays [messageId]'s voice note, or stops it if it is playing. */
    data class PlayVoice(val messageId: String) : ChatEvent

    /** The parent picked a photo ([uri] from the system photo picker). */
    data class PhotoPicked(val uri: String) : ChatEvent

    /** Sends a failed message again. */
    data class Retry(val messageId: String) : ChatEvent

    /** Drops a failed message that was never stored, or removes a stored one whose sending is blocked. */
    data class Discard(val messageId: String) : ChatEvent

    data object NudgeSendAnyway : ChatEvent

    data object NudgeUseSuggestion : ChatEvent

    data object NudgeDismiss : ChatEvent

    data object LoadOlder : ChatEvent

    data object NoticeShown : ChatEvent

    /** Loads the thread again, and asks again for photos that were unavailable. */
    data object Reload : ChatEvent

    /**
     * The screen left the foreground (`ON_STOP`): a recording under way is cancelled, never sent (review M6), and
     * playback stops.
     */
    data object ScreenStopped : ChatEvent
}
