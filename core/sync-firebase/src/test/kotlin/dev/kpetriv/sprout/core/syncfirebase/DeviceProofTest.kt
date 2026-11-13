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
import kotlin.time.Instant

/**
 * Golden bytes for the device-proof payloads. The expected values were produced by the backend's own functions
 * (`server/src/crypto.ts` `callablePayload`, `registerParentKeyPayload`, `deviceStatusPayload`, `idTokenHash` and
 * `canonicalJson`, run with Node 24 on the same inputs), so a change on either side that alters the signed bytes fails
 * here. `server/test` has no byte fixtures of its own; it signs whatever `callablePayload` returns.
 */
class DeviceProofTest {
    private val hid = HouseholdId("home-1")

    /** Stands for sha256(raw ID token): 32 bytes of 0x11. */
    private val tokenHash = ByteArray(32) { 0x11 }
    private val tokenHex = "11".repeat(32)
    private val deviceHex = "62".repeat(64)

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    @Test
    fun `the ID token hash is SHA-256 of the raw token's UTF-8 bytes`() {
        assertEquals(
            "28b378c640e31b0f71a347b0f77b4c0f6677edf604e6c5a3ebbc9db34d2f5b79",
            DeviceProof.idTokenHash("eyJhbGciOiJub25lIn0.eyJzdWIiOiJ1In0.").hex(),
        )
    }

    @Test
    fun `createLinkCode signs the domain, the household, the token hash and the kid`() {
        assertEquals(
            "7370726f75742f6372656174652d6c696e6b2d636f64652f763200686f6d652d3100" + tokenHex +
                "007b226b69644964223a226b69642d61227d",
            DeviceProof.createLinkCodePayload(hid, tokenHash, KidId("kid-a")).hex(),
        )
    }

    @Test
    fun `revokeDevice and approveDevice sign the target device`() {
        assertEquals(
            "7370726f75742f7265766f6b652d6465766963652f763200686f6d652d3100" + tokenHex +
                "007b226465766963654964223a22" + deviceHex + "227d",
            DeviceProof.revokeDevicePayload(hid, tokenHash, DeviceId("b".repeat(64))).hex(),
        )
        assertEquals(
            "7370726f75742f617070726f76652d6465766963652f763200686f6d652d3100" + tokenHex +
                "007b226465766963654964223a22" + deviceHex + "227d",
            DeviceProof.approveDevicePayload(hid, tokenHash, DeviceId("b".repeat(64))).hex(),
        )
    }

    @Test
    fun `exportHousehold signs empty arguments and deleteHousehold the confirmation`() {
        assertEquals(
            "7370726f75742f6578706f72742d686f757365686f6c642f763200686f6d652d3100" + tokenHex + "007b7d",
            DeviceProof.exportHouseholdPayload(hid, tokenHash).hex(),
        )
        assertEquals(
            "7370726f75742f64656c6574652d686f757365686f6c642f763200686f6d652d3100" + tokenHex +
                "007b22636f6e6669726d6174696f6e223a22686f6d652d31227d",
            DeviceProof.deleteHouseholdPayload(hid, tokenHash).hex(),
        )
    }

    @Test
    fun `pruneMessages signs the cut and whether it is a purge`() {
        assertEquals(
            "7370726f75742f7072756e652d6d657373616765732f763200686f6d652d3100" + tokenHex +
                "007b226265666f7265223a313739333630363430303030302c227075726765223a66616c73657d",
            DeviceProof.pruneMessagesPayload(hid, tokenHash, Instant.fromEpochMilliseconds(1_793_606_400_000), false)
                .hex(),
        )
        assertEquals(
            "7370726f75742f7072756e652d6d657373616765732f763200686f6d652d3100" + tokenHex +
                "007b226265666f7265223a352c227075726765223a747275657d",
            DeviceProof.pruneMessagesPayload(hid, tokenHash, Instant.fromEpochMilliseconds(5), true).hex(),
        )
    }

    @Test
    fun `canonical JSON leaves base64 and KDF descriptor characters unescaped (ADR-0013 vector)`() {
        val args = buildJsonObject {
            put("verifier", "//////////////////////////////////////////8=")
            put("salt", "AAAAAAAAAAAAAAAAAAAAAA==")
            put("kdf", "argon2id\$v=19\$m=65536,t=3,p=1")
        }
        assertEquals(
            """{"kdf":"argon2id${'$'}v=19${'$'}m=65536,t=3,p=1","salt":"AAAAAAAAAAAAAAAAAAAAAA==",""" +
                """"verifier":"//////////////////////////////////////////8="}""",
            DeviceProof.canonicalJson(args),
        )
        val setArgs = buildJsonObject {
            put("verifier", "//////////////////////////////////////////8=")
            put("salt", "AAAAAAAAAAAAAAAAAAAAAA==")
            put("newPin", false)
            put("kdf", "pbkdf2-sha256\$i=600000")
        }
        assertEquals(
            """{"kdf":"pbkdf2-sha256${'$'}i=600000","newPin":false,"salt":"AAAAAAAAAAAAAAAAAAAAAA==",""" +
                """"verifier":"//////////////////////////////////////////8="}""",
            DeviceProof.canonicalJson(setArgs),
        )
        assertEquals("{}", DeviceProof.canonicalJson(JsonObject(emptyMap())))
        assertEquals("""{"method":"WAIT"}""", DeviceProof.canonicalJson(buildJsonObject { put("method", "WAIT") }))
        val pin = buildJsonObject {
            put("verifier", "ab+/=")
            put("method", "PIN")
        }
        assertEquals("""{"method":"PIN","verifier":"ab+/="}""", DeviceProof.canonicalJson(pin))
    }

    @Test
    fun `the recovery callables sign the check value, nothing, and the method with its verifier (ADR-0013)`() {
        val salt = ByteArray(16) { 1 }
        val verifier = ByteArray(32) { 2 }
        assertEquals(
            "7370726f75742f7365742d7265636f766572792d70696e2f763200686f6d652d3100" + tokenHex +
                "007b226b6466223a2270626b6466322d73686132353624693d363030303030222c226e657750696e223a747275652c22" +
                "73616c74223a22415145424151454241514542415145424151454241513d3d222c227665726966696572223a22416749" +
                "434167494341674943416749434167494341674943416749434167494341674943416749434167493d227d",
            DeviceProof.setRecoveryPinPayload(hid, tokenHash, salt, "pbkdf2-sha256\$i=600000", verifier, newPin = true)
                .hex(),
        )
        assertEquals(
            "7370726f75742f7265636f766572792d7374617475732f763200686f6d652d3100" + tokenHex + "007b7d",
            DeviceProof.recoveryStatusPayload(hid, tokenHash).hex(),
        )
        assertEquals(
            "7370726f75742f7265636f7665722d706172656e742d6465766963652f763200686f6d652d3100" + tokenHex +
                "007b226d6574686f64223a2250494e222c227665726966696572223a2241674943416749434167494341674943416749" +
                "4341674943416749434167494341674943416749434167493d227d",
            DeviceProof.recoverParentDevicePayload(hid, tokenHash, verifier).hex(),
        )
        assertEquals(
            "7370726f75742f7265636f7665722d706172656e742d6465766963652f763200686f6d652d3100" + tokenHex +
                "007b226d6574686f64223a2257414954227d",
            DeviceProof.recoverParentDevicePayload(hid, tokenHash, verifier = null).hex(),
        )
    }

    @Test
    fun `registerEncryptionKey signs the raw 32-byte X25519 key`() {
        assertEquals(
            "7370726f75742f72656769737465722d656e6372797074696f6e2d6b65792f763200686f6d652d3100" + tokenHex + "00" +
                "07".repeat(32),
            DeviceProof.registerEncryptionKeyPayload(hid, tokenHash, ByteArray(32) { 7 }).hex(),
        )
    }

    @Test
    fun `registerParentKey signs the raw SPKI bytes`() {
        assertEquals(
            "7370726f75742f72656769737465722d706172656e742d6b65792f763200686f6d652d3100" + tokenHex + "00305901ff",
            DeviceProof.registerParentKeyPayload(hid, tokenHash, byteArrayOf(0x30, 0x59, 0x01, 0xff.toByte())).hex(),
        )
    }

    @Test
    fun `deviceStatus signs the household and the device, without a token`() {
        assertEquals(
            "7370726f75742f6465766963652d7374617475732f763100686f6d652d3100$deviceHex",
            DeviceProof.deviceStatusPayload(hid, DeviceId("b".repeat(64))).hex(),
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
    fun `only the signed callables, a 32-byte token hash and integers within the safe range are accepted`() {
        val empty = JsonObject(emptyMap())
        assertFailsWith<IllegalArgumentException> { DeviceProof.payload("redeemLinkCode", hid, tokenHash, empty) }
        assertFailsWith<IllegalArgumentException> {
            DeviceProof.payload(HouseholdFunctions.EXPORT_HOUSEHOLD, hid, ByteArray(31), empty)
        }
        assertFailsWith<IllegalArgumentException> {
            DeviceProof.payload(HouseholdFunctions.EXPORT_HOUSEHOLD, hid, tokenHash, buildJsonObject { put("n", 1.5) })
        }
        assertFailsWith<IllegalArgumentException> {
            DeviceProof.payload(
                HouseholdFunctions.EXPORT_HOUSEHOLD,
                hid,
                tokenHash,
                buildJsonObject { put("n", 1L shl 53) },
            )
        }
    }
}
