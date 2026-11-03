package dev.kpetriv.sprout.core.database

import dev.kpetriv.sprout.core.database.entity.DocumentJson
import dev.kpetriv.sprout.core.domainledger.Currency
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Document payloads (`DocumentEnvelope.payload`) and their decoding. Domain documents are their model's
 * [DocumentJson] form; the household policy has its own small shape. Decoding never throws.
 */
internal object DocumentPayloads {
    fun <T> encode(serializer: KSerializer<T>, value: T): JsonObject =
        DocumentJson.json.encodeToJsonElement(serializer, value) as? JsonObject
            ?: error("document payloads are JSON objects")

    /** [payload] as a [T], or null when it does not decode (malformed, missing field, an id failing validation). */
    fun <T> decodeOrNull(serializer: KSerializer<T>, payload: JsonObject): T? = try {
        DocumentJson.json.decodeFromJsonElement(serializer, payload)
    } catch (expected: IllegalArgumentException) {
        null
    }

    /** `household_policy`: the household's fiat currency and whether parent money events need a parent signature. */
    fun householdPolicy(fiatCurrency: Currency.Fiat, requireParentSignature: Boolean): JsonObject = buildJsonObject {
        put(FIAT_CURRENCY, fiatCurrency.isoCode)
        put(REQUIRE_PARENT_SIGNATURE, requireParentSignature)
    }

    class HouseholdPolicyPayload(val fiatCurrency: Currency.Fiat, val requireParentSignature: Boolean)

    fun decodeHouseholdPolicy(payload: JsonObject): HouseholdPolicyPayload? {
        val currency = payload.string(FIAT_CURRENCY)?.let { runCatching { Currency.Fiat(it) }.getOrNull() }
        val require = (payload[REQUIRE_PARENT_SIGNATURE] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
        return if (currency != null && require != null) HouseholdPolicyPayload(currency, require) else null
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private const val FIAT_CURRENCY = "fiatCurrency"
    private const val REQUIRE_PARENT_SIGNATURE = "requireParentSignature"
}
