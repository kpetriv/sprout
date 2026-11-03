package dev.kpetriv.sprout.feature.timebank

import androidx.lifecycle.SavedStateHandle
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.KidUiTier
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class TimeBankViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val kid = KidId("kid-leo")
    private val source = FakeTimeBankSource()
    private val control = FakeTimeBankControl()
    private val clock = FakeTimeBankClock()

    private fun viewModel(tier: KidUiTier = KidUiTier.SPROUT, timeBankClock: TimeBankClock = clock) = TimeBankViewModel(
        savedStateHandle = SavedStateHandle(
            mapOf(TimeBankViewModel.KID_ID_KEY to kid.value, TimeBankViewModel.TIER_KEY to tier.name),
        ),
        source = source,
        control = control,
        clock = timeBankClock,
    )

    private fun idle(balance: Long = 45, sessions: List<TimeBankSession> = emptyList()) =
        TimeBankSnapshot(running = false, startedAt = null, balanceMinutes = balance, sessions = sessions)

    @Test
    fun `idle shows the balance, today's sessions and the history, newest first`() = runTest {
        val sessions = listOf(
            TimeBankSession(NOON - 1.days, 30),
            TimeBankSession(NOON - 4.hours, 15),
            TimeBankSession(NOON - 1.hours, 20),
        )
        source.set(kid, idle(sessions = sessions))

        val state = assertIs<TimeBankUiState.Ready>(subscribe(viewModel(KidUiTier.SAPLING).state).value)

        assertEquals(KidUiTier.SAPLING, state.tier)
        assertEquals(45, state.remainingMinutes)
        assertFalse(state.running)
        assertEquals(1f, state.ringFraction)
        assertEquals(listOf(20L, 15L), state.today.map { it.minutes })
        assertEquals(35, state.todayMinutes)
        assertEquals(listOf(20L, 15L, 30L), state.history.map { it.minutes })
        assertEquals(LocalDateTime.of(2026, 11, 4, 11, 0), state.history.first().startedAt)
        assertTrue(state.canStart)
        assertEquals(0, clock.tickSubscriptions, "an idle screen does not tick")
    }

    @Test
    fun `today follows the household zone`() = runTest {
        // Noon UTC on 4 November is 01:00 on 5 November in Auckland (UTC+13): 10:30 UTC was yesterday there.
        val sessions = listOf(TimeBankSession(NOON - 90.minutes, 10), TimeBankSession(NOON - 30.minutes, 5))
        source.set(kid, idle(sessions = sessions))
        val auckland = FakeTimeBankClock(zone = ZoneId.of("Pacific/Auckland"))

        val inUtc = assertIs<TimeBankUiState.Ready>(subscribe(viewModel().state).value)
        val inAuckland = assertIs<TimeBankUiState.Ready>(subscribe(viewModel(timeBankClock = auckland).state).value)

        assertEquals(listOf(5L, 10L), inUtc.today.map { it.minutes })
        assertEquals(listOf(5L), inAuckland.today.map { it.minutes })
    }

    @Test
    fun `a running session ticks from its start time, not from when the screen opened`() = runTest {
        // Started ten minutes before this ViewModel existed, as after process death.
        source.set(kid, TimeBankSnapshot(running = true, startedAt = NOON - 10.minutes, balanceMinutes = 45))
        val state = subscribe(viewModel().state)

        val first = assertIs<TimeBankUiState.Ready>(state.value)
        assertEquals(600, first.elapsedSeconds)
        assertEquals(35, first.remainingMinutes)
        assertTrue(first.canStop)
        assertFalse(first.canStart)

        clock.advanceBy(30.seconds)
        val later = assertIs<TimeBankUiState.Ready>(state.value)
        assertEquals(630, later.elapsedSeconds)
        assertEquals(35, later.remainingMinutes, "a started minute still counts as left")
        assertEquals((45 * 60 - 630) / (45f * 60), later.ringFraction)
    }

    @Test
    fun `time is up once the session has used the whole balance`() = runTest {
        source.set(kid, TimeBankSnapshot(running = true, startedAt = NOON - 5.minutes, balanceMinutes = 5))
        val state = subscribe(viewModel().state)

        val ready = assertIs<TimeBankUiState.Ready>(state.value)
        assertTrue(ready.timeUp)
        assertEquals(0, ready.remainingSeconds)
        assertEquals(0, ready.remainingMinutes)
        assertTrue(ready.canStop)
    }

    @Test
    fun `a kid without a time bank account sees an empty balance and cannot start`() = runTest {
        source.set(kid, null)
        val vm = viewModel(KidUiTier.SEEDLING)
        val state = subscribe(vm.state)

        val ready = assertIs<TimeBankUiState.Ready>(state.value)
        assertTrue(ready.isEmpty)
        assertEquals(0f, ready.ringFraction)
        vm.onEvent(TimeBankEvent.Start)

        assertTrue(control.calls.isEmpty())
    }

    @Test
    fun `start goes through the control once while it is in flight`() = runTest {
        source.set(kid, idle())
        val gate = CompletableDeferred<Unit>()
        control.gate = gate
        val vm = viewModel()
        val state = subscribe(vm.state)

        vm.onEvent(TimeBankEvent.Start)
        vm.onEvent(TimeBankEvent.Start)
        assertTrue(assertIs<TimeBankUiState.Ready>(state.value).isBusy)
        gate.complete(Unit)

        assertEquals(listOf("start:kid-leo"), control.calls)
        val ready = assertIs<TimeBankUiState.Ready>(state.value)
        assertFalse(ready.isBusy)
        assertNull(ready.message)
    }

    @Test
    fun `a refused start says why`() = runTest {
        source.set(kid, idle())
        control.startResult = Result.failure(TimeBankRefusedException(TimeBankRefusal.NO_BALANCE))
        val vm = viewModel()
        val state = subscribe(vm.state)

        vm.onEvent(TimeBankEvent.Start)

        assertEquals(
            TimeBankMessage.StartFailed(TimeBankRefusal.NO_BALANCE),
            assertIs<TimeBankUiState.Ready>(state.value).message,
        )
    }

    @Test
    fun `a start that throws is a generic failure`() = runTest {
        source.set(kid, idle())
        val vm = TimeBankViewModel(
            savedStateHandle = SavedStateHandle(
                mapOf(TimeBankViewModel.KID_ID_KEY to kid.value, TimeBankViewModel.TIER_KEY to "SPROUT"),
            ),
            source = source,
            control = object : TimeBankControl {
                override suspend fun start(kidId: KidId): Result<Unit> = error("disk full")

                override suspend fun stop(kidId: KidId): Result<Long> = error("disk full")
            },
            clock = clock,
        )
        val state = subscribe(vm.state)

        vm.onEvent(TimeBankEvent.Start)

        assertEquals(TimeBankMessage.StartFailed(null), assertIs<TimeBankUiState.Ready>(state.value).message)
    }

    @Test
    fun `stop reports the minutes the ledger debited, once`() = runTest {
        source.set(kid, TimeBankSnapshot(running = true, startedAt = NOON - 12.minutes, balanceMinutes = 45))
        control.stopResult = Result.success(12)
        val vm = viewModel()
        val state = subscribe(vm.state)

        vm.onEvent(TimeBankEvent.Stop)
        assertEquals(TimeBankMessage.Stopped(12), assertIs<TimeBankUiState.Ready>(state.value).message)
        vm.onEvent(TimeBankEvent.MessageShown)

        assertEquals(listOf("stop:kid-leo"), control.calls)
        assertNull(assertIs<TimeBankUiState.Ready>(state.value).message)
    }

    @Test
    fun `a failed stop keeps the session running and says so`() = runTest {
        source.set(kid, TimeBankSnapshot(running = true, startedAt = NOON - 1.minutes, balanceMinutes = 45))
        control.stopResult = Result.failure(IllegalStateException("clash"))
        val vm = viewModel()
        val state = subscribe(vm.state)

        vm.onEvent(TimeBankEvent.Stop)

        val ready = assertIs<TimeBankUiState.Ready>(state.value)
        assertEquals(TimeBankMessage.StopFailed, ready.message)
        assertTrue(ready.running)
    }

    @Test
    fun `stop is ignored while nothing runs and start while something does`() = runTest {
        source.set(kid, idle())
        val vm = viewModel()
        subscribe(vm.state)
        vm.onEvent(TimeBankEvent.Stop)

        source.set(kid, TimeBankSnapshot(running = true, startedAt = NOON, balanceMinutes = 45))
        vm.onEvent(TimeBankEvent.Start)

        assertTrue(control.calls.isEmpty())
    }

    @Test
    fun `a load failure is an error until retry succeeds`() = runTest {
        source.failure = IllegalStateException("database closed")
        val vm = viewModel()
        val state = subscribe(vm.state)
        assertEquals(TimeBankUiState.Error, state.value)

        source.failure = null
        source.set(kid, idle())
        vm.onEvent(TimeBankEvent.Retry)

        assertIs<TimeBankUiState.Ready>(state.value)
    }
}
