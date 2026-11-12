package dev.kpetriv.sprout.feature.chat

import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.KidUiTier
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Fixed chat states for previews, screen tests and screenshot goldens. No real people or messages. */
@Suppress("MagicNumber", "TooManyFunctions") // Fixed preview minutes; one function per previewed state.
object ChatPreviewData {
    private val start = Instant.parse("2026-11-04T16:00:00Z")
    private const val VOICE_MS = 12_000L
    private const val RECORDING_MS = 8_000L

    private val names = mapOf(KidUiTier.SEEDLING to "Mia", KidUiTier.SPROUT to "Leo", KidUiTier.SAPLING to "Ava")

    fun threads() = ChatThreadListUiState.Ready(
        listOf(
            ThreadRowUi(KidId("kid-mia"), "Mia", unreadCount = 2, lastMessageAt = start + 30.minutes),
            ThreadRowUi(KidId("kid-leo"), "Leo", unreadCount = 0, lastMessageAt = start - 90.minutes),
            ThreadRowUi(KidId("kid-ava"), "Ava", unreadCount = 0, lastMessageAt = null),
        ),
    )

    private fun at(minutes: Int) = start + minutes.minutes

    private fun theirs(id: String, minute: Int, author: String?, kind: MessageKind) =
        MessageUi(id, MessageDirection.THEIRS, kind, at(minute), SendState.SENT, authorName = author)

    private fun mine(id: String, minute: Int, kind: MessageKind, state: SendState = SendState.SENT) =
        MessageUi(id, MessageDirection.MINE, kind, at(minute), state)

    private fun seedlingMessages() = listOf(
        theirs("m1", 0, "Mum", MessageKind.PRESET).copy(presetId = "love_you"),
        mine("m2", 1, MessageKind.PRESET).copy(presetId = "hello", emoji = "❤️"),
        theirs("m3", 2, "Dad", MessageKind.VOICE).copy(durationMs = VOICE_MS),
        theirs("m4", 3, "Mum", MessageKind.TEXT).copy(text = "Dinner is at six"),
        mine("m5", 4, MessageKind.TEXT).copy(text = "🐶"),
    )

    private fun textMessages(kidName: String?, photo: PhotoUi) = listOf(
        theirs("m1", 0, kidName ?: "Mum", MessageKind.TEXT).copy(text = "Can I go to the park after school?"),
        mine("m2", 2, MessageKind.TEXT).copy(text = "Yes, back by five please."),
        theirs("m3", 3, kidName ?: "Mum", MessageKind.PRESET).copy(presetId = "yes", emoji = "🎉"),
        mine("m4", 5, MessageKind.PHOTO).copy(photo = photo, text = "Look what I found"),
        theirs("m5", 6, kidName ?: "Dad", MessageKind.VOICE).copy(durationMs = VOICE_MS),
        mine("m6", 8, MessageKind.TEXT, SendState.SENDING).copy(text = "See you soon"),
    )

    private fun base(mode: ChatMode, tier: KidUiTier?, messages: List<MessageUi>) = ChatUiState.Ready(
        mode = mode,
        tier = tier,
        kidName = tier?.let(names::getValue) ?: "Leo",
        messages = messages,
        hasMore = false,
        keyState = ThreadKeyState.USABLE,
    )

    /** The parent's thread with [kidName]'s messages on the left. */
    fun parentThread(photo: PhotoUi = PhotoUi.Unavailable) =
        base(ChatMode.PARENT, null, textMessages("Leo", photo)).copy(hasMore = true)

    fun parentEmpty() = base(ChatMode.PARENT, null, emptyList())

    /** Waiting for a thread key, with older messages this device cannot read. */
    fun parentLocked() = base(
        ChatMode.PARENT,
        null,
        listOf(
            theirs("m1", 0, "Leo", MessageKind.TEXT).copy(locked = true),
            theirs("m2", 1, "Leo", MessageKind.VOICE).copy(locked = true),
        ),
    ).copy(keyState = ThreadKeyState.WAITING_FOR_PARENT)

    fun kidMessages(tier: KidUiTier, photo: PhotoUi = PhotoUi.Unavailable) = base(
        ChatMode.KID,
        tier,
        if (tier == KidUiTier.SEEDLING) seedlingMessages() else textMessages(null, photo),
    )

    fun kidEmpty(tier: KidUiTier) = base(ChatMode.KID, tier, emptyList())

    /** Messages from before this device had the key, and no key for sending yet. */
    fun kidLocked(tier: KidUiTier) = base(
        ChatMode.KID,
        tier,
        listOf(
            theirs("m1", 0, "Mum", MessageKind.TEXT).copy(locked = true),
            theirs("m2", 1, "Dad", MessageKind.PHOTO).copy(locked = true),
            mine("m3", 2, MessageKind.PRESET).copy(locked = true),
        ),
    ).copy(keyState = ThreadKeyState.WAITING_FOR_PARENT)

    /** This phone's chat key was replaced in the registry (audit I2): remove and re-link it. */
    fun kidKeyConflict(tier: KidUiTier) = kidLocked(tier).copy(keyState = ThreadKeyState.KEY_CONFLICT)

    /** Seedling mid-recording; other tiers show the thread (they have no microphone). */
    fun kidRecording(tier: KidUiTier) = kidMessages(tier).copy(recording = RecordingUi(RECORDING_MS))

    /** The last message failed to send; Seedling had a preset and an emoji picked. */
    fun kidFailed(tier: KidUiTier): ChatUiState.Ready {
        val failed = if (tier == KidUiTier.SEEDLING) {
            mine("local-0", 9, MessageKind.PRESET, SendState.FAILED).copy(presetId = "hungry", pending = true)
        } else {
            mine("local-0", 9, MessageKind.TEXT, SendState.FAILED).copy(text = "Can we have pizza?", pending = true)
        }
        val messages = kidMessages(tier).messages.filterNot { it.sendState == SendState.SENDING } + failed
        return kidMessages(tier).copy(
            messages = messages,
            selectedPreset = if (tier == KidUiTier.SEEDLING) "play" else null,
            selectedEmoji = if (tier == KidUiTier.SEEDLING) "🐶" else null,
            draft = if (tier == KidUiTier.SEEDLING) "" else "Please?",
        )
    }

    /** Sprout and Sapling: the kindness filter suggested a second look. */
    fun kidNudge(tier: KidUiTier) = kidMessages(tier).copy(
        draft = "You never let me do anything",
        nudge = NudgeUi("You never let me do anything", "Can we talk about it later?"),
    )

    /**
     * A parent's own messages in every send state, a kid message written on a parent's phone and one that arrived
     * late.
     */
    fun parentSendStates() = base(
        ChatMode.PARENT,
        null,
        listOf(
            theirs("m1", 0, "Leo", MessageKind.TEXT).copy(text = "Can I have a snack?", fromParentPhone = true),
            mine("m2", 1, MessageKind.TEXT, SendState.SENDING).copy(text = "After homework"),
            mine("m3", 2, MessageKind.TEXT, SendState.FAILED).copy(text = "Apple or banana?"),
            mine("m4", 3, MessageKind.TEXT, SendState.BLOCKED).copy(text = "Bring your coat"),
            theirs("m5", 4, "Leo", MessageKind.TEXT).copy(text = "I said thank you", late = true),
        ),
    )

    /** One photo still loading and one that could not be fetched. */
    fun parentPhotos() = base(
        ChatMode.PARENT,
        null,
        listOf(
            theirs("m1", 0, "Leo", MessageKind.PHOTO).copy(photo = PhotoUi.Loading),
            theirs("m2", 1, "Leo", MessageKind.PHOTO).copy(photo = PhotoUi.Unavailable, text = "My drawing"),
        ),
    )

    /** A voice note playing: in Seedling, Dad's; in the other tiers, the last one. */
    fun kidVoicePlaying(tier: KidUiTier) = kidMessages(tier).let { state ->
        state.copy(playingId = state.messages.last { it.kind == MessageKind.VOICE }.id)
    }

    /** Sprout and Sapling: the kindness filter is looking at the draft. */
    fun kidChecking(tier: KidUiTier) = kidMessages(tier).copy(draft = "See you at the park", checking = true)
}
