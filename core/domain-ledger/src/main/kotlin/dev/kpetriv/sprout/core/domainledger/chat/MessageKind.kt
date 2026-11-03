package dev.kpetriv.sprout.core.domainledger.chat

import kotlinx.serialization.Serializable

/** What a chat message carries (M4 §1.3). VOICE and PHOTO keep their bytes in a blob named by the envelope. */
@Serializable
enum class MessageKind(val hasBlob: Boolean) {
    TEXT(hasBlob = false),
    PRESET(hasBlob = false),
    VOICE(hasBlob = true),
    PHOTO(hasBlob = true),
}
