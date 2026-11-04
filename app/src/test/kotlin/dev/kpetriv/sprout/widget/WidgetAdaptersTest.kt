package dev.kpetriv.sprout.widget

import android.app.Application
import dev.kpetriv.sprout.FakeSessionStore
import dev.kpetriv.sprout.FakeStartupStatus
import dev.kpetriv.sprout.core.database.TestHousehold
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.kidSession
import dev.kpetriv.sprout.navigation.ShellSignals
import dev.kpetriv.sprout.parentSession
import dev.kpetriv.sprout.startup.StartupFailure
import dev.kpetriv.sprout.startup.StartupState
import dev.kpetriv.sprout.sync.GateState
import dev.kpetriv.sprout.sync.Removal
import dev.kpetriv.sprout.sync.RemovalReason
import dev.kpetriv.sprout.trialSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Where the widget shows kid data (M7 §4, D-M7-1): only on that kid's own linked phone. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class) // Not the Hilt app: its startup opens the encrypted database.
class WidgetAdaptersTest {
    private val household = TestHousehold(
        RuntimeEnvironment.getApplication(),
        sessionFor = TestHousehold.parentSession(HouseholdId("home-1"), "parent-uid"),
    )
    private val ready = StartupState.Ready

    @After
    fun close() = household.close()

    @Test
    fun `kid data only for a KID session, nothing at all while a removal or the gate is pending`() {
        val kid = kidSession(KidId("kid-leo"))
        assertEquals(WidgetTarget.KidOf(KidId("kid-leo")), widgetTargetOf(ready, kid, removal = false, gate = false))
        assertEquals(WidgetTarget.OpenApp, widgetTargetOf(ready, parentSession(), removal = false, gate = false))
        assertEquals(WidgetTarget.OpenApp, widgetTargetOf(ready, trialSession(), removal = false, gate = false))
        assertEquals(WidgetTarget.OpenApp, widgetTargetOf(ready, null, removal = false, gate = false))
        assertEquals(WidgetTarget.Blank, widgetTargetOf(ready, kid, removal = true, gate = false))
        assertEquals(WidgetTarget.Blank, widgetTargetOf(ready, null, removal = true, gate = false))
        assertEquals(WidgetTarget.Blank, widgetTargetOf(ready, kid, removal = false, gate = true))
        assertEquals(WidgetTarget.Blank, widgetTargetOf(ready, parentSession(), removal = false, gate = true))
        // The trial household has no sign-in to lose.
        assertEquals(WidgetTarget.OpenApp, widgetTargetOf(ready, trialSession(), removal = false, gate = true))
        val failed = StartupState.Failed(StartupFailure.DATABASE)
        assertEquals(WidgetTarget.Blank, widgetTargetOf(failed, kid, removal = false, gate = false))
    }

    @Test
    fun `a linked kid phone shows the kid's day, formatted for the tier`() = runTest {
        val (kid, _) = household.addKid("kid-leo", age = 9)
        val source = source(sessions = FakeSessionStore(kidSession(kid.id)))

        val state = assertIs<WidgetState.Kid>(source.observe().first())

        assertEquals("Leo", state.name)
        assertEquals("£0.00", state.balance)
        assertEquals(0, state.dueToday)
    }

    @Test
    fun `a parent's phone, a kid that is gone and a removal never show kid data`() = runTest {
        household.addKid("kid-leo")
        assertEquals(WidgetState.OpenApp, source(FakeSessionStore(parentSession())).observe().first())
        assertEquals(WidgetState.OpenApp, source(FakeSessionStore(kidSession(KidId("kid-gone")))).observe().first())
        val removed = source(
            FakeSessionStore(kidSession(KidId("kid-leo"))),
            removal = Removal(RemovalReason.DEVICE_REVOKED),
        )
        assertEquals(WidgetState.Blank, removed.observe().first())
        val gate = source(FakeSessionStore(kidSession(KidId("kid-leo"))), gate = GateState.Checking)
        assertEquals(WidgetState.Blank, gate.observe().first())
    }

    @Test
    fun `nothing is shown before startup has finished, and a failing read is blank`() = runTest {
        val startup = FakeStartupStatus(StartupState.Running)
        val source = SessionWidgetStateSource(
            startup,
            FakeSessionStore(parentSession()),
            signals(),
            household.kidTodaySummary,
        )
        startup.state.value = StartupState.Ready
        assertEquals(WidgetState.OpenApp, source.observe().first())

        household.addKid("kid-leo")
        household.close()
        val closed = source(FakeSessionStore(kidSession(KidId("kid-leo"))))
        assertEquals(WidgetState.Blank, closed.observe().first())
    }

    private fun source(sessions: FakeSessionStore, removal: Removal? = null, gate: GateState? = null) =
        SessionWidgetStateSource(FakeStartupStatus(), sessions, signals(removal, gate), household.kidTodaySummary)

    private fun signals(removal: Removal? = null, gate: GateState? = null) = object : ShellSignals {
        override val removal = MutableStateFlow(removal)
        override val gate = MutableStateFlow(gate)
        override val parentSetupPending = MutableStateFlow<String?>(null)

        override fun digestOf(householdId: HouseholdId): String = householdId.value
    }
}
