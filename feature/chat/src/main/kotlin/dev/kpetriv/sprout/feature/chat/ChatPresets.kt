package dev.kpetriv.sprout.feature.chat

import androidx.annotation.StringRes

/**
 * A Seedling / Sprout preset message. [id] is what travels in the PRESET plaintext (`{"preset": id}`), so the receiver
 * shows it in its own language; ids are stable and never reused.
 */
data class ChatPreset(val id: String, val emoji: String, @param:StringRes val label: Int)

/** An emoji of the Seedling row; [name] is what a screen reader says. */
data class ChatEmoji(val emoji: String, @param:StringRes val name: Int)

/** The twelve presets and the emoji row. Labels live in `strings.xml`. */
object ChatPresets {
    val all: List<ChatPreset> = listOf(
        ChatPreset("hello", "👋", R.string.feature_chat_preset_hello),
        ChatPreset("love_you", "❤️", R.string.feature_chat_preset_love_you),
        ChatPreset("miss_you", "🤗", R.string.feature_chat_preset_miss_you),
        ChatPreset("yes", "👍", R.string.feature_chat_preset_yes),
        ChatPreset("no", "👎", R.string.feature_chat_preset_no),
        ChatPreset("help", "🙋", R.string.feature_chat_preset_help),
        ChatPreset("hungry", "🍎", R.string.feature_chat_preset_hungry),
        ChatPreset("come_home", "🏠", R.string.feature_chat_preset_come_home),
        ChatPreset("play", "⚽", R.string.feature_chat_preset_play),
        ChatPreset("happy", "😀", R.string.feature_chat_preset_happy),
        ChatPreset("sad", "😢", R.string.feature_chat_preset_sad),
        ChatPreset("good_night", "🌙", R.string.feature_chat_preset_good_night),
    )

    /** The Seedling emoji row: sent on their own (as a one-emoji TEXT) or added to a preset. */
    val emojis: List<ChatEmoji> = listOf(
        ChatEmoji("❤️", R.string.feature_chat_emoji_heart),
        ChatEmoji("😀", R.string.feature_chat_emoji_smile),
        ChatEmoji("😢", R.string.feature_chat_emoji_sad),
        ChatEmoji("👍", R.string.feature_chat_emoji_thumbs_up),
        ChatEmoji("🎉", R.string.feature_chat_emoji_party),
        ChatEmoji("🐶", R.string.feature_chat_emoji_dog),
    )

    private val byId = all.associateBy { it.id }

    /** The preset for [id]; null for an id this version does not know (shown as a generic preset). */
    fun find(id: String?): ChatPreset? = id?.let(byId::get)
}
