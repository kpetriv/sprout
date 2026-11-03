package dev.kpetriv.sprout.core.database

import dev.kpetriv.sprout.core.database.entity.AuditAction
import dev.kpetriv.sprout.core.database.entity.RegistrationStatus
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.model.UserId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class SessionTest {
    private val clock = testClock()
    private val database = inMemoryDatabase()
    private val sessions = RoomSessionStore(database, clock)
    private val authors = SessionBackedAuthorProvider(sessions)
    private val device = DeviceId("device-1")

    @After
    fun close() = database.close()

    private fun session(role: Role, kidId: KidId? = null) =
        Session(UserId("uid-1"), HOME, role, kidId, device, RegistrationStatus.ACTIVE, clock.now())

    @Test
    fun withoutASessionNothingCanBeAuthored() = runTest {
        assertNull(sessions.get())
        assertFailsWith<NoSessionException> { authors.currentAuthor() }
        assertFailsWith<NoSessionException> { authors.kidAuthor(KID_X) }
    }

    @Test
    fun aParentSessionAuthorsAsTheParentAndAsTheKidInKidMode() = runTest {
        sessions.save(session(Role.PARENT))

        val parent = authors.currentAuthor()
        assertEquals(HOME, parent.householdId)
        assertEquals(UserId("uid-1"), parent.uid)
        assertEquals(Role.PARENT, parent.role)
        assertEquals(device, parent.deviceId)
        // D-M3-12: kid mode on a parent device is the parent's uid and device with role KID, for any kid.
        assertEquals(parent.copy(role = Role.KID), authors.kidAuthor(KID_X))
        assertEquals(parent.copy(role = Role.KID), authors.kidAuthor(KidId("another-kid")))
    }

    @Test
    fun aKidSessionNeverAuthorsAsAParentNorForAnotherKid() = runTest {
        sessions.save(session(Role.KID, KID_X))

        val kid = authors.currentAuthor()
        assertEquals(Role.KID, kid.role)
        assertEquals(kid, authors.kidAuthor(KID_X))
        assertFailsWith<IllegalArgumentException> { authors.kidAuthor(KidId("another-kid")) }
    }

    @Test
    fun aKidSessionNamesItsKid() {
        assertFailsWith<IllegalArgumentException> { session(Role.KID, kidId = null) }
    }

    @Test
    fun theTrialSessionIsCreatedOnceAndNeverReplacesARealOne() = runTest {
        val trial = sessions.ensureLocalTrialSession(device)
        assertTrue(trial.isLocalTrial)
        assertEquals(LocalTrial.PARENT, trial.uid)
        assertEquals(LocalTrial.HOUSEHOLD, trial.householdId)
        assertEquals(Role.PARENT, trial.role)
        assertEquals(trial, sessions.ensureLocalTrialSession(DeviceId("other")))

        val real = session(Role.PARENT)
        sessions.save(real)
        assertEquals(real, sessions.ensureLocalTrialSession(device))
        assertEquals(real, sessions.observe().first())
    }

    @Test
    fun startingAndClearingASessionAreAudited() = runTest {
        sessions.save(session(Role.PARENT))
        sessions.save(session(Role.PARENT).copy(deviceStatus = RegistrationStatus.REVOKED))
        sessions.clear()
        sessions.clear()

        assertNull(sessions.get())
        assertEquals(
            listOf(AuditAction.SESSION_STARTED, AuditAction.SESSION_CLEARED),
            database.auditLogDao().all().map { it.action },
        )
        assertTrue(database.auditLogDao().all().all { it.target == HOME.value })
    }
}
