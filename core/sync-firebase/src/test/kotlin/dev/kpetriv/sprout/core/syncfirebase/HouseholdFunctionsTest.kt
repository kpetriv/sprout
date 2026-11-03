package dev.kpetriv.sprout.core.syncfirebase

import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.KidId
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HouseholdFunctionsTest {
    private class RecordingInvoker(private val respond: (String, Map<String, Any?>) -> Any?) : CallableInvoker {
        val calls = mutableListOf<Triple<String, Map<String, Any?>, Long>>()
        val withoutAuth = mutableListOf<String>()

        override suspend fun call(name: String, data: Map<String, Any?>, timeoutMs: Long): Any? {
            calls += Triple(name, data, timeoutMs)
            return respond(name, data)
        }

        override suspend fun callWithoutAuth(name: String, data: Map<String, Any?>, timeoutMs: Long): Any? {
            withoutAuth += name
            return call(name, data, timeoutMs)
        }
    }

    private val device = DeviceId("a".repeat(64))
    private val other = DeviceId("b".repeat(64))

    @Test
    fun `createHousehold sends base64 key material and decodes the household`() = runTest {
        val invoker = RecordingInvoker { _, _ -> mapOf("hid" to "h1", "deviceId" to device.value) }
        val key = DeviceKeyRegistration(byteArrayOf(1, 2, 3), listOf(byteArrayOf(4), byteArrayOf(5, 6)))

        val created = HouseholdFunctions(invoker).createHousehold("EUR", key).getOrThrow()

        assertEquals(CreatedHousehold(HouseholdId("h1"), device), created)
        val (name, data, timeout) = invoker.calls.single()
        assertEquals("createHousehold", name)
        assertEquals(
            mapOf(
                "fiatCurrency" to "EUR",
                "devicePublicKey" to "AQID",
                "attestationChain" to listOf("BA==", "BQY="),
            ),
            data,
        )
        assertEquals(30_000, timeout)
    }

    @Test
    fun `an unattested key sends no attestationChain field`() = runTest {
        val invoker = RecordingInvoker { _, _ ->
            mapOf(
                "deviceId" to device.value,
                "fingerprint" to "aaaa aaaa aaaa aaaa",
            )
        }

        HouseholdFunctions(invoker).registerParentDevice(DeviceKeyRegistration(byteArrayOf(1), null)).getOrThrow()

        assertFalse("attestationChain" in invoker.calls.single().second)
    }

    @Test
    fun `every callable uses its backend name and payload`() = runTest {
        val invoker = RecordingInvoker { name, _ ->
            when (name) {
                "beginDeviceRegistration" -> mapOf("challenge" to "AAEC")
                "approveDevice" -> mapOf("deviceId" to device.value, "status" to "ACTIVE")
                "createLinkCode" -> mapOf("code" to "ABCD2345", "expiresAt" to 1.7620006E12)
                "redeemLinkCode" -> mapOf("hid" to "h1", "kidId" to "kid-a", "deviceId" to device.value)
                "revokeDevice" -> mapOf("deviceId" to other.value, "revokedAtLamport" to 13)
                "exportHousehold" -> mapOf("household" to mapOf("id" to "h1"), "events" to listOf<Any>())
                "deleteHousehold" -> mapOf("deleted" to true)
                "registerParentKey" -> mapOf("fingerprint" to "c".repeat(64), "validFromLamport" to 41.0)
                else -> error("unexpected")
            }
        }
        val functions = HouseholdFunctions(invoker)
        val key = DeviceKeyRegistration(byteArrayOf(9), null)

        assertContentEquals(byteArrayOf(0, 1, 2), functions.beginDeviceRegistration().getOrThrow())
        functions.approveDevice(device, other, byteArrayOf(7, 7)).getOrThrow()
        val code = functions.createLinkCode(KidId("kid-a"), device, byteArrayOf(1)).getOrThrow()
        val redeemed = functions.redeemLinkCode("abcd-2345", key).getOrThrow()
        val revoked = functions.revokeDevice(other, device, byteArrayOf(2)).getOrThrow()
        val export = functions.exportHousehold(device, byteArrayOf(3)).getOrThrow()
        functions.deleteHousehold(HouseholdId("h1"), device, byteArrayOf(4)).getOrThrow()
        val parentKey = functions.registerParentKey(byteArrayOf(5), listOf(byteArrayOf(6)), device, byteArrayOf(8))
            .getOrThrow()

        assertEquals(1_762_000_600_000, code.expiresAt)
        assertFalse("ABCD2345" in code.toString(), "a link code never shows in toString")
        assertEquals(RedeemedLink(HouseholdId("h1"), KidId("kid-a"), device), redeemed)
        assertEquals(13, revoked.revokedAtLamport)
        assertEquals(0, export.getValue("events").jsonArray.size)
        assertEquals(ParentKeyRegistration("c".repeat(64), 41), parentKey)
        assertEquals(
            listOf(
                "beginDeviceRegistration" to emptyMap(),
                "approveDevice" to
                    mapOf("deviceId" to device.value, "approverDeviceId" to other.value, "signature" to "Bwc="),
                "createLinkCode" to mapOf("kidId" to "kid-a", "callerDeviceId" to device.value, "signature" to "AQ=="),
                "redeemLinkCode" to mapOf("code" to "abcd-2345", "devicePublicKey" to "CQ=="),
                "revokeDevice" to
                    mapOf("deviceId" to other.value, "callerDeviceId" to device.value, "signature" to "Ag=="),
                "exportHousehold" to mapOf("callerDeviceId" to device.value, "signature" to "Aw=="),
                "deleteHousehold" to
                    mapOf("confirmation" to "h1", "callerDeviceId" to device.value, "signature" to "BA=="),
                "registerParentKey" to mapOf(
                    "publicKey" to "BQ==",
                    "attestationChain" to listOf("Bg=="),
                    "deviceId" to device.value,
                    "signature" to "CA==",
                ),
            ),
            invoker.calls.map { it.first to it.second },
        )
        assertEquals(300_000, invoker.calls.first { it.first == "exportHousehold" }.third)
        assertEquals(300_000, invoker.calls.first { it.first == "deleteHousehold" }.third)
    }

    @Test
    fun `callable errors come back typed and a malformed response is reported as such`() = runTest {
        val failing = HouseholdFunctions(
            RecordingInvoker { _, _ -> throw FunctionsException(FunctionsError.ResourceExhausted) },
        )
        assertEquals(
            FunctionsError.ResourceExhausted,
            (failing.createLinkCode(KidId("kid-a"), device, byteArrayOf(1)).exceptionOrNull() as FunctionsException)
                .error,
        )

        val malformed = HouseholdFunctions(RecordingInvoker { _, _ -> mapOf("hid" to "bad id!", "deviceId" to "x") })
        val error = malformed.createHousehold("EUR", DeviceKeyRegistration(byteArrayOf(1), null)).exceptionOrNull()
        assertEquals(FunctionsError.MalformedResponse, (error as FunctionsException).error)

        val missing = HouseholdFunctions(RecordingInvoker { _, _ -> null })
        assertTrue(missing.revokeDevice(device, other, byteArrayOf(1)).isFailure)
    }

    @Test
    fun `error codes map from the gRPC names`() {
        assertEquals(FunctionsError.Unauthenticated, FunctionsError.fromCodeName("UNAUTHENTICATED"))
        assertEquals(FunctionsError.PermissionDenied, FunctionsError.fromCodeName("PERMISSION_DENIED"))
        assertEquals(FunctionsError.NotFound, FunctionsError.fromCodeName("NOT_FOUND"))
        assertEquals(FunctionsError.AlreadyExists, FunctionsError.fromCodeName("ALREADY_EXISTS"))
        assertEquals(FunctionsError.InvalidArgument, FunctionsError.fromCodeName("INVALID_ARGUMENT"))
        assertEquals(FunctionsError.ResourceExhausted, FunctionsError.fromCodeName("RESOURCE_EXHAUSTED"))
        assertEquals(FunctionsError.FailedPrecondition, FunctionsError.fromCodeName("FAILED_PRECONDITION"))
        assertEquals(FunctionsError.Unavailable, FunctionsError.fromCodeName("DEADLINE_EXCEEDED"))
        assertEquals(FunctionsError.Unknown("internal"), FunctionsError.fromCodeName("INTERNAL"))
        assertEquals("internal", FunctionsException(FunctionsError.Unknown("internal")).message)
    }

    @Test
    fun `deviceStatus goes without an ID token and decodes the status`() = runTest {
        var answer: Any? = mapOf("status" to "REVOKED", "householdGone" to false)
        val invoker = RecordingInvoker { _, _ -> answer }
        val functions = HouseholdFunctions(invoker)

        val revoked = functions.deviceStatus(HouseholdId("h1"), device, byteArrayOf(7, 7)).getOrThrow()
        assertEquals(DeviceStatusAnswer(DeviceStatusValue.REVOKED, householdGone = false), revoked)
        assertEquals(listOf("deviceStatus"), invoker.withoutAuth)
        assertEquals(
            mapOf("hid" to "h1", "deviceId" to device.value, "signature" to "Bwc="),
            invoker.calls.single().second,
        )

        answer = mapOf("status" to "UNKNOWN", "householdGone" to true)
        assertEquals(
            DeviceStatusAnswer(DeviceStatusValue.UNKNOWN, householdGone = true),
            functions.deviceStatus(HouseholdId("h1"), device, byteArrayOf(1)).getOrThrow(),
        )

        answer = mapOf("status" to "SOMETHING_ELSE", "householdGone" to false)
        val malformed = functions.deviceStatus(HouseholdId("h1"), device, byteArrayOf(1)).exceptionOrNull()
        assertEquals(FunctionsError.MalformedResponse, (malformed as FunctionsException).error)
        assertEquals(3, invoker.withoutAuth.size, "every other callable goes through call() with the ID token")
    }

    @Test
    fun `a deviceStatus request never prints its ids or signature`() {
        val request = DeviceStatusRequest(HouseholdId("h1"), device, "c2lnbmF0dXJl")
        assertEquals("DeviceStatusRequest(<redacted>)", request.toString())
        assertFalse("h1" in request.toString())
    }

    @Test
    fun `the token-less transport speaks the callable protocol`() {
        assertEquals(
            "http://10.0.2.2:8787/deviceStatus",
            CallableHttp.url(FunctionsEndpoint.CustomDomain("http://10.0.2.2:8787"), "demo-sprout", "deviceStatus"),
        )
        assertEquals(
            "https://europe-west1-p1.cloudfunctions.net/deviceStatus",
            CallableHttp.url(FunctionsEndpoint.Region("europe-west1"), "p1", "deviceStatus"),
        )
        assertEquals(
            mapOf("status" to "ACTIVE", "householdGone" to false),
            CallableHttp.parse("""{"result":{"status":"ACTIVE","householdGone":false}}"""),
        )
        val refusal = """{"error":{"status":"PERMISSION_DENIED","message":"Device signature does not verify"}}"""
        val denied = runCatching { CallableHttp.parse(refusal) }.exceptionOrNull() as FunctionsException
        assertEquals(FunctionsError.PermissionDenied, denied.error)
        assertEquals("permission-denied", denied.message, "the server's message is not kept")
        for (body in listOf("not json", "[]", "{}", """{"error":{}}""")) {
            val failure = runCatching { CallableHttp.parse(body) }.exceptionOrNull() as FunctionsException
            assertEquals(FunctionsError.MalformedResponse, failure.error, body)
        }
    }
}
