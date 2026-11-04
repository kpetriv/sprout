package dev.kpetriv.sprout.feature.settings

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.time.Instant

/**
 * The readable chat part of the export (M7 §3, D-M7-9): every thread, decoded on this phone, the media files that could
 * be had ([media], by their source file name) and those that could not ([missing]).
 */
internal class ChatExportContent(
    val threads: List<ChatExportThread>,
    val media: Map<String, ByteArray>,
    val missing: List<ChatMissingMedia>,
) {
    override fun toString(): String =
        "ChatExportContent(threads=${threads.size}, media=${media.size}, missing=${missing.size})"
}

/**
 * The household export (D-M3-7): the `exportHousehold` JSON bundle and a `ledger.csv` derived from its events, in one
 * zip the parent saves through the system file picker. Export v2 (M7 §3) adds the readable chat when given one:
 * `chat/<kidId>.json` per thread, `chat/media/<file>` per photo or voice note, and `chat/missing.json` listing the
 * media that could not be had. Ids from the database become file names only through [safeName].
 */
internal object HouseholdExport {
    const val JSON_NAME: String = "household.json"
    const val CSV_NAME: String = "ledger.csv"
    const val CHAT_DIR: String = "chat/"
    const val MEDIA_DIR: String = "media/"
    const val MISSING_NAME: String = "chat/missing.json"

    val CSV_COLUMNS: List<String> =
        listOf("eventId", "lamport", "createdAt", "type", "kidId", "accountId", "amount", "currency")

    private val prettyJson = Json { prettyPrint = true }

    /** The zip with [JSON_NAME] and [CSV_NAME], and the chat entries when [chat] is given. */
    fun zip(bundle: JsonObject, chat: ChatExportContent? = null): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            zip.entry(JSON_NAME, prettyJson.encodeToString(JsonObject.serializer(), bundle).encodeToByteArray())
            zip.entry(CSV_NAME, ledgerCsv(bundle).encodeToByteArray())
            if (chat != null) writeChat(zip, chat)
        }
        return bytes.toByteArray()
    }

    private fun writeChat(zip: ZipOutputStream, chat: ChatExportContent) {
        val names = mediaFileNames(chat.threads, chat.media.keys + chat.missing.map { it.fileName })
        val threadNames = UniqueNames()
        chat.threads.forEach { thread ->
            val json = threadJson(thread, names)
            zip.entry(CHAT_DIR + threadNames.claim(safeName(thread.kidId.value)) + ".json", json)
        }
        chat.media.forEach { (fileName, bytes) -> zip.entry(CHAT_DIR + MEDIA_DIR + names.getValue(fileName), bytes) }
        // Missing: what the reader reported, plus anything referenced that neither arrived nor was reported.
        val reported = chat.missing.associate { it.fileName to it.reason }
        val missing = names.keys.filter { it !in chat.media }.map { it to (reported[it] ?: NOT_AVAILABLE) }
        val missingJson = buildJsonObject {
            put(
                "missing",
                buildJsonArray {
                    missing.forEach { (fileName, reason) ->
                        add(
                            buildJsonObject {
                                put("file", MEDIA_DIR + names.getValue(fileName))
                                put("reason", reason)
                            },
                        )
                    }
                },
            )
        }
        zip.entry(MISSING_NAME, prettyJson.encodeToString(JsonObject.serializer(), missingJson).encodeToByteArray())
    }

    /**
     * Every distinct media file name of [threads] (in message order), then [others], each with its unique, sanitised
     * name in the zip: the base and the extension are sanitised separately ([safeName]).
     */
    fun mediaFileNames(threads: List<ChatExportThread>, others: Collection<String> = emptyList()): Map<String, String> {
        val names = UniqueNames()
        val result = LinkedHashMap<String, String>()
        (threads.flatMap { it.messages }.mapNotNull { it.media } + others).forEach { fileName ->
            if (fileName !in result) {
                val dot = fileName.lastIndexOf('.')
                val base = if (dot > 0) fileName.substring(0, dot) else fileName
                val extension = if (dot > 0) fileName.substring(dot + 1) else ""
                val safeExtension = extension.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }
                    .take(MAX_EXTENSION).ifEmpty { "bin" }
                result[fileName] = names.claim(safeName(base), ".$safeExtension")
            }
        }
        return result
    }

    private fun threadJson(thread: ChatExportThread, mediaNames: Map<String, String>): ByteArray {
        val json = buildJsonObject {
            put("kidId", thread.kidId.value)
            put(
                "messages",
                buildJsonArray {
                    thread.messages.forEach { message ->
                        add(
                            buildJsonObject {
                                put("sentAt", message.sentAt.toString())
                                put("author", message.author.name)
                                put("kind", message.kind.name.lowercase())
                                if (message.locked) put("locked", true)
                                message.text?.let { put("text", it) }
                                message.presetId?.let { put("presetId", it) }
                                message.emoji?.let { put("emoji", it) }
                                message.durationMs?.let { put("durationMs", it) }
                                message.media?.let { put("media", MEDIA_DIR + mediaNames.getValue(it)) }
                            },
                        )
                    }
                },
            )
        }
        return prettyJson.encodeToString(JsonObject.serializer(), json).encodeToByteArray()
    }

    /**
     * [raw] as a file name that cannot leave its directory: letters, digits, `-` and `_` only (anything else becomes
     * `_`), at most [MAX_NAME] characters, never empty.
     */
    fun safeName(raw: String): String =
        raw.map { if (it.isSafe()) it else '_' }.joinToString("").take(MAX_NAME).ifEmpty { "_" }

    private fun Char.isSafe(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' || this == '-' ||
        this == '_'

    /** Hands out names once: a repeat (two ids that sanitise alike) gets `-2`, `-3`, ... before [suffix]. */
    private class UniqueNames {
        private val used = mutableSetOf<String>()

        fun claim(base: String, suffix: String = ""): String {
            var candidate = base
            var n = 1
            while (!used.add(candidate + suffix)) {
                n += 1
                candidate = "$base-$n"
            }
            return candidate + suffix
        }
    }

    private fun ZipOutputStream.entry(name: String, content: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(content)
        closeEntry()
    }

    private const val MAX_NAME = 64
    private const val NOT_AVAILABLE = "NOT_AVAILABLE"
    private const val MAX_EXTENSION = 5

    /**
     * One row per event of `bundle.events` (stored with the envelope fields at the top level), in lamport order:
     * [CSV_COLUMNS]. `createdAt` (epoch ms) is written as an ISO-8601 instant; amounts stay in minor units (or
     * minutes). RFC 4180 quoting; a text cell that a spreadsheet would read as a formula is prefixed with `'`.
     */
    fun ledgerCsv(bundle: JsonObject): String {
        val events = (bundle["events"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        val rows = events.sortedWith(compareBy({ it.long("lamport") ?: Long.MAX_VALUE }, { it.text("eventId") }))
            .map { event ->
                listOf(
                    event.text("eventId"),
                    event.long("lamport")?.toString().orEmpty(),
                    event.long("createdAt")?.let { Instant.fromEpochMilliseconds(it).toString() }
                        ?: event.text("createdAt"),
                    event.text("type"),
                    event.text("kidId"),
                    event.text("accountId"),
                    event.long("amount")?.toString().orEmpty(),
                    event.text("currency"),
                )
            }
        return (listOf(CSV_COLUMNS) + rows).joinToString(separator = "\r\n", postfix = "\r\n") { row ->
            row.joinToString(",", transform = ::cell)
        }
    }

    private fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: (this[key] as? JsonPrimitive)?.content?.takeUnless { it == "null" }.orEmpty()

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.let { primitive ->
        primitive.longOrNull ?: primitive.content.toDoubleOrNull()?.takeIf { it % 1.0 == 0.0 }?.toLong()
    }

    private fun cell(value: String): String {
        val numeric = value.toLongOrNull() != null
        val safe = if (!numeric && value.firstOrNull() in FORMULA_STARTS) "'$value" else value
        return if (safe.any { it in QUOTED }) {
            "\"" + safe.replace("\"", "\"\"") + "\""
        } else {
            safe
        }
    }

    private val FORMULA_STARTS = setOf('=', '+', '-', '@', '\t')
    private val QUOTED = setOf(',', '"', '\n', '\r')

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
}
