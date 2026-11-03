package dev.kpetriv.sprout.feature.settings

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.time.Instant

/**
 * The household export (D-M3-7): the `exportHousehold` JSON bundle and a `ledger.csv` derived from its events, in one
 * zip the parent saves through the system file picker.
 */
internal object HouseholdExport {
    const val JSON_NAME: String = "household.json"
    const val CSV_NAME: String = "ledger.csv"

    val CSV_COLUMNS: List<String> =
        listOf("eventId", "lamport", "createdAt", "type", "kidId", "accountId", "amount", "currency")

    private val prettyJson = Json { prettyPrint = true }

    /** The zip with [JSON_NAME] and [CSV_NAME]. */
    fun zip(bundle: JsonObject): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry(JSON_NAME))
            zip.write(prettyJson.encodeToString(JsonObject.serializer(), bundle).encodeToByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(CSV_NAME))
            zip.write(ledgerCsv(bundle).encodeToByteArray())
            zip.closeEntry()
        }
        return bytes.toByteArray()
    }

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
