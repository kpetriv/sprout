package dev.kpetriv.sprout.core.syncfirebase

import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.sync.RecoveredParentDevice
import dev.kpetriv.sprout.core.sync.RecoveryDeviceStatus
import dev.kpetriv.sprout.core.sync.RecoveryMethod
import dev.kpetriv.sprout.core.sync.RecoveryRefusal
import dev.kpetriv.sprout.core.sync.RecoveryRefusedException
import dev.kpetriv.sprout.core.sync.StoredRecoveryPin
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Instant

/** The account-recovery callables (ADR-0013 §4): request shapes, answers and the typed refusals. */
class HouseholdFunctionsRecoveryTest {
    private class Invoker(private val respond: (String, Map<String, Any?>) -> Any?) : CallableInvoker {
        val calls = mutableListOf<Pair<String, Map<String, Any?>>>()

        override suspend fun call(name: String, data: Map<String, Any?>, timeoutMs: Long): Any? {
            calls += name to data
            return respond(name, data)
        }
    }

    private val device = DeviceId("a".repeat(64))
    private val salt = ByteArray(16) { 1 }
    private val verifier = ByteArray(32) { 2 }

    private fun refusing(code: FunctionsError, details: Map<String, Any?>) =
        HouseholdFunctions(Invoker { _, _ -> throw FunctionsException(code, FunctionsException.detailsOf(details)) })

    private fun refusal(result: Result<*>): RecoveryRefusal =
        assertIs<RecoveryRefusedException>(result.exceptionOrNull()).refusal

    @Test
    fun `setRecoveryPin sends the check value in base64 with the caller's proof`() = runTest {
        var answer: Any? = mapOf("set" to true, "attemptsLeft" to 8)
        val invoker = Invoker { _, _ -> answer }
        val left = HouseholdFunctions(invoker)
            .setRecoveryPin(salt, "argon2id\$v=19\$m=65536,t=3,p=1", verifier, newPin = false, device, byteArrayOf(9))
            .getOrThrow()
        assertEquals(StoredRecoveryPin(stored = true, attemptsLeft = 8), left, "a missing `stored` is true")
        answer = mapOf("set" to true, "stored" to false, "attemptsLeft" to 3)
        val kept = HouseholdFunctions(invoker)
            .setRecoveryPin(salt, "argon2id\$v=19\$m=65536,t=3,p=1", verifier, newPin = false, device, byteArrayOf(9))
            .getOrThrow()
        assertEquals(StoredRecoveryPin(stored = false, attemptsLeft = 3), kept)
        val (name, data) = invoker.calls.first()
        assertEquals("setRecoveryPin", name)
        assertEquals(
            mapOf(
                "salt" to "AQEBAQEBAQEBAQEBAQEBAQ==",
                "kdf" to "argon2id\$v=19\$m=65536,t=3,p=1",
                "verifier" to "AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI=",
                "newPin" to false,
                "callerDeviceId" to device.value,
                "signature" to "CQ==",
            ),
            data,
        )
        assertFalse("AgICAg" in SetRecoveryPinRequest("s", "k", "AgICAg", true, device, "sig").toString())
    }

    @Test
    fun `recoveryStatus decodes the salt, the attempts and the end of the wait`() = runTest {
        var answer: Any? = mapOf(
            "pin" to mapOf("salt" to "AQEBAQEBAQEBAQEBAQEBAQ==", "kdf" to "pbkdf2-sha256\$i=600000"),
            "attemptsLeft" to 7,
            "waitEndsAt" to 1_794_211_200_000.0,
        )
        val invoker = Invoker { _, _ -> answer }
        val functions = HouseholdFunctions(invoker)
        val status = functions.recoveryStatus(device, byteArrayOf(5)).getOrThrow()
        assertContentEquals(salt, status.pin?.salt)
        assertEquals("pbkdf2-sha256\$i=600000", status.pin?.kdf)
        assertEquals(7, status.attemptsLeft)
        assertEquals(Instant.fromEpochMilliseconds(1_794_211_200_000), status.waitEndsAt)
        assertEquals("recoveryStatus" to mapOf("deviceId" to device.value, "signature" to "BQ=="), invoker.calls.last())

        answer = mapOf("pin" to null, "attemptsLeft" to 0, "waitEndsAt" to null)
        val none = functions.recoveryStatus(device, byteArrayOf(5)).getOrThrow()
        assertNull(none.pin)
        assertNull(none.waitEndsAt, "no wait asked for yet")

        answer = mapOf("pin" to null, "attemptsLeft" to 11, "waitEndsAt" to 5)
        val malformed = functions.recoveryStatus(device, byteArrayOf(5)).exceptionOrNull()
        assertEquals(FunctionsError.MalformedResponse, assertIs<FunctionsException>(malformed).error)
    }

    @Test
    fun `recoverParentDevice sends the method, and the verifier only with the PIN`() = runTest {
        val invoker = Invoker { _, data ->
            mapOf(
                "deviceId" to device.value,
                "status" to "ACTIVE",
                "revokedDevices" to 1,
                "method" to data["method"],
                "attemptsLeft" to 9,
                "signInAgain" to true,
            )
        }
        val functions = HouseholdFunctions(invoker)
        assertEquals(
            RecoveredParentDevice(device, 1, RecoveryMethod.PIN, attemptsLeft = 9, signInAgain = true),
            functions.recoverParentDevice(verifier, device, byteArrayOf(1)).getOrThrow(),
        )
        assertEquals(
            mapOf(
                "deviceId" to device.value,
                "signature" to "AQ==",
                "method" to "PIN",
                "verifier" to "AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI=",
            ),
            invoker.calls.last().second,
        )
        val waited = functions.recoverParentDevice(null, device, byteArrayOf(1)).getOrThrow()
        assertEquals(RecoveryMethod.WAIT, waited.method)
        assertEquals(
            mapOf("deviceId" to device.value, "signature" to "AQ==", "method" to "WAIT"),
            invoker.calls.last().second,
        )
    }

    @Test
    fun `refusals with a reason come back typed`() = runTest {
        assertEquals(
            RecoveryRefusal.RecentSignIn,
            refusal(
                refusing(FunctionsError.FailedPrecondition, mapOf("reason" to "recent-sign-in"))
                    .recoverParentDevice(null, device, byteArrayOf(1)),
            ),
        )
        assertEquals(
            RecoveryRefusal.WrongPin(3),
            refusal(
                refusing(FunctionsError.PermissionDenied, mapOf("reason" to "wrong-pin", "attemptsLeft" to 3.0))
                    .recoverParentDevice(verifier, device, byteArrayOf(1)),
            ),
        )
        assertEquals(
            RecoveryRefusal.OtherParent,
            refusal(
                refusing(FunctionsError.FailedPrecondition, mapOf("reason" to "other-parent"))
                    .recoverParentDevice(verifier, device, byteArrayOf(1)),
            ),
        )
        assertEquals(
            RecoveryRefusal.PinUnavailable,
            refusal(
                refusing(FunctionsError.FailedPrecondition, mapOf("reason" to "pin-unavailable"))
                    .recoverParentDevice(verifier, device, byteArrayOf(1)),
            ),
        )
        assertEquals(
            RecoveryRefusal.Wait(Instant.fromEpochMilliseconds(1_794_211_200_000)),
            refusal(
                refusing(
                    FunctionsError.FailedPrecondition,
                    mapOf("reason" to "wait", "waitEndsAt" to 1_794_211_200_000L),
                )
                    .recoverParentDevice(null, device, byteArrayOf(1)),
            ),
        )
        assertEquals(
            RecoveryRefusal.NotPending(RecoveryDeviceStatus.ACTIVE),
            refusal(
                refusing(FunctionsError.FailedPrecondition, mapOf("reason" to "not-pending", "status" to "ACTIVE"))
                    .recoveryStatus(device, byteArrayOf(1)),
            ),
        )
        assertEquals(
            RecoveryRefusal.NotPending(RecoveryDeviceStatus.REVOKED),
            refusal(
                refusing(FunctionsError.FailedPrecondition, mapOf("reason" to "not-pending", "status" to "REVOKED"))
                    .recoveryStatus(device, byteArrayOf(1)),
            ),
        )
    }

    @Test
    fun `a refusal without a known reason, or with a broken parameter, stays a FunctionsException`() = runTest {
        val cases = listOf(
            FunctionsError.PermissionDenied to emptyMap(),
            FunctionsError.FailedPrecondition to mapOf("reason" to "something-new"),
            FunctionsError.PermissionDenied to mapOf("reason" to "wrong-pin"),
            FunctionsError.PermissionDenied to mapOf("reason" to "wrong-pin", "attemptsLeft" to 2.5),
            FunctionsError.FailedPrecondition to mapOf("reason" to "wait", "waitEndsAt" to "soon"),
            FunctionsError.Unavailable to mapOf("reason" to "recent-sign-in"),
        )
        for ((code, details) in cases) {
            val failure =
                refusing(code, details).recoverParentDevice(verifier, device, byteArrayOf(1)).exceptionOrNull()
            assertEquals(code, assertIs<FunctionsException>(failure).error, details.toString())
        }
    }

    @Test
    fun `error details keep only scalar fields and never reach the message`() {
        val details = FunctionsException.detailsOf(
            mapOf("reason" to "wrong-pin", "attemptsLeft" to 4, "nested" to mapOf("x" to 1), 5 to "x", "n" to null),
        )
        assertEquals(mapOf("reason" to "wrong-pin", "attemptsLeft" to 4), details)
        val exception = FunctionsException(FunctionsError.PermissionDenied, details)
        assertEquals("permission-denied", exception.message)
        assertEquals("wrong-pin", exception.reason)
        val parsed = runCatching {
            CallableHttp.parse(
                """{"error":{"status":"FAILED_PRECONDITION","message":"m",""" +
                    """"details":{"reason":"wait","waitEndsAt":7}}}""",
            )
        }.exceptionOrNull()
        assertEquals(mapOf("reason" to "wait", "waitEndsAt" to 7L), assertIs<FunctionsException>(parsed).details)
    }
}
