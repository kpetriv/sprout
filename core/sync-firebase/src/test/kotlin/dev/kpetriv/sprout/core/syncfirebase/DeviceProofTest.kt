package dev.kpetriv.sprout.core.syncfirebase

import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.KidId
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Golden bytes for the device-proof payloads. The expected values were produced by the backend's own functions
 * (`server/src/crypto.ts` `callablePayload`, `registerParentKeyPayload` and `canonicalJson`, run with Node 24 on the
 * same inputs), so a change on either side that alters the signed bytes fails here. `server/test` has no byte
 * fixtures of its own; it signs whatever `callablePayload` returns.
 */
class DeviceProofTest {
    private val hid = HouseholdId("home-1")

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    @Test
    fun `createLinkCode signs the domain, the household and the kid`() {
        assertEquals(
            "7370726f75742f6372656174652d6c696e6b2d636f64652f763100686f6d652d31007b226b69644964223a226b69642d61227d",
            DeviceProof.createLinkCodePayload(hid, KidId("kid-a")).hex(),
        )
    }

    @Test
    fun `revokeDevice signs the device to revoke`() {
        assertEquals(
            "7370726f75742f7265766f6b652d6465766963652f763100686f6d652d31007b226465766963654964223a22" +
                "62".repeat(64) + "227d",
            DeviceProof.revokeDevicePayload(hid, DeviceId("b".repeat(64))).hex(),
        )
    }

    @Test
    fun `exportHousehold signs empty arguments and deleteHousehold the confirmation`() {
        assertEquals(
            "7370726f75742f6578706f72742d686f757365686f6c642f763100686f6d652d31007b7d",
            DeviceProof.exportHouseholdPayload(hid).hex(),
        )
        assertEquals(
            "7370726f75742f64656c6574652d686f757365686f6c642f763100686f6d652d31007b22636f6e6669726d6174696f6e223a" +
                "22686f6d652d31227d",
            DeviceProof.deleteHouseholdPayload(hid).hex(),
        )
    }

    @Test
    fun `registerParentKey signs the raw SPKI bytes`() {
        assertEquals(
            "7370726f75742f72656769737465722d706172656e742d6b65792f763100686f6d652d3100305901ff",
            DeviceProof.registerParentKeyPayload(hid, byteArrayOf(0x30, 0x59, 0x01, 0xff.toByte())).hex(),
        )
    }

    @Test
    fun `canonical JSON sorts keys by UTF-16 code unit and escapes like JSON stringify`() {
        val value = buildJsonObject {
            put("z", 1)
            put("a", "x")
            put(
                "B",
                buildJsonArray {
                    add(JsonPrimitive(true))
                    add(JsonNull)
                    add(JsonPrimitive("q\"\\\n\u0001\u2028\u00e9\u007f"))
                },
            )
            put(
                "m",
                buildJsonObject {
                    put("y", 2)
                    put("x", "\u00e9\uD83D\uDE00")
                    put("\u00e9", -3)
                },
            )
        }

        assertEquals(
            "7b2242223a5b747275652c6e756c6c2c22715c225c5c5c6e5c7530303031e280a8c3a97f225d2c2261223a2278222c226d223a" +
                "7b2278223a22c3a9f09f9880222c2279223a322c22c3a9223a2d337d2c227a223a317d",
            DeviceProof.canonicalJson(value).encodeToByteArray().hex(),
        )
        // Lone surrogates are escaped (lower-case hex), as JavaScript's well-formed JSON.stringify does.
        assertEquals(
            "{\"s\":\"a\\ud800b\\udc00\"}",
            DeviceProof.canonicalJson(buildJsonObject { put("s", "a\uD800b\uDC00") }),
        )
    }

    @Test
    fun `only the signed callables and integers within the safe range are accepted`() {
        assertFailsWith<IllegalArgumentException> { DeviceProof.payload("approveDevice", hid, JsonObject(emptyMap())) }
        assertFailsWith<IllegalArgumentException> {
            DeviceProof.payload(HouseholdFunctions.EXPORT_HOUSEHOLD, hid, buildJsonObject { put("n", 1.5) })
        }
        assertFailsWith<IllegalArgumentException> {
            DeviceProof.payload(HouseholdFunctions.EXPORT_HOUSEHOLD, hid, buildJsonObject { put("n", 1L shl 53) })
        }
    }
}
