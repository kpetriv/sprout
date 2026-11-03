package dev.kpetriv.sprout.work

import dev.kpetriv.sprout.FakeShellPreferences
import dev.kpetriv.sprout.REAL_HOUSEHOLD
import dev.kpetriv.sprout.core.database.entity.RegistrationStatus
import dev.kpetriv.sprout.kidSession
import dev.kpetriv.sprout.parentSession
import dev.kpetriv.sprout.trialSession
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The planner's gate (review M5): which sessions may queue allowance and interest. */
class ScheduledActionsPlannerTest {
    private val preferences = FakeShellPreferences()

    private fun mayPlan(session: dev.kpetriv.sprout.core.database.Session?) =
        UseCaseScheduledActionsPlanner.mayPlan(session, preferences)

    @Test
    fun `the trial plans, a real parent only after its first successful pull`() {
        assertTrue(mayPlan(trialSession()))
        assertFalse(mayPlan(parentSession()))
        preferences.recordFirstPull(REAL_HOUSEHOLD)
        assertTrue(mayPlan(parentSession()))
    }

    @Test
    fun `no session, a kid's phone, a PENDING or REVOKED phone never plan`() {
        preferences.recordFirstPull(REAL_HOUSEHOLD)
        assertFalse(mayPlan(null))
        assertFalse(mayPlan(kidSession()))
        assertFalse(mayPlan(parentSession(RegistrationStatus.PENDING)))
        assertFalse(mayPlan(parentSession(RegistrationStatus.REVOKED)))
    }
}
