package dev.kpetriv.sprout.core.database

import dev.kpetriv.sprout.core.database.entity.DocumentJson
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.UserId
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64

/**
 * Document payloads (`DocumentEnvelope.payload`) and their decoding. Domain documents are their model's
 * [DocumentJson] form; the household policy and parent keys have their own small shapes. Decoding never throws.
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

    /** `parent_key`: one registered parent authorisation key (ADR-0004 §1). */
    class ParentKeyPayload(
        val fingerprint: String,
        val publicKey: ByteArray,
        val uid: UserId,
        val deviceId: DeviceId,
        val validFromLamport: Long,
    )

    fun parentKey(key: ParentKeyPayload): JsonObject = buildJsonObject {
        put(FINGERPRINT, key.fingerprint)
        put(PUBLIC_KEY, Base64.Default.encode(key.publicKey))
        put(UID, key.uid.value)
        put(DEVICE_ID, key.deviceId.value)
        put(VALID_FROM_LAMPORT, key.validFromLamport)
    }

    fun decodeParentKey(payload: JsonObject): ParentKeyPayload? = runCatching {
        ParentKeyPayload(
            fingerprint = requireNotNull(payload.string(FINGERPRINT)),
            publicKey = Base64.Default.decode(requireNotNull(payload.string(PUBLIC_KEY))),
            uid = UserId(requireNotNull(payload.string(UID))),
            deviceId = DeviceId(requireNotNull(payload.string(DEVICE_ID))),
            validFromLamport = requireNotNull(
                (payload[VALID_FROM_LAMPORT] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull,
            ).also { require(it >= 0) },
        )
    }.getOrNull()

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private const val FIAT_CURRENCY = "fiatCurrency"
    private const val REQUIRE_PARENT_SIGNATURE = "requireParentSignature"
    private const val FINGERPRINT = "fingerprint"
    private const val PUBLIC_KEY = "publicKey"
    private const val UID = "uid"
    private const val DEVICE_ID = "deviceId"
    private const val VALID_FROM_LAMPORT = "validFromLamport"
}
