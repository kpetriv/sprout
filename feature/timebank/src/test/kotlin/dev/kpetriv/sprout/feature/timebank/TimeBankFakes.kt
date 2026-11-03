package dev.kpetriv.sprout.feature.timebank

import dev.kpetriv.sprout.core.model.KidId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.time.Duration
import kotlin.time.Instant

/** Keeps a WhileSubscribed state flow hot for the rest of the test. */
@OptIn(ExperimentalCoroutinesApi::class)
fun <T> TestScope.subscribe(state: StateFlow<T>): StateFlow<T> {
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { state.collect { } }
    return state
}

/** 4 November 2026, 12:00 UTC. */
val NOON: Instant = Instant.parse("2026-11-04T12:00:00Z")

class FakeTimeBankSource : TimeBankStateSource {
    val snapshots = MutableStateFlow<Map<KidId, TimeBankSnapshot?>>(emptyMap())
    var failure: Throwable? = null
    val observed = mutableListOf<KidId>()

    fun set(kidId: KidId, snapshot: TimeBankSnapshot?) {
        snapshots.value = snapshots.value + (kidId to snapshot)
    }

    override fun observe(kidId: KidId): Flow<TimeBankSnapshot?> = snapshots
        .onSubscription { observed += kidId }
        .map { failure?.let { error -> throw error } ?: it[kidId] }
}

class FakeTimeBankControl : TimeBankControl {
    var startResult: Result<Unit> = Result.success(Unit)
    var stopResult: Result<Long> = Result.success(0)
    var gate: CompletableDeferred<Unit>? = null
    val calls = mutableListOf<String>()

    override suspend fun start(kidId: KidId): Result<Unit> {
        calls += "start:${kidId.value}"
        gate?.await()
        return startResult
    }

    override suspend fun stop(kidId: KidId): Result<Long> {
        calls += "stop:${kidId.value}"
        gate?.await()
        return stopResult
    }
}

/** A clock whose ticks a test drives by [advanceBy]. */
class FakeTimeBankClock(start: Instant = NOON, private val zone: ZoneId = ZoneOffset.UTC) : TimeBankClock {
    private val current = MutableStateFlow(start)
    var tickSubscriptions = 0
        private set

    override fun now(): Instant = current.value

    override fun zone(): ZoneId = zone

    override fun ticks(): Flow<Instant> = current.onSubscription { tickSubscriptions++ }

    fun advanceBy(duration: Duration) {
        current.value += duration
    }
}
