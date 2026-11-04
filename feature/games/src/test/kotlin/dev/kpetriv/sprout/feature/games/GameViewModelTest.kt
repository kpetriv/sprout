package dev.kpetriv.sprout.feature.games

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.kpetriv.sprout.core.domainledger.AppendResult
import dev.kpetriv.sprout.core.domainledger.GameCreditResult
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.domainledger.Violation
import dev.kpetriv.sprout.core.games.ArithmeticRound
import dev.kpetriv.sprout.core.games.Difficulty
import dev.kpetriv.sprout.core.games.GameCredit
import dev.kpetriv.sprout.core.games.GameId
import dev.kpetriv.sprout.core.games.GameKind
import dev.kpetriv.sprout.core.games.GameSession
import dev.kpetriv.sprout.core.games.MemoryRound
import dev.kpetriv.sprout.core.games.Score
import dev.kpetriv.sprout.core.games.WordRound
import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.model.KidUiTier
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GameViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val submitter = FakeCreditSubmitter()
    private val timer = FakeGameTimer()

    private fun viewModel(
        tier: KidUiTier = KidUiTier.SPROUT,
        kind: GameKind = GameKind.ARITHMETIC,
        handle: SavedStateHandle = TestData.handle(tier, kind),
        seeds: GameSeedSource = FixedSeeds(SEED, OTHER_SEED),
        submitter: GameCreditSubmitter = this.submitter,
        timer: GameTimer = this.timer,
    ) = GameViewModel(
        handle = handle,
        submitter = submitter,
        seeds = seeds,
        currency = { TestData.gbp },
        timer = timer,
        creditScope = creditScope(main.dispatcher),
    )

    private fun session(tier: KidUiTier, kind: GameKind, seed: Long = SEED, timed: Boolean = false) =
        GameSession.create(GameId(kind, Difficulty.forTier(tier)), seed, timed)

    private fun GameViewModel.playing() = assertIs<GamePlayUiState.Playing>(state.value)

    private fun GameViewModel.arithmetic() = playing().round as RoundUi.Arithmetic

    /** Answers the current arithmetic round, right or wrong, by choice or by typing. */
    private fun GameViewModel.answer(correct: Boolean) {
        val round = arithmetic().round
        val choices = round.choices
        if (choices == null) {
            onEvent(GamePlayEvent.AnswerChanged((if (correct) round.answer else round.answer + 1).toString()))
            onEvent(GamePlayEvent.SubmitAnswer)
        } else {
            onEvent(GamePlayEvent.Choose(if (correct) round.answer else choices.first { it != round.answer }))
        }
    }

    @Test
    fun `starts on the start card with a seed from the seed source`() {
        val seeds = FixedSeeds(SEED)
        val handle = TestData.handle(KidUiTier.SPROUT, GameKind.ARITHMETIC)
        val vm = viewModel(handle = handle, seeds = seeds)

        val start = assertIs<GamePlayUiState.Start>(vm.state.value)
        assertEquals(8, start.rounds)
        assertFalse(start.timedAvailable)
        assertEquals(SEED, handle.get<Long>(GameViewModel.SEED_KEY))
        assertEquals(1, seeds.calls)

        // Timed mode is Sapling only.
        vm.onEvent(GamePlayEvent.TimedChanged(true))
        assertFalse(assertIs<GamePlayUiState.Start>(vm.state.value).timed)
        assertTrue(assertIs<GamePlayUiState.Start>(viewModel(KidUiTier.SAPLING).state.value).timedAvailable)
    }

    @Test
    fun `rounds come from the seeded session and advance on every answer`() = runTest {
        val vm = viewModel()
        val expected = session(KidUiTier.SPROUT, GameKind.ARITHMETIC).rounds
        vm.onEvent(GamePlayEvent.Start)

        expected.forEachIndexed { index, round ->
            val playing = vm.playing()
            assertEquals(index + 1, playing.roundNumber)
            assertEquals(8, playing.roundCount)
            assertEquals(round, vm.arithmetic().round)
            vm.answer(correct = index != 3)
        }

        val result = assertIs<GamePlayUiState.Result>(vm.state.value)
        assertEquals(Score(correct = 7, total = 8, perfect = false, timedBonus = false), result.score)
        assertEquals(CreditUi.Credited(Money(7), Money(20), clamped = false), result.credit)
        val gameId = GameId(GameKind.ARITHMETIC, Difficulty.SPROUT)
        assertEquals(listOf(FakeCreditSubmitter.Call(TestData.kid, gameId, result.score)), submitter.calls)
    }

    @Test
    fun `a choice that is not offered is ignored`() {
        val vm = viewModel()
        vm.onEvent(GamePlayEvent.Start)
        val choices = checkNotNull(vm.arithmetic().round.choices)

        vm.onEvent(GamePlayEvent.Choose((0..1_000).first { it !in choices }))

        assertEquals(1, vm.playing().roundNumber)
    }

    @Test
    fun `process death resumes the same round and keeps the outcomes`() {
        val handle = TestData.handle(KidUiTier.SPROUT, GameKind.ARITHMETIC)
        val first = viewModel(handle = handle)
        first.onEvent(GamePlayEvent.Start)
        first.answer(correct = true)
        first.answer(correct = false)

        val seeds = FixedSeeds(OTHER_SEED)
        val restored = viewModel(handle = handle.restored(), seeds = seeds)

        assertEquals(0, seeds.calls, "a resumed play never re-rolls its seed")
        assertEquals(3, restored.playing().roundNumber)
        assertEquals(session(KidUiTier.SPROUT, GameKind.ARITHMETIC).rounds[2], restored.arithmetic().round)
        repeat(6) { restored.answer(correct = true) }
        assertEquals(7, assertIs<GamePlayUiState.Result>(restored.state.value).score.correct)
    }

    @Test
    fun `process death on the start card keeps the seed and the timed choice`() {
        val handle = TestData.handle(KidUiTier.SAPLING, GameKind.ARITHMETIC)
        viewModel(KidUiTier.SAPLING, handle = handle).onEvent(GamePlayEvent.TimedChanged(true))

        val restored = viewModel(KidUiTier.SAPLING, handle = handle.restored(), seeds = FixedSeeds(OTHER_SEED))

        assertTrue(assertIs<GamePlayUiState.Start>(restored.state.value).timed)
        restored.onEvent(GamePlayEvent.Start)
        assertEquals(session(KidUiTier.SAPLING, GameKind.ARITHMETIC).rounds[0], restored.arithmetic().round)
        assertTrue(restored.playing().timed)
    }

    @Test
    fun `sapling types answers, negative ones included`() {
        val vm = viewModel(KidUiTier.SAPLING)
        vm.onEvent(GamePlayEvent.Start)
        val rounds = session(KidUiTier.SAPLING, GameKind.ARITHMETIC).rounds.filterIsInstance<ArithmeticRound>()
        assertTrue(rounds.all { it.choices == null })

        vm.onEvent(GamePlayEvent.AnswerChanged("1a2"))
        assertEquals("12", vm.arithmetic().typed)
        vm.onEvent(GamePlayEvent.ToggleMinus)
        assertEquals("-12", vm.arithmetic().typed)
        vm.onEvent(GamePlayEvent.AnswerChanged("−7"))
        assertEquals("-7", vm.arithmetic().typed)
        vm.onEvent(GamePlayEvent.AnswerChanged("-"))
        vm.onEvent(GamePlayEvent.SubmitAnswer)
        assertEquals(1, vm.playing().roundNumber, "nothing to check yet")

        vm.onEvent(GamePlayEvent.AnswerChanged(rounds[0].answer.toString()))
        vm.onEvent(GamePlayEvent.SubmitAnswer)
        assertEquals(2, vm.playing().roundNumber)
        assertEquals("", vm.arithmetic().typed)
    }

    @Test
    fun `a perfect timed play within every limit earns the timed bonus`() = runTest {
        val vm = viewModel(KidUiTier.SAPLING)
        vm.onEvent(GamePlayEvent.TimedChanged(true))
        vm.onEvent(GamePlayEvent.Start)
        assertEquals(8_000L, vm.playing().timeLimitMs)

        repeat(10) {
            timer.advanceBy(8_000) // the limit itself still counts as in time
            vm.answer(correct = true)
        }

        val score = assertIs<GamePlayUiState.Result>(vm.state.value).score
        assertEquals(Score(correct = 10, total = 10, perfect = true, timedBonus = true), score)
    }

    @Test
    fun `one slow round loses the timed bonus but not the perfect bonus`() = runTest {
        val vm = viewModel(KidUiTier.SAPLING)
        vm.onEvent(GamePlayEvent.TimedChanged(true))
        vm.onEvent(GamePlayEvent.Start)

        repeat(10) { index ->
            timer.advanceBy(if (index == 4) 8_001L else 1_000L)
            vm.answer(correct = true)
        }

        val score = assertIs<GamePlayUiState.Result>(vm.state.value).score
        assertEquals(Score(correct = 10, total = 10, perfect = true, timedBonus = false), score)
    }

    @Test
    fun `timed memory is measured from when the sequence hides`() = runTest {
        val virtual = GameTimer { testScheduler.currentTime }
        val handle = TestData.handle(KidUiTier.SAPLING, GameKind.MEMORY)
        val vm = viewModel(KidUiTier.SAPLING, GameKind.MEMORY, handle = handle, timer = virtual)
        val round = session(KidUiTier.SAPLING, GameKind.MEMORY, timed = true).rounds[0] as MemoryRound
        vm.onEvent(GamePlayEvent.TimedChanged(true))
        vm.onEvent(GamePlayEvent.Start)
        val showing = GameViewModel.SYMBOL_SHOW_MILLIS * round.sequence.size

        advanceTimeBy(showing + 1) // the sequence hides at `showing`
        assertFalse((vm.playing().round as RoundUi.Memory).showing)
        advanceTimeBy(2_999)
        round.sequence.forEach { vm.onEvent(GamePlayEvent.TapSymbol(it)) }

        assertEquals(2, vm.playing().roundNumber)
        assertEquals(3_000L, handle.get<LongArray>(GameViewModel.ELAPSED_KEY)?.single(), "not ${showing + 3_000}")
    }

    @Test
    fun `a resumed timed round keeps its start, and a reboot loses the timed bonus`() {
        val handle = TestData.handle(KidUiTier.SAPLING, GameKind.ARITHMETIC)
        val vm = viewModel(KidUiTier.SAPLING, handle = handle)
        vm.onEvent(GamePlayEvent.TimedChanged(true))
        vm.onEvent(GamePlayEvent.Start)
        timer.advanceBy(5_000)

        val resumedHandle = handle.restored()
        val resumed = viewModel(KidUiTier.SAPLING, handle = resumedHandle)
        timer.advanceBy(2_000)
        resumed.answer(correct = true)
        assertEquals(7_000L, resumedHandle.get<LongArray>(GameViewModel.ELAPSED_KEY)?.single())

        // The monotonic timer restarts at zero on a reboot: that round's time is unknown, so no bonus.
        val rebooted = viewModel(KidUiTier.SAPLING, handle = handle.restored(), timer = FakeGameTimer(now = 0L))
        repeat(10) { rebooted.answer(correct = true) }
        val score = assertIs<GamePlayUiState.Result>(rebooted.state.value).score
        assertTrue(score.perfect)
        assertFalse(score.timedBonus)
    }

    @Test
    fun `memory shows the sequence 700 ms per symbol, then takes taps in order`() = runTest {
        val vm = viewModel(kind = GameKind.MEMORY)
        val round = session(KidUiTier.SPROUT, GameKind.MEMORY).rounds[0] as MemoryRound
        vm.onEvent(GamePlayEvent.Start)

        fun memory() = vm.playing().round as RoundUi.Memory
        assertTrue(memory().showing)
        assertEquals(1, memory().shownCount)
        vm.onEvent(GamePlayEvent.TapSymbol(round.sequence[0]))
        assertEquals(emptyList(), memory().tapped, "taps are ignored while the sequence shows")

        advanceTimeBy(GameViewModel.SYMBOL_SHOW_MILLIS + 1)
        assertEquals(2, memory().shownCount)
        advanceTimeBy(GameViewModel.SYMBOL_SHOW_MILLIS * round.sequence.size)
        assertFalse(memory().showing)

        round.sequence.dropLast(1).forEach { vm.onEvent(GamePlayEvent.TapSymbol(it)) }
        assertEquals(round.sequence.dropLast(1), memory().tapped)
        vm.onEvent(GamePlayEvent.TapSymbol(round.sequence.last()))

        assertEquals(2, vm.playing().roundNumber)
        assertTrue(memory().showing, "the next round shows its own sequence")
    }

    @Test
    fun `a wrong memory tap ends the round as incorrect`() = runTest {
        val vm = viewModel(kind = GameKind.MEMORY)
        val rounds = session(KidUiTier.SPROUT, GameKind.MEMORY).rounds.map { it as MemoryRound }
        vm.onEvent(GamePlayEvent.Start)

        rounds.forEach { round ->
            advanceTimeBy(GameViewModel.SYMBOL_SHOW_MILLIS * round.sequence.size + 1)
            vm.onEvent(GamePlayEvent.TapSymbol(round.sequence[1]))
        }

        assertEquals(0, assertIs<GamePlayUiState.Result>(vm.state.value).score.correct)
    }

    @Test
    fun `a memory round resumed after process death shows its sequence again`() = runTest {
        val handle = TestData.handle(KidUiTier.SAPLING, GameKind.MEMORY)
        val vm = viewModel(KidUiTier.SAPLING, GameKind.MEMORY, handle = handle)
        val round = session(KidUiTier.SAPLING, GameKind.MEMORY).rounds[0] as MemoryRound
        vm.onEvent(GamePlayEvent.Start)
        advanceTimeBy(GameViewModel.SYMBOL_SHOW_MILLIS * round.sequence.size + 1)
        assertEquals(2, round.distractors.size)

        val restored = viewModel(KidUiTier.SAPLING, GameKind.MEMORY, handle = handle.restored())

        val memory = restored.playing().round as RoundUi.Memory
        assertEquals(round, memory.round)
        assertTrue(memory.showing)
    }

    @Test
    fun `word letters are tapped in order, cleared, and checked when complete`() {
        val vm = viewModel(kind = GameKind.WORDS)
        val rounds = session(KidUiTier.SPROUT, GameKind.WORDS).rounds.map { it as WordRound }
        vm.onEvent(GamePlayEvent.Start)
        fun word() = vm.playing().round as RoundUi.Word

        val first = rounds[0]
        val tiles = GameViewModel.tilesFor(first.word, first.scrambled)
        vm.onEvent(GamePlayEvent.TapLetter(tiles[0]))
        vm.onEvent(GamePlayEvent.TapLetter(tiles[0]))
        assertEquals(first.word.take(1), word().answer, "a used tile cannot be tapped twice")
        vm.onEvent(GamePlayEvent.ClearWord)
        assertEquals("", word().answer)
        assertEquals(emptyList(), word().used)

        tiles.forEach { vm.onEvent(GamePlayEvent.TapLetter(it)) }
        assertEquals(2, vm.playing().roundNumber)

        // Spelling the scramble itself is wrong (it never equals the word).
        rounds[1].scrambled.indices.forEach { vm.onEvent(GamePlayEvent.TapLetter(it)) }
        assertEquals(3, vm.playing().roundNumber)
        repeat(6) { index ->
            val round = rounds[index + 2]
            GameViewModel.tilesFor(round.word, round.scrambled).forEach { vm.onEvent(GamePlayEvent.TapLetter(it)) }
        }

        assertEquals(7, assertIs<GamePlayUiState.Result>(vm.state.value).score.correct)
    }

    @Test
    fun `sapling can type the word`() {
        val vm = viewModel(KidUiTier.SAPLING, GameKind.WORDS)
        val round = session(KidUiTier.SAPLING, GameKind.WORDS).rounds[0] as WordRound
        vm.onEvent(GamePlayEvent.Start)

        vm.onEvent(GamePlayEvent.WordTyped(round.word.take(2).uppercase() + "1"))
        val word = vm.playing().round as RoundUi.Word
        assertEquals(round.word.take(2), word.answer)
        assertEquals(GameViewModel.tilesFor(round.word.take(2), round.scrambled), word.used)

        vm.onEvent(GamePlayEvent.WordTyped(round.word))
        assertEquals(2, vm.playing().roundNumber)
    }

    @Test
    fun `cap reached, no account and a clamped credit`() = runTest {
        submitter.respond = { _, _ -> GameCreditResult.CapReached }
        assertEquals(CreditUi.CapReached, playThrough().credit)

        submitter.respond = { _, _ -> GameCreditResult.NoAccount }
        assertEquals(CreditUi.NoAccount, playThrough().credit)

        // A perfect Sprout play earns 8 + 3 = 11; only 4 were left today.
        submitter.respond = { _, _ -> GameCreditResult.Credited(Money(4), Money(0), earned = Money(11)) }
        assertEquals(CreditUi.Credited(Money(4), Money(0), clamped = true), playThrough().credit)

        // In full: earned is what was credited.
        submitter.respond = { _, _ -> GameCreditResult.Credited(Money(11), Money(0)) }
        assertEquals(CreditUi.Credited(Money(11), Money(0), clamped = false), playThrough().credit)
    }

    @Test
    fun `a play that earned nothing is for fun, never a zero credit`() {
        assertEquals(
            CreditUi.NothingEarned,
            GameViewModel.creditOf(GameCreditResult.Credited(Money.ZERO, Money(50), earned = Money.ZERO)),
        )
        assertEquals(
            CreditUi.CapReached,
            GameViewModel.creditOf(GameCreditResult.Credited(Money.ZERO, Money.ZERO, earned = Money.ZERO)),
            "nothing earned with nothing left reads as the cap reached",
        )

        val handle = TestData.handle(KidUiTier.SPROUT, GameKind.ARITHMETIC)
        submitter.respond = { _, _ -> GameCreditResult.Credited(Money.ZERO, Money(50), earned = Money.ZERO) }
        val vm = viewModel(handle = handle)
        vm.onEvent(GamePlayEvent.Start)
        while (vm.state.value is GamePlayUiState.Playing) vm.answer(correct = false)
        assertEquals(CreditUi.NothingEarned, assertIs<GamePlayUiState.Result>(vm.state.value).credit)
        assertEquals(CreditUi.NothingEarned, assertIs<GamePlayUiState.Result>(restore(handle).state.value).credit)
        vm.onEvent(GamePlayEvent.RetryCredit)
        assertEquals(1, submitter.calls.size, "nothing to retry")
    }

    @Test
    fun `a refusal for the phone's date asks to check it and can be retried`() {
        val today = CalendarDate(2026, 10, 1)
        val serverDate = CalendarDate(2026, 11, 4)
        val deviceDate = GameCreditResult.Refused(
            AppendResult.Rejected("device date", listOf(Violation.PlayedOnOutOfRange(today, serverDate))),
        )
        assertEquals(CreditUi.CheckDate, GameViewModel.creditOf(deviceDate))
        assertEquals(CreditUi.Refused, GameViewModel.creditOf(GameCreditResult.Refused(AppendResult.Duplicate)))

        val handle = TestData.handle(KidUiTier.SPROUT, GameKind.ARITHMETIC)
        submitter.respond = { _, _ -> deviceDate }
        val vm = viewModel(handle = handle)
        playThrough(vm)
        assertEquals(CreditUi.CheckDate, assertIs<GamePlayUiState.Result>(vm.state.value).credit)
        assertEquals(CreditUi.CheckDate, assertIs<GamePlayUiState.Result>(restore(handle).state.value).credit)

        submitter.respond = { _, _ -> GameCreditResult.Credited(Money(11), Money(9)) }
        vm.onEvent(GamePlayEvent.RetryCredit)
        val credited = assertIs<GamePlayUiState.Result>(vm.state.value).credit
        assertEquals(CreditUi.Credited(Money(11), Money(9), clamped = false), credited)
        assertEquals(2, submitter.calls.size)
    }

    @Test
    fun `leaving while the credit is saved does not cancel it`() {
        val pending = CompletableDeferred<GameCreditResult>()
        submitter.respond = { _, _ -> pending.await() }
        val store = ViewModelStore()
        val vm = ViewModelProvider(store, viewModelFactory { initializer { viewModel() } })[GameViewModel::class.java]
        playThrough(vm)
        assertEquals(CreditUi.Submitting, assertIs<GamePlayUiState.Result>(vm.state.value).credit)

        store.clear() // back pressed: the entry is popped and its ViewModel cleared mid-submit
        pending.complete(GameCreditResult.Credited(Money(11), Money(39)))

        assertEquals(listOf<GameCreditResult>(GameCreditResult.Credited(Money(11), Money(39))), submitter.completed)
        assertEquals(1, submitter.calls.size)
    }

    @Test
    fun `plays racing for the last of the cap get one in full, one clamped, then for fun`() {
        val gate = CompletableDeferred<Unit>()
        val lock = Mutex()
        var remaining = 15L
        val ledger = FakeCreditSubmitter().apply {
            // Like PlayGameUseCase: plays are serialised, each clamps to what the one before it left.
            respond = { gameId, score ->
                gate.await()
                lock.withLock {
                    val earned = checkNotNull(GameCredit.earned(gameId, score))
                    val amount = minOf(earned, remaining)
                    remaining -= amount
                    if (amount == 0L) {
                        GameCreditResult.CapReached
                    } else {
                        GameCreditResult.Credited(Money(amount), Money(remaining), Money(earned))
                    }
                }
            }
        }
        val plays = List(3) { viewModel(submitter = ledger) }
        plays.forEach { playThrough(it) }
        assertTrue(plays.all { (it.state.value as GamePlayUiState.Result).credit == CreditUi.Submitting })

        gate.complete(Unit)

        val credits = plays.map { assertIs<GamePlayUiState.Result>(it.state.value).credit }
        assertEquals(
            setOf(
                CreditUi.Credited(Money(11), Money(4), clamped = false),
                CreditUi.Credited(Money(4), Money.ZERO, clamped = true),
                CreditUi.CapReached,
            ),
            credits.toSet(),
        )
        assertEquals(0L, remaining)
    }

    @Test
    fun `a refused or failed credit can be retried`() = runTest {
        submitter.respond = { _, _ -> GameCreditResult.Refused(AppendResult.Rejected("rejected")) }
        val vm = viewModel()
        playThrough(vm)
        assertEquals(CreditUi.Refused, assertIs<GamePlayUiState.Result>(vm.state.value).credit)

        submitter.respond = { _, _ -> error("database closed") }
        vm.onEvent(GamePlayEvent.RetryCredit)
        assertEquals(CreditUi.Refused, assertIs<GamePlayUiState.Result>(vm.state.value).credit)

        submitter.respond = { _, _ -> GameCreditResult.Credited(Money(11), Money(9)) }
        vm.onEvent(GamePlayEvent.RetryCredit)
        val credited = assertIs<GamePlayUiState.Result>(vm.state.value).credit
        assertEquals(CreditUi.Credited(Money(11), Money(9), clamped = false), credited)
        assertEquals(3, submitter.calls.size)

        vm.onEvent(GamePlayEvent.RetryCredit)
        assertEquals(3, submitter.calls.size, "a credited play is never retried")
    }

    @Test
    fun `a saved result is restored without submitting again`() = runTest {
        val handle = TestData.handle(KidUiTier.SPROUT, GameKind.ARITHMETIC)
        playThrough(viewModel(handle = handle))
        assertEquals(1, submitter.calls.size)

        val restored = viewModel(handle = handle.restored())

        val result = assertIs<GamePlayUiState.Result>(restored.state.value)
        // A perfect Sprout play earns 8 + 3 = 11, credited in full.
        assertEquals(CreditUi.Credited(Money(11), Money(20), clamped = false), result.credit)
        assertEquals(1, submitter.calls.size)
    }

    @Test
    fun `process death while the credit is saved never submits twice`() = runTest {
        val pending = CompletableDeferred<GameCreditResult>()
        submitter.respond = { _, _ -> pending.await() }
        val handle = TestData.handle(KidUiTier.SPROUT, GameKind.ARITHMETIC)
        val vm = viewModel(handle = handle)
        playThrough(vm)
        assertEquals(CreditUi.Submitting, assertIs<GamePlayUiState.Result>(vm.state.value).credit)

        val restored = viewModel(handle = handle.restored())

        assertEquals(CreditUi.Unknown, assertIs<GamePlayUiState.Result>(restored.state.value).credit)
        assertEquals(1, submitter.calls.size)
        pending.complete(GameCreditResult.CapReached)
    }

    @Test
    fun `play again re-rolls the seed and keeps the timed choice`() = runTest {
        val handle = TestData.handle(KidUiTier.SAPLING, GameKind.ARITHMETIC)
        val vm = viewModel(KidUiTier.SAPLING, handle = handle)
        vm.onEvent(GamePlayEvent.TimedChanged(true))
        playThrough(vm)

        vm.onEvent(GamePlayEvent.PlayAgain)

        assertTrue(assertIs<GamePlayUiState.Start>(vm.state.value).timed)
        assertEquals(OTHER_SEED, handle.get<Long>(GameViewModel.SEED_KEY))
        vm.onEvent(GamePlayEvent.Start)
        val expected = session(KidUiTier.SAPLING, GameKind.ARITHMETIC, seed = OTHER_SEED).rounds[0]
        assertEquals(expected, vm.arithmetic().round)
        assertEquals(1, vm.playing().roundNumber)
    }

    @Test
    fun `word scramble is unavailable at seedling`() {
        val vm = viewModel(KidUiTier.SEEDLING, GameKind.WORDS)

        assertIs<GamePlayUiState.Unavailable>(vm.state.value)
        vm.onEvent(GamePlayEvent.Start)
        assertIs<GamePlayUiState.Unavailable>(vm.state.value)
    }

    private fun restore(handle: SavedStateHandle) = viewModel(handle = handle.restored())

    /** Plays a Sprout arithmetic game, every answer right. */
    private fun playThrough(vm: GameViewModel = viewModel()): GamePlayUiState.Result {
        if (vm.state.value is GamePlayUiState.Start) vm.onEvent(GamePlayEvent.Start)
        while (vm.state.value is GamePlayUiState.Playing) vm.answer(correct = true)
        return assertIs<GamePlayUiState.Result>(vm.state.value)
    }

    private companion object {
        const val SEED = 42L
        const val OTHER_SEED = 7L
    }
}
