package dev.kpetriv.sprout.core.sync

import dev.kpetriv.sprout.core.domainledger.DeviceStatus
import dev.kpetriv.sprout.core.domainledger.RegisteredParentKey
import dev.kpetriv.sprout.core.model.Role
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** The fake server's account recovery (ADR-0013 §1, §2) behaves as the callables are specified. */
class FakeBackendRecoveryTest {
    private val old = TestIdentity("old-phone", "parent-uid", Role.PARENT)
    private val fresh = TestIdentity("new-phone", "parent-uid", Role.PARENT, status = DeviceStatus.Pending)
    private val otherParent = TestIdentity("co-parent", "co-uid", Role.PARENT)
    private var now = FakeBackend.DEFAULT_NOW_EPOCH_MS
    private val backend = FakeBackend(HOUSEHOLD).apply {
        nowEpochMs = { now }
        registerDevice(old.registered)
    }
    private val oldCalls = FakeRecoveryCallables(backend, old.deviceId, old.uid)
    private val freshCalls = FakeRecoveryCallables(backend, fresh.deviceId, fresh.uid)
    private val salt = ByteArray(16) { 1 }
    private val verifier = ByteArray(32) { 2 }
    private val wrong = ByteArray(32) { 3 }

    private fun refusal(result: Result<*>): RecoveryRefusal =
        assertIs<RecoveryRefusedException>(result.exceptionOrNull()).refusal

    private fun permanent(result: Result<*>): String =
        assertIs<SyncError.Permanent>(assertIs<SyncException>(result.exceptionOrNull()).error).code

    private fun registerFresh() {
        now += HOUR
        backend.registerDevice(fresh.registered)
    }

    private suspend fun setPin(newPin: Boolean = true): Int =
        oldCalls.setRecoveryPin(salt, KDF, verifier, newPin).getOrThrow().attemptsLeft

    @Test
    fun `only an ACTIVE parent device sets a check value of the right shape`() = runTest {
        assertEquals(10, setPin())
        assertEquals(0, backend.recoveryPinOf(old.uid)?.failedAttempts)
        val shortSalt = oldCalls.setRecoveryPin(ByteArray(8), "pbkdf2-sha256\$i=1", verifier, newPin = true)
        assertEquals("invalid-argument", permanent(shortSalt))
        assertEquals("invalid-argument", permanent(oldCalls.setRecoveryPin(salt, "scrypt\$n=1", verifier, true)))
        registerFresh()
        assertEquals("permission-denied", permanent(freshCalls.setRecoveryPin(salt, KDF, verifier, newPin = true)))
    }

    @Test
    fun `a PENDING phone recovers with the PIN, every phone of the account is revoked, and it signs in again`() =
        runTest {
            setPin()
            backend.injectEvent(old.event("e1", lamport = 7))
            backend.addParentKey(RegisteredParentKey("fp", ByteArray(1), old.uid, old.deviceId, 1, null))
            registerFresh()

            val status = freshCalls.recoveryStatus().getOrThrow()
            assertEquals(KDF, status.pin?.kdf)
            assertEquals(salt.toList(), status.pin?.salt?.toList())
            assertEquals(10, status.attemptsLeft)
            assertNull(status.waitEndsAt, "no wait was asked for")

            val recovered = freshCalls.recoverParentDevice(RecoveryRequest.Pin(verifier)).getOrThrow()
            val expected = RecoveredParentDevice(fresh.deviceId, 1, RecoveryMethod.PIN, 10, signInAgain = true)
            assertEquals(expected, recovered)
            assertEquals(DeviceStatus.Active, backend.device(fresh.deviceId)?.status)
            assertEquals(DeviceStatus.Revoked(8), backend.device(old.deviceId)?.status)
            assertEquals(8L, backend.parentKeys().single().revokedAtLamport)
            assertNotNull(backend.recoveryPinOf(old.uid), "a PIN recovery keeps the check value")
            assertEquals(RecoveryMethod.PIN, backend.recoveries().single().method)
            // Every session of the account is refused, the recovering phone's too, until it signs in again.
            assertTrue(backend.isSignedOut(old.deviceId))
            assertTrue(backend.isSignedOut(fresh.deviceId))
            val freshTransport = FakeSyncTransport(backend, fresh.deviceId, fresh.uid, Role.PARENT)
            val refused = runCatching { freshTransport.pull(SyncCursor.START) }.exceptionOrNull()
            assertEquals(SyncError.Unauthenticated, assertIs<SyncException>(refused).error)
            backend.recordSignIn(fresh.deviceId)
            freshTransport.pull(SyncCursor.START)
            assertTrue(backend.isSignedOut(old.deviceId))
            // Now ACTIVE: not pending any more.
            assertEquals(
                RecoveryRefusal.NotPending(RecoveryDeviceStatus.ACTIVE),
                refusal(freshCalls.recoverParentDevice(RecoveryRequest.Pin(verifier))),
            )
        }

    @Test
    fun `a failed session revocation answers internal, and the retry finishes it and answers the same`() = runTest {
        setPin()
        registerFresh()
        backend.failNextSessionRevocation = true
        assertEquals("internal", permanent(freshCalls.recoverParentDevice(RecoveryRequest.Pin(verifier))))
        assertEquals(DeviceStatus.Active, backend.device(fresh.deviceId)?.status)
        assertFalse(backend.isSignedOut(old.deviceId), "the revocation did not run")
        val retried = freshCalls.recoverParentDevice(RecoveryRequest.Pin(verifier)).getOrThrow()
        assertEquals(RecoveredParentDevice(fresh.deviceId, 1, RecoveryMethod.PIN, 10, signInAgain = true), retried)
        assertTrue(backend.isSignedOut(old.deviceId))
        assertEquals(1, backend.recoveries().size)
    }

    @Test
    fun `wrong PINs add up across a restore and a re-registration of the same PIN, and close the PIN path`() = runTest {
        setPin()
        registerFresh()
        repeat(3) { freshCalls.recoverParentDevice(RecoveryRequest.Pin(wrong)) }
        val recovered = freshCalls.recoverParentDevice(RecoveryRequest.Pin(verifier)).getOrThrow()
        assertEquals(7, recovered.attemptsLeft, "a restore keeps the count")
        assertEquals(3, backend.recoveryPinOf(old.uid)?.failedAttempts)
        backend.recordSignIn(fresh.deviceId)
        val freshActive = FakeRecoveryCallables(backend, fresh.deviceId, fresh.uid)
        // The old phone that set the row is revoked now: this phone's registration replaces it, count kept.
        val same = freshActive.setRecoveryPin(salt, KDF, verifier, newPin = false).getOrThrow()
        assertEquals(StoredRecoveryPin(stored = true, attemptsLeft = 7), same)
        val changed = freshActive.setRecoveryPin(salt, KDF, verifier, newPin = true).getOrThrow()
        assertEquals(StoredRecoveryPin(stored = true, attemptsLeft = 10), changed)
    }

    @Test
    fun `ten wrong PINs close the PIN path until a new PIN is set`() = runTest {
        setPin()
        registerFresh()
        for (left in 9 downTo 0) {
            assertEquals(
                RecoveryRefusal.WrongPin(left),
                refusal(freshCalls.recoverParentDevice(RecoveryRequest.Pin(wrong))),
            )
        }
        assertEquals(
            RecoveryRefusal.PinUnavailable,
            refusal(freshCalls.recoverParentDevice(RecoveryRequest.Pin(verifier))),
        )
        val status = freshCalls.recoveryStatus().getOrThrow()
        assertNull(status.pin)
        assertEquals(0, status.attemptsLeft)
        assertEquals(DeviceStatus.Active, backend.device(old.deviceId)?.status)
        // The same PIN registered again (the 30-day refresh) keeps it closed.
        assertEquals(0, setPin(newPin = false))
        assertNull(freshCalls.recoveryStatus().getOrThrow().pin)
        // A new PIN set on a working phone opens it again.
        setPin(newPin = true)
        assertEquals(10, freshCalls.recoveryStatus().getOrThrow().attemptsLeft)
    }

    @Test
    fun `the wait starts when the phone asks, ends 7 days later, needs a fresh sign-in, and forgets the PIN`() =
        runTest {
            setPin()
            registerFresh()
            now += 30 * DAY
            assertNull(freshCalls.recoveryStatus().getOrThrow().waitEndsAt, "registering does not start the wait")
            val endsAt = Instant.fromEpochMilliseconds(now + FakeBackend.RECOVERY_WAIT_MS)
            backend.recordSignIn(fresh.deviceId)
            assertEquals(RecoveryRefusal.Wait(endsAt), refusal(freshCalls.recoverParentDevice(RecoveryRequest.Wait)))
            assertEquals(endsAt, freshCalls.recoveryStatus().getOrThrow().waitEndsAt)
            now += DAY
            backend.recordSignIn(fresh.deviceId)
            assertEquals(RecoveryRefusal.Wait(endsAt), refusal(freshCalls.recoverParentDevice(RecoveryRequest.Wait)))
            now = endsAt.toEpochMilliseconds()
            assertEquals(RecoveryRefusal.RecentSignIn, refusal(freshCalls.recoverParentDevice(RecoveryRequest.Wait)))
            backend.recordSignIn(fresh.deviceId)
            val recovered = freshCalls.recoverParentDevice(RecoveryRequest.Wait).getOrThrow()
            assertEquals(RecoveryMethod.WAIT, recovered.method)
            assertEquals(0, recovered.attemptsLeft)
            assertNull(backend.recoveryPinOf(old.uid), "a recovery by waiting deletes the check value")
            assertIs<DeviceStatus.Revoked>(backend.device(old.deviceId)?.status)
        }

    @Test
    fun `no check value means no PIN path, and a revoked phone is not pending`() = runTest {
        registerFresh()
        val status = freshCalls.recoveryStatus().getOrThrow()
        assertNull(status.pin)
        assertEquals(0, status.attemptsLeft)
        assertEquals(
            RecoveryRefusal.PinUnavailable,
            refusal(freshCalls.recoverParentDevice(RecoveryRequest.Pin(verifier))),
        )
        backend.revoke(fresh.deviceId, atLamport = 1)
        assertEquals(RecoveryRefusal.NotPending(RecoveryDeviceStatus.REVOKED), refusal(freshCalls.recoveryStatus()))
    }

    @Test
    fun `another account's ACTIVE parent device blocks recovery, and a stranger's call is refused`() = runTest {
        setPin()
        backend.registerDevice(otherParent.registered)
        registerFresh()
        assertEquals(
            RecoveryRefusal.OtherParent,
            refusal(freshCalls.recoverParentDevice(RecoveryRequest.Pin(verifier))),
        )
        val stranger = FakeRecoveryCallables(backend, fresh.deviceId, otherParent.uid)
        assertEquals("permission-denied", permanent(stranger.recoveryStatus()))
    }

    @Test
    fun `an unchanged PIN of a second working phone keeps the value the other phone set`() = runTest {
        val second = TestIdentity("second-phone", "parent-uid", Role.PARENT)
        backend.registerDevice(second.registered)
        val secondCalls = FakeRecoveryCallables(backend, second.deviceId, second.uid)
        setPin()
        val kept = secondCalls.setRecoveryPin(salt, KDF, wrong, newPin = false).getOrThrow()
        assertEquals(StoredRecoveryPin(stored = false, attemptsLeft = 10), kept)
        assertEquals(old.deviceId, backend.recoveryPinOf(old.uid)?.setByDeviceId)
        // A PIN changed on the second phone replaces it.
        val changed = secondCalls.setRecoveryPin(salt, KDF, wrong, newPin = true).getOrThrow()
        assertEquals(StoredRecoveryPin(stored = true, attemptsLeft = 10), changed)
        assertEquals(second.deviceId, backend.recoveryPinOf(old.uid)?.setByDeviceId)
    }

    private companion object {
        const val HOUR = 3_600_000L
        const val DAY = 24 * HOUR
        const val KDF = "pbkdf2-sha256\$i=600000"
    }
}
