package dev.kpetriv.sprout.core.syncfirebase

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Conversion between JSON trees and the plain values the Firebase SDKs read and write (`Map<String, Any?>`, `List`,
 * `String`, `Long`, `Double`, `Boolean`, null). Integers stay `Long` both ways, so a ledger event or document
 * round-trips through Firestore unchanged and its hash and signature still verify.
 */
internal object PlainValues {
    fun toPlain(element: JsonElement): Any? = when (element) {
        JsonNull -> null
        is JsonPrimitive -> primitive(element)
        is JsonArray -> element.map(::toPlain)
        is JsonObject -> toPlainMap(element)
    }

    fun toPlainMap(json: JsonObject): Map<String, Any?> = json.mapValues { (_, value) -> toPlain(value) }

    /**
     * The JSON form of a plain value. [ServerTime] becomes its ISO-8601 instant; values JSON cannot hold (SDK types
     * such as references or blobs) become null. With [wholeDoublesAsLongs], a `Double` without a fraction becomes a
     * `Long` (callable responses decode JSON numbers loosely).
     */
    fun toJson(value: Any?, wholeDoublesAsLongs: Boolean = false): JsonElement = when (value) {
        null -> JsonNull

        is String -> JsonPrimitive(value)

        is Boolean -> JsonPrimitive(value)

        is Long -> JsonPrimitive(value)

        is Int -> JsonPrimitive(value.toLong())

        is Short -> JsonPrimitive(value.toLong())

        is Byte -> JsonPrimitive(value.toLong())

        is Double -> number(value, wholeDoublesAsLongs)

        is Float -> number(value.toDouble(), wholeDoublesAsLongs)

        is ServerTime -> JsonPrimitive(value.toIsoString())

        is Map<*, *> -> JsonObject(
            value.entries.filter { it.key is String }
                .associate { (key, item) -> key as String to toJson(item, wholeDoublesAsLongs) },
        )

        is List<*> -> JsonArray(value.map { toJson(it, wholeDoublesAsLongs) })

        else -> JsonNull
    }

    fun toJsonObject(map: Map<String, Any?>, wholeDoublesAsLongs: Boolean = false): JsonObject =
        JsonObject(map.mapValues { (_, value) -> toJson(value, wholeDoublesAsLongs) })

    private fun primitive(element: JsonPrimitive): Any? = when {
        element.isString -> element.content
        element.booleanOrNull != null -> element.booleanOrNull
        element.longOrNull != null -> element.longOrNull
        else -> element.doubleOrNull
    }

    private fun number(value: Double, wholeAsLong: Boolean): JsonPrimitive =
        if (wholeAsLong && isWholeLong(value)) JsonPrimitive(value.toLong()) else JsonPrimitive(value)

    private fun isWholeLong(value: Double): Boolean = value % 1.0 == 0.0 && value in LONG_RANGE

    private val LONG_RANGE = Long.MIN_VALUE.toDouble()..Long.MAX_VALUE.toDouble()
}
