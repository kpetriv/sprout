package dev.kpetriv.sprout.core.domainledger.chat

import dev.kpetriv.sprout.core.domainledger.CanonicalEncoder
import dev.kpetriv.sprout.core.model.ID_PATTERN
import dev.kpetriv.sprout.core.model.MAX_CHAT_CAPTION_LENGTH
import dev.kpetriv.sprout.core.model.MAX_CHAT_EMOJI_LENGTH
import dev.kpetriv.sprout.core.model.MAX_CHAT_TEXT_LENGTH
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * What a message says, before encryption (M4 §1.3). The bytes that get encrypted are the canonical JSON of
 * [MessagePlaintextCodec.encode]; each kind has a closed key set:
 *
 * - [Text]: `{"text": "..."}`, 1 to [MAX_CHAT_TEXT_LENGTH] characters, not blank;
 * - [Preset]: `{"preset": "<id>", "emoji": "..." | null}`;
 * - [Voice]: `{"durationMs": n}`, 1 to [ChatLimits.MAX_VOICE_NOTE_MILLIS]; the audio is in the envelope's blob;
 * - [Photo]: `{"photoRef": {"sha256": hex, "width": n, "height": n}, "caption": "..." | null}`; the JPEG is in the
 *   envelope's blob and [ChatPhotoRef.sha256] is the SHA-256 of the decrypted JPEG bytes.
 *
 * `toString` never prints user content.
 */
sealed interface MessagePlaintext {
    val kind: MessageKind

    class Text(val text: String) : MessagePlaintext {
        init {
            require(text.isNotBlank() && text.length <= MAX_CHAT_TEXT_LENGTH) {
                "text must be 1 to $MAX_CHAT_TEXT_LENGTH characters and not blank"
            }
        }

        override val kind: MessageKind get() = MessageKind.TEXT

        override fun equals(other: Any?): Boolean = other is Text && other.text == text

        override fun hashCode(): Int = text.hashCode()

        override fun toString(): String = "Text(<redacted>)"
    }

    /** A Seedling preset tile, by its fixed [presetId], with an optional [emoji]. */
    data class Preset(val presetId: String, val emoji: String? = null) : MessagePlaintext {
        init {
            require(ID_PATTERN.matches(presetId) && presetId.length <= ChatLimits.MAX_PRESET_ID_LENGTH) {
                "presetId must be an id of at most ${ChatLimits.MAX_PRESET_ID_LENGTH} characters"
            }
            require(emoji == null || (emoji.isNotBlank() && emoji.length <= MAX_CHAT_EMOJI_LENGTH)) {
                "emoji must be 1 to $MAX_CHAT_EMOJI_LENGTH characters"
            }
        }

        override val kind: MessageKind get() = MessageKind.PRESET

        override fun toString(): String = "Preset(presetId=$presetId, emoji=${if (emoji == null) "null" else "<set>"})"
    }

    data class Voice(val durationMs: Long) : MessagePlaintext {
        init {
            require(durationMs in 1..ChatLimits.MAX_VOICE_NOTE_MILLIS) {
                "durationMs must be 1 to ${ChatLimits.MAX_VOICE_NOTE_MILLIS}"
            }
        }

        override val kind: MessageKind get() = MessageKind.VOICE
    }

    class Photo(val photoRef: ChatPhotoRef, val caption: String? = null) : MessagePlaintext {
        init {
            require(caption == null || (caption.isNotBlank() && caption.length <= MAX_CHAT_CAPTION_LENGTH)) {
                "caption must be 1 to $MAX_CHAT_CAPTION_LENGTH characters"
            }
        }

        override val kind: MessageKind get() = MessageKind.PHOTO

        override fun equals(other: Any?): Boolean =
            other is Photo && other.photoRef == photoRef && other.caption == caption

        override fun hashCode(): Int = 31 * photoRef.hashCode() + caption.hashCode()

        override fun toString(): String =
            "Photo(photoRef=$photoRef, caption=${if (caption == null) "null" else "<redacted>"})"
    }
}

/** A photo message's image: lower-case hex SHA-256 of the decrypted JPEG and its size in pixels. */
data class ChatPhotoRef(val sha256: String, val width: Int, val height: Int) {
    init {
        require(SHA256_HEX.matches(sha256)) { "sha256 must be 64 lower-case hex characters" }
        require(width in 1..ChatLimits.MAX_PHOTO_DIMENSION && height in 1..ChatLimits.MAX_PHOTO_DIMENSION) {
            "width and height must be 1 to ${ChatLimits.MAX_PHOTO_DIMENSION}"
        }
    }

    private companion object {
        val SHA256_HEX = Regex("[0-9a-f]{64}")
    }
}

/** Canonical JSON form of [MessagePlaintext] (see there for the shapes). */
object MessagePlaintextCodec {
    private const val TEXT = "text"
    private const val PRESET = "preset"
    private const val EMOJI = "emoji"
    private const val DURATION = "durationMs"
    private const val PHOTO_REF = "photoRef"
    private const val CAPTION = "caption"
    private const val SHA256 = "sha256"
    private const val WIDTH = "width"
    private const val HEIGHT = "height"

    /**
     * The canonical UTF-8 bytes of [plaintext] ([CanonicalEncoder.encodeTree]).
     *
     * @throws CharacterCodingException if a string holds an unpaired surrogate.
     */
    fun encode(plaintext: MessagePlaintext): ByteArray = CanonicalEncoder.encodeTree(toJson(plaintext))

    /**
     * Decodes [bytes] as the plaintext of a message of [kind]; null when they are not strict UTF-8 JSON, the key set
     * is not exactly the kind's, a value fails validation, or the result has no canonical encoding (review H1: a JSON
     * escape such as `"\ud800"` decodes to an unpaired surrogate, which [encode] cannot write). So every plaintext
     * this returns can be encoded again: a stored message never fails to re-encode later.
     */
    fun decode(kind: MessageKind, bytes: ByteArray): MessagePlaintext? = try {
        val tree = Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)) as? JsonObject
        tree?.let { fromJson(kind, it) }?.also { encode(it).fill(0) }
    } catch (expected: IllegalArgumentException) {
        null
    } catch (expected: CharacterCodingException) {
        null
    }

    private fun toJson(plaintext: MessagePlaintext): JsonObject = buildJsonObject {
        when (plaintext) {
            is MessagePlaintext.Text -> put(TEXT, plaintext.text)

            is MessagePlaintext.Preset -> {
                put(PRESET, plaintext.presetId)
                put(EMOJI, plaintext.emoji)
            }

            is MessagePlaintext.Voice -> put(DURATION, plaintext.durationMs)

            is MessagePlaintext.Photo -> {
                put(
                    PHOTO_REF,
                    buildJsonObject {
                        put(SHA256, plaintext.photoRef.sha256)
                        put(WIDTH, plaintext.photoRef.width)
                        put(HEIGHT, plaintext.photoRef.height)
                    },
                )
                put(CAPTION, plaintext.caption)
            }
        }
    }

    /** Throws [IllegalArgumentException] (caught by [decode]) for any shape or value that is not allowed. */
    private fun fromJson(kind: MessageKind, tree: JsonObject): MessagePlaintext = when (kind) {
        MessageKind.TEXT -> MessagePlaintext.Text(tree.withKeys(TEXT).string(TEXT))

        MessageKind.PRESET -> tree.withKeys(PRESET, EMOJI).let {
            MessagePlaintext.Preset(it.string(PRESET), it.nullableString(EMOJI))
        }

        MessageKind.VOICE -> MessagePlaintext.Voice(tree.withKeys(DURATION).long(DURATION))

        MessageKind.PHOTO -> tree.withKeys(PHOTO_REF, CAPTION).let {
            val ref = requireNotNull(it[PHOTO_REF] as? JsonObject) { "photoRef must be an object" }
                .withKeys(SHA256, WIDTH, HEIGHT)
            val photoRef = ChatPhotoRef(ref.string(SHA256), ref.int(WIDTH), ref.int(HEIGHT))
            MessagePlaintext.Photo(photoRef, it.nullableString(CAPTION))
        }
    }

    /** This object, which must have exactly [keys]. */
    private fun JsonObject.withKeys(vararg keys: String): JsonObject =
        also { require(it.keys == keys.toSet()) { "unexpected key set" } }

    private fun JsonObject.primitive(key: String): JsonPrimitive? = this[key] as? JsonPrimitive

    private fun JsonObject.string(key: String): String =
        requireNotNull(primitive(key)?.takeIf { it.isString }?.content) { "$key must be a string" }

    /** A string or JSON null (the key itself must be present). */
    private fun JsonObject.nullableString(key: String): String? = if (this[key] == JsonNull) null else string(key)

    private fun JsonObject.long(key: String): Long =
        requireNotNull(primitive(key)?.takeUnless { it.isString }?.longOrNull) { "$key must be an integer" }

    private fun JsonObject.int(key: String): Int =
        requireNotNull(primitive(key)?.takeUnless { it.isString }?.intOrNull) { "$key must be an integer" }
}
