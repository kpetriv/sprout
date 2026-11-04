package dev.kpetriv.sprout.settings

import android.app.Application
import dev.kpetriv.sprout.core.database.ChatPruneScheduler
import dev.kpetriv.sprout.core.database.NoHouseholdPolicyYetException
import dev.kpetriv.sprout.core.database.TestHousehold
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.sync.SyncTransport
import dev.kpetriv.sprout.feature.settings.HouseholdRulesState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Settings' M7 adapters over the real Room household (M7 §1, §3). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class) // Not the Hilt app: its startup opens the encrypted database.
class PolicyAdaptersTest {
    private val household = TestHousehold(
        RuntimeEnvironment.getApplication(),
        sessionFor = TestHousehold.parentSession(HouseholdId("home-1"), "parent-uid"),
    )
    private var pruneRequests = 0
    private val rules = RepositoryHouseholdRules(
        household.policies,
        object : ChatPruneScheduler {
            override fun scheduleDaily() = Unit

            override fun pruneSoon() {
                pruneRequests += 1
            }
        },
    )

    @After
    fun close() = household.close()

    @Test
    fun `nothing is reported until the household has a policy, then its values`() = runTest {
        assertNull(withTimeoutOrNull(100) { rules.observe().first() })

        household.policies.ensure(household.gbp)
        assertEquals(HouseholdRulesState(chatRetentionDays = null, dailyGameCapMinor = null), rules.observe().first())
    }

    @Test
    fun `a new retention is written and prunes this phone soon, the cap is written`() = runTest {
        household.policies.ensure(household.gbp)

        rules.setChatRetentionDays(30)
        rules.setDailyGameCap(120)

        assertEquals(HouseholdRulesState(chatRetentionDays = 30, dailyGameCapMinor = 120), rules.observe().first())
        assertEquals(1, pruneRequests)
    }

    @Test
    fun `without a policy row the setters let the refusal through and prune nothing`() = runTest {
        // Settings shows "Waiting for the household policy to sync" for it (D-M7-57, audit 2 I1).
        assertFailsWith<NoHouseholdPolicyYetException> { rules.setChatRetentionDays(30) }
        assertFailsWith<NoHouseholdPolicyYetException> { rules.setDailyGameCap(120) }

        assertEquals(0, pruneRequests)
        assertNull(withTimeoutOrNull(100) { rules.observe().first() }, "and no policy was created")
    }

    @Test
    fun `the readable export of a household without chat is empty`() = runTest {
        val unused = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SyncTransport::class.java)) { _, _, _ ->
            error("no transport call expected")
        } as SyncTransport
        assertTrue(ReaderChatExportSource(household.chatExport, unused).threads().isEmpty())
    }
}
