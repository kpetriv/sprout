package dev.kpetriv.sprout.core.database

import dev.kpetriv.sprout.core.database.entity.DocumentJson
import dev.kpetriv.sprout.core.domainledger.Currency
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
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

    /**
     * `household_policy`: the household's fiat currency, whether parent money events need a parent signature, and
     * (policy v2, D-M7-10) the chat retention in days and the daily game cap in minor units. A v2 setting that is null
     * is left out, so the payload of a household that never set one is the v1 payload.
     */
    fun householdPolicy(
        fiatCurrency: Currency.Fiat,
        requireParentSignature: Boolean,
        chatRetentionDays: Int? = null,
        dailyGameCapMinor: Long? = null,
    ): JsonObject = buildJsonObject {
        put(FIAT_CURRENCY, fiatCurrency.isoCode)
        put(REQUIRE_PARENT_SIGNATURE, requireParentSignature)
        chatRetentionDays?.let { put(CHAT_RETENTION_DAYS, it) }
        dailyGameCapMinor?.let { put(DAILY_GAME_CAP_MINOR, it) }
    }

    class HouseholdPolicyPayload(
        val fiatCurrency: Currency.Fiat,
        val requireParentSignature: Boolean,
        val chatRetentionDays: Int? = null,
        val dailyGameCapMinor: Long? = null,
    )

    /**
     * The policy in [payload], or null when it does not decode: a missing or mistyped currency or flag, or a v2 setting
     * that is present (not null) but not an integer within [HouseholdPolicyLimits]. Unknown keys are ignored (the
     * rules refuse them).
     */
    fun decodeHouseholdPolicy(payload: JsonObject): HouseholdPolicyPayload? {
        val currency = payload.string(FIAT_CURRENCY)?.let { runCatching { Currency.Fiat(it) }.getOrNull() }
        val require = (payload[REQUIRE_PARENT_SIGNATURE] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
        val retention = payload.optionalInteger(CHAT_RETENTION_DAYS, RETENTION_RANGE)
        val cap = payload.optionalInteger(DAILY_GAME_CAP_MINOR, HouseholdPolicyLimits.DAILY_GAME_CAP_MINOR)
        val settings = retention is Setting.Ok && cap is Setting.Ok
        return if (currency != null && require != null && settings) {
            HouseholdPolicyPayload(
                currency,
                require,
                (retention as Setting.Ok).value?.toInt(),
                (cap as Setting.Ok).value,
            )
        } else {
            null
        }
    }

    /** An optional integer setting as decoded: [Ok] with its value (null when absent or JSON null), or [Invalid]. */
    private sealed interface Setting {
        class Ok(val value: Long?) : Setting

        data object Invalid : Setting
    }

    /** [key] as an optional integer within [range]: absent and JSON null are [Setting.Ok] with no value. */
    private fun JsonObject.optionalInteger(key: String, range: LongRange): Setting {
        val element = this[key]
        if (element == null || element is JsonNull) return Setting.Ok(null)
        val value = (element as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
        return if (value != null && value in range) Setting.Ok(value) else Setting.Invalid
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private const val FIAT_CURRENCY = "fiatCurrency"
    private const val REQUIRE_PARENT_SIGNATURE = "requireParentSignature"
    private const val CHAT_RETENTION_DAYS = "chatRetentionDays"
    private const val DAILY_GAME_CAP_MINOR = "dailyGameCapMinor"
    private val RETENTION_RANGE: LongRange =
        HouseholdPolicyLimits.CHAT_RETENTION_DAYS.let { it.first.toLong()..it.last.toLong() }
}
