package dev.kpetriv.sprout.recovery

import android.app.Application
import dev.kpetriv.sprout.core.crypto.RecoveryPinVerifier
import dev.kpetriv.sprout.core.database.Session
import dev.kpetriv.sprout.core.database.SubmissionFilter
import dev.kpetriv.sprout.core.database.TestHousehold
import dev.kpetriv.sprout.core.database.entity.RegistrationStatus
import dev.kpetriv.sprout.core.database.usecase.ApprovalResult
import dev.kpetriv.sprout.core.domainhousehold.Chore
import dev.kpetriv.sprout.core.domainhousehold.ChoreReward
import dev.kpetriv.sprout.core.domainledger.AttestationLevel
import dev.kpetriv.sprout.core.domainledger.DeviceOwner
import dev.kpetriv.sprout.core.domainledger.DeviceStatus
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.domainledger.RegisteredDevice
import dev.kpetriv.sprout.core.domainledger.RegisteredParentKey
import dev.kpetriv.sprout.core.model.AccountId
import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.model.ChoreId
import dev.kpetriv.sprout.core.model.ChoreSchedule
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.model.UserId
import dev.kpetriv.sprout.core.sync.FakeBackend
import dev.kpetriv.sprout.core.sync.FakeRecoveryCallables
import dev.kpetriv.sprout.core.sync.FakeSyncClock
import dev.kpetriv.sprout.core.sync.FakeSyncTransport
import dev.kpetriv.sprout.core.sync.SyncEngine
import dev.kpetriv.sprout.core.sync.SyncError
import dev.kpetriv.sprout.core.sync.SyncReport
import dev.kpetriv.sprout.feature.lock.RecoveryUploadResult
import dev.kpetriv.sprout.feature.onboarding.RecoveredDevice
import dev.kpetriv.sprout.feature.onboarding.RecoveryChoice
import dev.kpetriv.sprout.feature.onboarding.RecoveryException
import dev.kpetriv.sprout.feature.onboarding.RecoveryProblem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.security.SecureRandom
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The reported bug (1.1.0, ADR-0013): a parent phone whose storage was cleared signs in again, lands PENDING, and
 * nothing can approve it. Here it recovers through the real app adapters ([SyncAccountRecovery],
 * [ServerRecoveryCheckUploader]), the real check value ([RecoveryPinVerifier], PBKDF2 on the JVM) and the real data
 * layer of three devices ([TestHousehold]) over one [FakeBackend]: parent phone A, the same account's fresh phone B,
 * and a kid phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class) // Not the Hilt app: its startup opens the encrypted database.
class AccountRecoveryRegressionTest {
    private var now = FakeBackend.DEFAULT_NOW_EPOCH_MS
    private val backend = FakeBackend(HOME).apply { nowEpochMs = { now } }
    private val clock = FakeSyncClock()
    private val verifier = RecoveryPinVerifier(SecureRandom(), Dispatchers.Unconfined, argon2id = null)
    private val app = RuntimeEnvironment.getApplication()

    private val a = TestHousehold(app, sessionFor = TestHousehold.parentSession(HOME, PARENT_UID))
    private val kid = TestHousehold(app, sessionFor = TestHousehold.kidSession(HOME, KID_UID, KID_X))

    /** B: the same Google account on a phone with a fresh key, registered PENDING after signing in. */
    private val b = TestHousehold(app, sessionFor = { deviceId ->
        Session(UserId(PARENT_UID), HOME, Role.PARENT, null, deviceId, RegistrationStatus.PENDING, START)
    })

    private val aEngine = engine(a)
    private val kidEngine = engine(kid)
    private val bEngine = engine(b)
    private val aCalls = FakeRecoveryCallables(backend, a.deviceId, UserId(PARENT_UID))
    private val bCalls = FakeRecoveryCallables(backend, b.deviceId, UserId(PARENT_UID))
    private var signInsAgain = 0
    private val bRecovery = SyncAccountRecovery(bCalls, verifier, b.sessions) {
        // The app's "Sign in again" gate: Credential Manager, then the session is the account's again.
        signInsAgain++
        backend.recordSignIn(b.deviceId)
        true
    }
    private val bed = ChoreId("bed")
    private val today = CalendarDate(2026, 11, 2)

    private fun engine(device: TestHousehold) = SyncEngine(
        FakeSyncTransport(backend, device.deviceId, device.session.uid, device.session.role),
        device.outbox,
        device.applier,
        clock,
    )

    @After
    fun close() {
        a.close()
        kid.close()
        b.close()
    }

    private suspend fun SyncEngine.synced(): SyncReport = syncOnce().also { assertNull(it.error, it.toString()) }

    private fun TestHousehold.registered(status: DeviceStatus = DeviceStatus.Active) = RegisteredDevice(
        deviceId,
        DeviceOwner(session.uid, session.role, session.kidId),
        keyStore.publicKey().encoded,
        status,
        AttestationLevel.NONE,
    )

    private suspend fun TestHousehold.publishParentKeys() = database.parentKeyDao().all()
        .map { RegisteredParentKey(it.fingerprint, it.publicKey, it.uid, it.deviceId, it.validFromLamport, null) }
        .forEach(backend::addParentKey)

    private suspend fun balances(device: TestHousehold): Map<AccountId, Money> =
        device.ledger.observeBalances(KID_X).first().associate { it.accountId to it.amount }

    /** A's household: the kid phone linked, A's parent key, a chore, one approved chore (a parent-signed payment). */
    private suspend fun household() {
        backend.registerDevice(a.registered())
        backend.registerDevice(kid.registered())
        a.addKid(KID_X.value)
        a.registerParentKey()
        a.publishParentKeys()
        a.chores.upsert(
            Chore(
                id = bed,
                householdId = HOME,
                kidId = KID_X,
                title = "Make your bed",
                reward = ChoreReward(money = Money(150)),
                schedule = ChoreSchedule.Daily.EVERY_DAY,
                createdAt = a.clock.now(),
            ),
        )
        aEngine.synced()
        kidEngine.synced()
        approveOn(a, today)
        // A registers the check value of its PIN (PIN set-up, newPin = true).
        val uploader = ServerRecoveryCheckUploader(aCalls, verifier, a.sessions)
        val uploaded = uploader.upload(PIN.toCharArray(), newPin = true)
        assertEquals(RecoveryUploadResult.Uploaded(stored = true, attemptsLeft = 10), uploaded)
    }

    /** The kid submits the chore for [day], [parent] approves it with one parent prompt, everyone syncs. */
    private suspend fun approveOn(parent: TestHousehold, day: CalendarDate) {
        kid.chores.submit(bed, day)
        kidEngine.synced()
        val parentEngine = if (parent === a) aEngine else bEngine
        parentEngine.synced()
        val submission = parent.chores.observeSubmissions(SubmissionFilter.PendingForParent).first().single()
        assertIs<ApprovalResult.Approved>(parent.approve(submission.id) { parent.authorization() })
        parentEngine.synced()
        kidEngine.synced()
    }

    /** B, a fresh key of the same account, registers PENDING (what `registerSecondDevice` leaves). */
    private fun bWaits() {
        now += HOUR
        backend.registerDevice(b.registered(DeviceStatus.Pending))
    }

    private suspend fun bActive() {
        b.sessions.save(requireNotNull(b.sessions.get()).copy(deviceStatus = RegistrationStatus.ACTIVE))
    }

    @Test
    fun aPhoneWhoseStorageWasClearedRestoresWithThePinAndKeepsTheHousehold() = runTest {
        household()
        val kidBalances = balances(kid)
        assertEquals(Money(150), kidBalances.values.max())
        bWaits()

        // The waiting screen: the PIN path is offered.
        val options = bRecovery.status().getOrThrow()
        val parameters = assertNotNull(options.pin)
        assertTrue(parameters.supported)
        assertEquals(10, options.attemptsLeft)
        assertNull(options.waitEndsAt)

        // Restore with the PIN.
        val check = bRecovery.verifierFor(PIN.toCharArray(), parameters).getOrThrow()
        assertEquals(
            RecoveredDevice(attemptsLeft = 10, signInAgain = true, method = RecoveryChoice.PIN),
            bRecovery.recover(check).getOrThrow(),
        )
        check.fill(0)
        assertEquals(DeviceStatus.Active, backend.device(b.deviceId)?.status)
        assertIs<DeviceStatus.Revoked>(backend.device(a.deviceId)?.status, "A is removed")
        assertNotNull(backend.parentKeys().single { it.deviceId == a.deviceId }.revokedAtLamport)

        // Every session of the account was revoked, B's too: it signs in again before its first sync.
        bActive()
        assertEquals(SyncError.Unauthenticated, bEngine.syncOnce().error)
        assertTrue(bRecovery.signInAgain())
        assertEquals(1, signInsAgain)
        bEngine.synced()

        // Ledger and kids intact on B.
        assertEquals(listOf(KID_X), b.kids.observeKids().first().map { it.id })
        assertEquals(kidBalances, balances(b))
        assertEquals(a.ledger.observeBalances(KID_X).first().size, b.ledger.observeBalances(KID_X).first().size)
        // A can no longer sync.
        assertEquals(SyncError.Unauthenticated, aEngine.syncOnce().error)

        // B enrols its parent key and pays the next chore: the kid phone accepts B's parent-signed event.
        b.registerParentKey()
        b.publishParentKeys()
        bEngine.synced()
        approveOn(b, CalendarDate(2026, 11, 3))
        assertEquals(Money(300), balances(kid).values.max())
        assertEquals(balances(b), balances(kid))
        assertTrue(kid.ledger.observeQuarantined().first().isEmpty(), "nothing B wrote was refused")
    }

    @Test
    fun tenWrongPinsCloseThePinPathAndLeaveTheOldPhoneAlone() = runTest {
        household()
        bWaits()
        val parameters = assertNotNull(bRecovery.status().getOrThrow().pin)
        val wrong = bRecovery.verifierFor("111222".toCharArray(), parameters).getOrThrow()
        for (left in 9 downTo 0) {
            val refusal = assertIs<RecoveryException>(bRecovery.recover(wrong).exceptionOrNull())
            assertEquals(RecoveryProblem.WrongPin(left), refusal.problem)
        }
        val closed = bRecovery.status().getOrThrow()
        assertNull(closed.pin)
        assertEquals(0, closed.attemptsLeft)
        val right = bRecovery.verifierFor(PIN.toCharArray(), parameters).getOrThrow()
        val refused = assertIs<RecoveryException>(bRecovery.recover(right).exceptionOrNull())
        assertEquals(RecoveryProblem.PinUnavailable, refused.problem)
        assertEquals(DeviceStatus.Active, backend.device(a.deviceId)?.status)
        aEngine.synced()
        // Re-registering the same PIN (the 30-day refresh) does not reopen it.
        val again = ServerRecoveryCheckUploader(aCalls, verifier, a.sessions).upload(PIN.toCharArray(), newPin = false)
        assertEquals(RecoveryUploadResult.Uploaded(stored = true, attemptsLeft = 0), again)
    }

    @Test
    fun withoutThePinTheWaitStartsWhenAskedAndRestoresSevenDaysLater() = runTest {
        household()
        bWaits()
        backend.recordSignIn(b.deviceId)
        val started = assertIs<RecoveryException>(bRecovery.recover(null).exceptionOrNull()).problem
        val endsAt = Instant.fromEpochMilliseconds(now + FakeBackend.RECOVERY_WAIT_MS)
        assertEquals(RecoveryProblem.Wait(endsAt), started)
        assertEquals(endsAt, bRecovery.status().getOrThrow().waitEndsAt)
        assertEquals(DeviceStatus.Active, backend.device(a.deviceId)?.status, "nothing changes before the end")

        now = endsAt.toEpochMilliseconds() - 1
        backend.recordSignIn(b.deviceId)
        val notYet = assertIs<RecoveryException>(bRecovery.recover(null).exceptionOrNull()).problem
        assertEquals(RecoveryProblem.Wait(endsAt), notYet)

        now = endsAt.toEpochMilliseconds() + SIX_MINUTES
        val old = assertIs<RecoveryException>(bRecovery.recover(null).exceptionOrNull()).problem
        assertEquals(RecoveryProblem.RecentSignIn, old, "the sign-in is six minutes old: sign in again")
        backend.recordSignIn(b.deviceId)
        assertEquals(
            RecoveredDevice(attemptsLeft = 0, signInAgain = true, method = RecoveryChoice.WAIT),
            bRecovery.recover(null).getOrThrow(),
        )
        assertIs<DeviceStatus.Revoked>(backend.device(a.deviceId)?.status)
        assertNull(backend.recoveryPinOf(UserId(PARENT_UID)), "the PIN is forgotten; B registers a new one")

        bActive()
        assertTrue(bRecovery.signInAgain())
        bEngine.synced()
        assertEquals(balances(kid), balances(b))
    }

    @Test
    fun aRestoreWhoseSessionRevocationFailedIsFinishedByTheRetry() = runTest {
        household()
        bWaits()
        val parameters = assertNotNull(bRecovery.status().getOrThrow().pin)
        backend.failNextSessionRevocation = true
        val check = bRecovery.verifierFor(PIN.toCharArray(), parameters).getOrThrow()
        assertEquals(
            RecoveredDevice(attemptsLeft = 10, signInAgain = true, method = RecoveryChoice.PIN),
            bRecovery.recover(check).getOrThrow(),
        )
        assertEquals(listOf("recoveryStatus", "recoverParentDevice:PIN", "recoverParentDevice:PIN"), bCalls.calls)
        assertTrue(backend.isSignedOut(a.deviceId), "the retry revoked the old sessions")
        assertEquals(1, backend.recoveries().size)
    }

    @Test
    fun twoPhonesWithDifferentPinsRestoreWithThePinSetOrChangedLast() = runTest {
        household()
        // C: a second working phone of the same account, whose own PIN is different.
        val c = TestHousehold(app, sessionFor = TestHousehold.parentSession(HOME, PARENT_UID))
        try {
            backend.registerDevice(c.registered())
            val cCalls = FakeRecoveryCallables(backend, c.deviceId, UserId(PARENT_UID))
            val cUploader = ServerRecoveryCheckUploader(cCalls, verifier, c.sessions)
            // C registers its unchanged PIN (the first registration after the update): A's value stays.
            assertEquals(
                RecoveryUploadResult.Uploaded(stored = false, attemptsLeft = 10),
                cUploader.upload(OTHER_PIN.toCharArray(), newPin = false),
            )
            bWaits()
            val parameters = assertNotNull(bRecovery.status().getOrThrow().pin)
            val cPin = bRecovery.verifierFor(OTHER_PIN.toCharArray(), parameters).getOrThrow()
            val refused = assertIs<RecoveryException>(bRecovery.recover(cPin).exceptionOrNull()).problem
            assertEquals(RecoveryProblem.WrongPin(9), refused, "C's PIN does not restore: A set the value")

            // C changes its PIN (newPin = true): now C's PIN restores, with ten fresh attempts.
            assertEquals(
                RecoveryUploadResult.Uploaded(stored = true, attemptsLeft = 10),
                cUploader.upload(OTHER_PIN.toCharArray(), newPin = true),
            )
            val changed = assertNotNull(bRecovery.status().getOrThrow().pin)
            val again = bRecovery.verifierFor(OTHER_PIN.toCharArray(), changed).getOrThrow()
            assertEquals(
                RecoveredDevice(attemptsLeft = 10, signInAgain = true, method = RecoveryChoice.PIN),
                bRecovery.recover(again).getOrThrow(),
            )
            assertIs<DeviceStatus.Revoked>(backend.device(a.deviceId)?.status)
            assertIs<DeviceStatus.Revoked>(backend.device(c.deviceId)?.status)
        } finally {
            c.close()
        }
    }

    private companion object {
        val HOME = HouseholdId("home-1")
        val KID_X = KidId("kid-x")
        const val PARENT_UID = "parent-uid"
        const val KID_UID = "kid-uid"
        const val PIN = "482935"
        const val OTHER_PIN = "731864"
        val START = TestHousehold.DEFAULT_START
        const val HOUR = 3_600_000L
        const val SIX_MINUTES = 6 * 60_000L
    }
}
