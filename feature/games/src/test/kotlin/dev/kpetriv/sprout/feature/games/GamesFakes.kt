package dev.kpetriv.sprout.feature.games

import androidx.lifecycle.SavedStateHandle
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.GameCreditResult
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.games.GameCredit
import dev.kpetriv.sprout.core.games.GameId
import dev.kpetriv.sprout.core.games.GameKind
import dev.kpetriv.sprout.core.games.Score
import dev.kpetriv.sprout.core.games.TablesSetup
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.KidUiTier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(val dispatcher: TestDispatcher = UnconfinedTestDispatcher()) : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)

    override fun finished(description: Description) = Dispatchers.resetMain()
}

object TestData {
    val gbp = Currency.Fiat("GBP")
    val kid = KidId("kid-leo")

    fun handle(tier: KidUiTier, kind: GameKind) = SavedStateHandle(
        mapOf(
            GameViewModel.KID_ID_KEY to kid.value,
            GameViewModel.TIER_KEY to tier.name,
            GameViewModel.KIND_KEY to kind.name,
        ),
    )
}

/** What the system restores after process death: the saved values only, in a fresh handle. */
fun SavedStateHandle.restored() = SavedStateHandle(keys().associateWith { get<Any?>(it) })

/** Hands out [seeds] in order, then keeps returning the last one. */
class FixedSeeds(private vararg val seeds: Long) : GameSeedSource {
    var calls = 0
        private set

    override fun next(): Long = seeds[minOf(calls++, seeds.lastIndex)]
}

class FakeCreditSubmitter : GameCreditSubmitter {
    data class Call(val kidId: KidId, val gameId: GameId, val score: Score)

    val calls = mutableListOf<Call>()

    /** Results that [submit] returned: a submit cancelled half-way never lands here. */
    val completed = mutableListOf<GameCreditResult>()

    /** The next result; credits what the score earns, in full, with 20 left by default. */
    var respond: suspend (GameId, Score) -> GameCreditResult = { gameId, score ->
        GameCreditResult.Credited(Money(checkNotNull(GameCredit.earned(gameId, score))), remainingAfter = Money(20))
    }

    override suspend fun submit(kidId: KidId, gameId: GameId, score: Score): GameCreditResult {
        calls += Call(kidId, gameId, score)
        return respond(gameId, score).also { completed += it }
    }
}

/** A [GameTimer] the test moves by hand. */
class FakeGameTimer(var now: Long = 1_000L) : GameTimer {
    override fun elapsedMillis(): Long = now

    fun advanceBy(millis: Long) {
        now += millis
    }
}

/** The app's scope in tests: never cancelled by a ViewModel, runs on [dispatcher] (the test Main dispatcher). */
fun creditScope(dispatcher: TestDispatcher) = GameCreditScope(CoroutineScope(SupervisorJob() + dispatcher))

class FakeCapSource(initial: Money = Money(23)) : GameCapSource {
    val remaining = MutableStateFlow(initial)
    var fail = false

    override fun observeRemaining(kidId: KidId): Flow<Money> = if (fail) {
        flow { error("cap unavailable") }
    } else {
        remaining
    }
}

/** A [TablesSetupStore] in memory: per kid, observed live; [saved] records every save. */
class FakeTablesSetupStore(initial: Map<KidId, TablesSetup> = emptyMap()) : TablesSetupStore {
    val setups = MutableStateFlow(initial)
    val saved = mutableListOf<Pair<KidId, TablesSetup>>()
    var fail = false

    override fun observe(kidId: KidId): Flow<TablesSetup?> = if (fail) {
        flow { error("store unavailable") }
    } else {
        setups.map { it[kidId] }
    }

    override suspend fun save(kidId: KidId, setup: TablesSetup) {
        saved += kidId to setup
        setups.value = setups.value + (kidId to setup)
    }
}

/** A [GameHistorySource] the test fills: [recent] plays and [best] this week, per kind. */
class FakeHistorySource : GameHistorySource {
    val recent = MutableStateFlow<List<GamePlaySummary>>(emptyList())
    val best = MutableStateFlow<Int?>(null)

    override fun observeRecent(kidId: KidId, kind: GameKind, limit: Int): Flow<List<GamePlaySummary>> =
        recent.map { plays -> plays.filter { it.gameId.kind == kind }.take(limit) }

    override fun observeBestScoreThisWeek(kidId: KidId, kind: GameKind): Flow<Int?> = best
}
