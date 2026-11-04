package dev.kpetriv.sprout.ai

import android.app.Application
import android.content.Context
import app.cash.turbine.test
import dev.kpetriv.sprout.core.ai.AiAvailability
import dev.kpetriv.sprout.core.ai.AiPath
import dev.kpetriv.sprout.core.ai.AiSettings
import dev.kpetriv.sprout.core.ai.AiStatus
import dev.kpetriv.sprout.core.ai.WeeklySummary
import dev.kpetriv.sprout.core.database.TestHousehold
import dev.kpetriv.sprout.core.database.usecase.ApprovalResult
import dev.kpetriv.sprout.core.database.usecase.WeeklyFactsUseCase
import dev.kpetriv.sprout.core.domainhousehold.Chore
import dev.kpetriv.sprout.core.domainhousehold.ChoreReward
import dev.kpetriv.sprout.core.domainhousehold.IsoWeek
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.model.ChoreId
import dev.kpetriv.sprout.core.model.ChoreSchedule
import dev.kpetriv.sprout.core.model.DayOfWeek
import dev.kpetriv.sprout.core.model.Kid
import dev.kpetriv.sprout.feature.parenthome.SummaryPath
import dev.kpetriv.sprout.feature.parenthome.SummaryWeek
import dev.kpetriv.sprout.feature.parenthome.WeeklySummaryUi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** [GatewayWeeklySummarySource] over a real Room household (the trial one) and [WeeklyFactsUseCase]. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class) // Not the Hilt app: its startup opens the encrypted database.
class WeeklySummarySourceTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val household = TestHousehold(context)
    private val facts = WeeklyFactsUseCase(
        household.kids,
        household.chores,
        household.ledger,
        household.calendar,
        { household.gbp },
    )
    private val gateway = FakeAiGateway()
    private val preferences = FakeAiPreferences()
    private val changes = MutableSharedFlow<Unit>(replay = 1).apply { tryEmit(Unit) }

    private fun source(scheduler: TestCoroutineScheduler) = GatewayWeeklySummarySource(
        facts,
        gateway,
        preferences,
        { changes },
        household.clock,
        UnconfinedTestDispatcher(scheduler),
    )

    @After
    fun close() = household.close()

    private suspend fun approvedChore(): Kid {
        val (kid, _) = household.addKid()
        household.registerParentKey()
        val chore = Chore(
            ChoreId("tidy"),
            household.session.householdId,
            kid.id,
            "Tidy your room",
            reward = ChoreReward(money = Money(50), minutes = Money(15)),
            schedule = ChoreSchedule.Weekly(DayOfWeek.MONDAY),
            createdAt = household.clock.now(),
        )
        household.chores.upsert(chore)
        val submission = household.chores.submit(chore.id, CalendarDate(2026, 11, 2))
        assertTrue(household.approve(submission.id) { household.authorization() } is ApprovalResult.Approved)
        return kid
    }

    @Test
    fun `this week's facts become the rules summary with its path and time`() = runTest {
        approvedChore()

        source(testScheduler).observe(SummaryWeek.THIS_WEEK).test {
            val ready = assertIs<WeeklySummaryUi.Ready>(awaitItem())
            assertEquals(SummaryWeek.THIS_WEEK, ready.week)
            assertEquals(SummaryPath.RULES, ready.path)
            assertEquals(household.clock.now(), ready.generatedAt)
            assertEquals(false, ready.offerModelDownload, "the rules-only gateway has no model to download")
            assertTrue(ready.text.isNotBlank())
            assertTrue("Leo" in ready.text, "the template names the kid")
            assertTrue("Leo" !in ready.toString(), "the text is never printed")
            val asked = gateway.summaryCalls.single()
            assertEquals(IsoWeek(2026, 45), asked.week)
            assertEquals(Money(50), asked.kids.single().earned.total)
        }
    }

    @Test
    fun `last week asks for the previous ISO week and a Nano answer keeps its path`() = runTest {
        gateway.summaryAnswer = WeeklySummary("A quiet week. Nothing was earned.", AiPath.NANO)

        source(testScheduler).observe(SummaryWeek.LAST_WEEK).test {
            val ready = assertIs<WeeklySummaryUi.Ready>(awaitItem())
            assertEquals(SummaryWeek.LAST_WEEK, ready.week)
            assertEquals(SummaryPath.NANO, ready.path)
            assertEquals("A quiet week. Nothing was earned.", ready.text)
            assertEquals(IsoWeek(2026, 44), gateway.summaryCalls.single().week)
            assertTrue(gateway.summaryCalls.single().complete)
        }
    }

    @Test
    fun `the download is offered only while the model is downloadable and the parent has not agreed`() = runTest {
        gateway.status = AiStatus(AiAvailability.DOWNLOADABLE)
        source(testScheduler).observe(SummaryWeek.THIS_WEEK).test {
            assertEquals(true, assertIs<WeeklySummaryUi.Ready>(awaitItem()).offerModelDownload)
        }

        preferences.state.value = AiSettings(downloadConsent = true)
        source(testScheduler).observe(SummaryWeek.THIS_WEEK).test {
            assertEquals(false, assertIs<WeeklySummaryUi.Ready>(awaitItem()).offerModelDownload)
        }

        gateway.status = AiStatus.AVAILABLE
        preferences.state.value = AiSettings()
        source(testScheduler).observe(SummaryWeek.THIS_WEEK).test {
            assertEquals(false, assertIs<WeeklySummaryUi.Ready>(awaitItem()).offerModelDownload)
        }
    }

    @Test
    fun `household activity recomputes, and only facts that changed reach the gateway`() = runTest {
        source(testScheduler).observe(SummaryWeek.THIS_WEEK).test {
            assertIs<WeeklySummaryUi.Ready>(awaitItem())
            assertTrue(gateway.summaryCalls.single().isEmpty)

            val kid = approvedChore()
            changes.emit(Unit)
            assertIs<WeeklySummaryUi.Ready>(awaitItem())
            assertEquals(2, gateway.summaryCalls.size)
            assertEquals(Money(50), gateway.summaryCalls.last().totals.earned.total)

            // A change that leaves the facts alone (here: none at all), then one that changes them (one write). Either
            // way exactly one more summary is made: the unchanged facts never reach the gateway.
            changes.emit(Unit)
            household.kids.upsert(kid.copy(firstName = "Leon"))
            changes.emit(Unit)
            assertIs<WeeklySummaryUi.Ready>(awaitItem())
            assertEquals(3, gateway.summaryCalls.size)
            assertEquals("Leon", gateway.summaryCalls.last().kids.single().firstName)
            expectNoEvents()
        }
    }

    @Test
    fun `facts that cannot be read show unavailable`() = runTest {
        household.database.close()

        source(testScheduler).observe(SummaryWeek.THIS_WEEK).test {
            assertEquals(WeeklySummaryUi.Unavailable, awaitItem())
        }
        assertTrue(gateway.summaryCalls.isEmpty())
    }

    @Test
    fun `Room activity fires once when collected and again when a kid or the ledger changes`() = runTest {
        RoomHouseholdActivity(household.database).changes().test {
            awaitItem()
            household.addKid()
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
    }
}
