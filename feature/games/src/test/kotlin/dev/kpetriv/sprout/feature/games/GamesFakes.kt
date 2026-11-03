package dev.kpetriv.sprout.feature.games

import androidx.lifecycle.SavedStateHandle
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.games.GameId
import dev.kpetriv.sprout.core.games.GameKind
import dev.kpetriv.sprout.core.games.Score
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.time.FakeClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(val dispatcher: TestDispatcher = UnconfinedTestDispatcher()) : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)

    override fun finished(description: Description) = Dispatchers.resetMain()
}

object TestData {
    val gbp = Currency.Fiat("GBP")
    val kid = KidId("kid-leo")
    val now: Instant = Instant.parse("2026-11-04T09:00:00Z")

    fun clock() = FakeClock(now)

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

    /** The next result; credits whatever was asked for with 20 left by default. */
    var respond: suspend (GameId, Score) -> GameCreditResult = { _, score ->
        GameCreditResult.Credited(Money(score.correct.toLong()), remainingAfter = Money(20))
    }

    override suspend fun submit(kidId: KidId, gameId: GameId, score: Score): GameCreditResult {
        calls += Call(kidId, gameId, score)
        return respond(gameId, score)
    }
}

class FakeCapSource(initial: Money = Money(23)) : GameCapSource {
    val remaining = MutableStateFlow(initial)
    var fail = false

    override fun observeRemaining(kidId: KidId): Flow<Money> = if (fail) {
        flow { error("cap unavailable") }
    } else {
        remaining
    }
}
