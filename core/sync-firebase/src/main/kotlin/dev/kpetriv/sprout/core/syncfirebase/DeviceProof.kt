package dev.kpetriv.sprout.core.syncfirebase

import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.KidId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The bytes a parent device signs to prove a privileged request (D-M3-34, T7/T8), exactly as the backend rebuilds them
 * (`server/src/crypto.ts` `callablePayload`, `registerParentKeyPayload`, `canonicalJson`). The app signs them with the
 * device key (`DeviceSigner`, ECDSA P-256 / SHA-256, DER) and passes the signature with `callerDeviceId` to
 * [HouseholdFunctions].
 *
 * - A signed callable: `"sprout/<callable-in-kebab-case>/v1" || 0x00 || hid || 0x00 || canonicalJson(args)` (UTF-8),
 *   where `args` are the callable's arguments other than `callerDeviceId` and `signature` ([payload]).
 * - `registerParentKey`: `"sprout/register-parent-key/v1" || 0x00 || hid || 0x00 || SPKI DER of the parent key`
 *   ([registerParentKeyPayload]).
 */
object DeviceProof {
    /** The signed callables and the domain each signature is bound to (`SIGNED_CALLABLES`). */
    val SIGNED_CALLABLES: Map<String, String> = mapOf(
        HouseholdFunctions.CREATE_LINK_CODE to "sprout/create-link-code/v1",
        HouseholdFunctions.REVOKE_DEVICE to "sprout/revoke-device/v1",
        HouseholdFunctions.EXPORT_HOUSEHOLD to "sprout/export-household/v1",
        HouseholdFunctions.DELETE_HOUSEHOLD to "sprout/delete-household/v1",
    )

    /** Domain of the `registerParentKey` signature (`REGISTER_PARENT_KEY_DOMAIN`). */
    const val REGISTER_PARENT_KEY_DOMAIN: String = "sprout/register-parent-key/v1"

    /**
     * The bytes to sign for a call to [callable] (one of [SIGNED_CALLABLES]' keys, the callable's name such as
     * `createLinkCode`) in household [hid] with the signed arguments [args].
     *
     * @throws IllegalArgumentException for a callable that is not signed this way, or [args] holding a number that
     *   is not an integer within ±(2^53 − 1) (JavaScript would print it differently).
     */
    fun payload(callable: String, hid: HouseholdId, args: JsonObject): ByteArray {
        val domain = requireNotNull(SIGNED_CALLABLES[callable]) { "not a signed callable: $callable" }
        return domain.encodeToByteArray() + SEPARATOR + hid.value.encodeToByteArray() + SEPARATOR +
            canonicalJson(args).encodeToByteArray()
    }

    /** The bytes to sign for `registerParentKey` of [publicKey] (X.509 SPKI DER) in household [hid]. */
    fun registerParentKeyPayload(hid: HouseholdId, publicKey: ByteArray): ByteArray =
        REGISTER_PARENT_KEY_DOMAIN.encodeToByteArray() + SEPARATOR + hid.value.encodeToByteArray() + SEPARATOR +
            publicKey

    /** [payload] for `createLinkCode(kidId)`: the signed arguments are `{ kidId }`. */
    fun createLinkCodePayload(hid: HouseholdId, kidId: KidId): ByteArray =
        payload(HouseholdFunctions.CREATE_LINK_CODE, hid, buildJsonObject { put("kidId", kidId.value) })

    /** [payload] for `revokeDevice(deviceId)`: the signed arguments are `{ deviceId }` (the device to revoke). */
    fun revokeDevicePayload(hid: HouseholdId, deviceId: DeviceId): ByteArray =
        payload(HouseholdFunctions.REVOKE_DEVICE, hid, buildJsonObject { put("deviceId", deviceId.value) })

    /** [payload] for `exportHousehold()`: no signed arguments (`{}`). */
    fun exportHouseholdPayload(hid: HouseholdId): ByteArray =
        payload(HouseholdFunctions.EXPORT_HOUSEHOLD, hid, JsonObject(emptyMap()))

    /** [payload] for `deleteHousehold(confirmation = hid)`: the signed arguments are `{ confirmation: hid }`. */
    fun deleteHouseholdPayload(hid: HouseholdId): ByteArray =
        payload(HouseholdFunctions.DELETE_HOUSEHOLD, hid, buildJsonObject { put("confirmation", hid.value) })

    /**
     * The backend's `canonicalJson`: object keys sorted by UTF-16 code unit (JavaScript's default sort, which is also
     * Kotlin's [String.compareTo]), no whitespace, strings escaped as `JSON.stringify` does (`\"`, `\\`, `\b`, `\f`,
     * `\n`, `\r`, `\t`, other control characters and lone surrogates as lower-case `\u00xx`; everything else
     * literal), integers in plain decimal, `true`, `false`, `null`.
     */
    fun canonicalJson(element: JsonElement): String = StringBuilder().also { it.appendCanonical(element) }.toString()

    private fun StringBuilder.appendCanonical(element: JsonElement) {
        when (element) {
            is JsonObject -> {
                append('{')
                element.entries.sortedBy { it.key }.forEachIndexed { index, (key, value) ->
                    if (index > 0) append(',')
                    appendQuoted(key)
                    append(':')
                    appendCanonical(value)
                }
                append('}')
            }

            is JsonArray -> {
                append('[')
                element.forEachIndexed { index, value ->
                    if (index > 0) append(',')
                    appendCanonical(value)
                }
                append(']')
            }

            JsonNull -> append("null")

            is JsonPrimitive -> when {
                element.isString -> appendQuoted(element.content)
                element.booleanOrNull != null -> append(element.content)
                else -> append(safeInteger(element))
            }
        }
    }

    private fun safeInteger(primitive: JsonPrimitive): Long {
        val value = requireNotNull(primitive.longOrNull) { "only integers are signed" }
        require(value in -MAX_SAFE_INTEGER..MAX_SAFE_INTEGER) { "integer outside JavaScript's safe range" }
        return value
    }

    private fun StringBuilder.appendQuoted(text: String) {
        append('"')
        for ((index, char) in text.withIndex()) {
            when {
                char == '"' -> append("\\\"")

                char == '\\' -> append("\\\\")

                char == '\b' -> append("\\b")

                char == '\u000C' -> append("\\f")

                char == '\n' -> append("\\n")

                char == '\r' -> append("\\r")

                char == '\t' -> append("\\t")

                char < ' ' || isLoneSurrogate(text, index) ->
                    append("\\u").append(char.code.toString(HEX).padStart(ESCAPE_DIGITS, '0'))

                else -> append(char)
            }
        }
        append('"')
    }

    private fun isLoneSurrogate(text: String, index: Int): Boolean {
        val char = text[index]
        return when {
            char.isHighSurrogate() -> index + 1 >= text.length || !text[index + 1].isLowSurrogate()
            char.isLowSurrogate() -> index == 0 || !text[index - 1].isHighSurrogate()
            else -> false
        }
    }

    private const val SEPARATOR: Byte = 0
    private const val HEX = 16
    private const val ESCAPE_DIGITS = 4
    private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
}
