package dev.kpetriv.sprout.core.domainledger

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The deterministic byte form of a ledger event that is hashed and signed (ADR-0002).
 *
 * Canonicalisation works on the event's JSON **tree**, not on a Kotlin class: [encode] for a [LedgerEvent] is
 * `encode(LedgerJson.encodeToJsonObject(event))`. A stored or pulled document must be hashed and verified from
 * its own tree (`LedgerJson.json.parseToJsonElement(stored)`), never from a decoded-and-re-encoded event, so that
 * fields added by a newer [Envelope.schemaVersion] that this client does not understand (and drops on decoding)
 * are still covered by the hash and the signature.
 *
 * Rules, frozen for [Envelope.CURRENT_SCHEMA_VERSION] 1:
 * 1. Input is the event's JSON object as [LedgerJson] writes it (polymorphic `"type"` discriminator, defaults and
 *    nulls included), plus any fields a newer schema added.
 * 2. `envelope.hash` and `envelope.signature` are removed; everything else, including `schemaVersion` and a null
 *    `prevHash`, stays.
 * 3. Output is compact JSON (no whitespace) with object keys sorted explicitly by UTF-16 code unit at every
 *    depth, independent of declaration order. Arrays keep their order.
 * 4. Strings escape only `"`, `\` and control characters below U+0020 (as `\b \f \n \r \t` or `\u00XX`);
 *    everything else is written as-is. Numbers must be plain decimal integers; booleans and `null` are literal.
 * 5. The result is strict UTF-8: a string with an unpaired surrogate cannot be encoded and is rejected with
 *    [CharacterCodingException] rather than silently replaced (which would let two different events share a hash).
 */
object CanonicalEncoder {
    private val EXCLUDED_ENVELOPE_KEYS = setOf("hash", "signature")
    private val INTEGER = Regex("-?(0|[1-9][0-9]*)")
    private const val HEX_DIGITS = "0123456789abcdef"
    private const val NIBBLE_BITS = 4
    private const val NIBBLE_MASK = 0x0F
    private const val FIRST_PRINTABLE = 0x20

    /**
     * The canonical bytes of [event].
     *
     * @throws CharacterCodingException if a string field holds an unpaired surrogate.
     */
    fun encode(event: LedgerEvent): ByteArray = encode(LedgerJson.encodeToJsonObject(event))

    /**
     * The canonical bytes of a stored or pulled event document, including fields this client does not know.
     *
     * @throws IllegalArgumentException if [json] has no `envelope` object or holds a non-integer number.
     * @throws CharacterCodingException if a string or key holds an unpaired surrogate.
     */
    fun encode(json: JsonObject): ByteArray = encodeToString(json).encodeToByteArray(throwOnInvalidSequence = true)

    /** [encode] as a string, for tests and debugging. Not validated as UTF-8. */
    fun encodeToString(event: LedgerEvent): String = encodeToString(LedgerJson.encodeToJsonObject(event))

    /** [encode] of a JSON document as a string, for tests and debugging. Not validated as UTF-8. */
    fun encodeToString(json: JsonObject): String {
        val envelope = requireNotNull(json["envelope"] as? JsonObject) { "A ledger event needs an envelope object" }
        val stripped = JsonObject(json + ("envelope" to JsonObject(envelope - EXCLUDED_ENVELOPE_KEYS)))
        return buildString { write(stripped) }
    }

    private fun StringBuilder.write(element: JsonElement) {
        when (element) {
            is JsonObject -> writeObject(element)

            is JsonArray -> {
                append('[')
                element.forEachIndexed { index, item ->
                    if (index > 0) append(',')
                    write(item)
                }
                append(']')
            }

            JsonNull -> append("null")

            is JsonPrimitive -> writePrimitive(element)
        }
    }

    private fun StringBuilder.writePrimitive(element: JsonPrimitive) {
        val content = element.content
        when {
            element.isString -> writeString(content)

            content == "true" || content == "false" -> append(content)

            else -> {
                require(INTEGER.matches(content)) { "Canonical numbers are plain decimal integers" }
                append(content)
            }
        }
    }

    private fun StringBuilder.writeObject(element: JsonObject) {
        append('{')
        element.keys.sorted().forEachIndexed { index, key ->
            if (index > 0) append(',')
            writeString(key)
            append(':')
            write(element.getValue(key))
        }
        append('}')
    }

    private fun StringBuilder.writeString(value: String) {
        append('"')
        for (char in value) {
            when {
                char == '"' -> append("\\\"")

                char == '\\' -> append("\\\\")

                char == '\b' -> append("\\b")

                char == '\u000C' -> append("\\f")

                char == '\n' -> append("\\n")

                char == '\r' -> append("\\r")

                char == '\t' -> append("\\t")

                char.code < FIRST_PRINTABLE -> {
                    append("\\u00")
                    append(HEX_DIGITS[char.code shr NIBBLE_BITS])
                    append(HEX_DIGITS[char.code and NIBBLE_MASK])
                }

                else -> append(char)
            }
        }
        append('"')
    }
}

/**
 * The full storage and wire form of [LedgerEvent]s, including `hash` and `signature`. Not canonical: use
 * [CanonicalEncoder] for anything hashed or signed.
 *
 * **Forward compatibility.** Decoding ignores unknown keys, so a client can read events written by a newer
 * [Envelope.schemaVersion]; [Envelope.schemaVersion] says which fields the author's schema defines, and therefore
 * which ones this client may be missing. Because a decoded event drops what it does not understand, hash and
 * signature verification must run on the stored JSON tree (`json.parseToJsonElement(stored)` with
 * [CanonicalEncoder.encode] / [HashChain.hashOf] / [signedPayloadOf]), never on a re-encoded [LedgerEvent].
 */
object LedgerJson {
    /** Polymorphic `"type"` discriminator; defaults and explicit nulls are always written; unknown keys ignored. */
    val json: Json = Json {
        classDiscriminator = "type"
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = true
    }

    fun encode(event: LedgerEvent): String = json.encodeToString(LedgerEvent.serializer(), event)

    /** [event] as a JSON tree, exactly the fields this client's schema writes. */
    fun encodeToJsonObject(event: LedgerEvent): JsonObject =
        json.encodeToJsonElement(LedgerEvent.serializer(), event) as? JsonObject
            ?: error("Ledger events must serialise to JSON objects")

    /** Decodes one event; throws [IllegalArgumentException] (including `SerializationException`) on bad input. */
    fun decode(text: String): LedgerEvent = json.decodeFromString(LedgerEvent.serializer(), text)

    /**
     * Decodes one event, or returns null if [text] is not a valid event (malformed JSON, unknown event type, a field
     * failing its own validation). For row-by-row decoding where one bad row must not hide the others.
     */
    fun decodeOrNull(text: String): LedgerEvent? = try {
        decode(text)
    } catch (expected: IllegalArgumentException) {
        null
    }

    /** [decodeOrNull] for an already parsed tree. */
    fun decodeOrNull(element: JsonElement): LedgerEvent? = try {
        json.decodeFromJsonElement(LedgerEvent.serializer(), element)
    } catch (expected: IllegalArgumentException) {
        null
    }
}
