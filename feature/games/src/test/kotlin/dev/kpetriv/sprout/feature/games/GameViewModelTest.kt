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
import dev.kpetriv.sprout.core.games.TablesLevel
import dev.kpetriv.sprout.core.games.TablesSetup
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

@Suppress("LargeClass") // One play's whole lifecycle: start card, every game, credit and restore.
class GameViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val submitter = FakeCreditSubmitter()
    private val timer = FakeGameTimer()
    private val setups = FakeTablesSetupStore()
    private val history = FakeHistorySource()

    private fun viewModel(
        tier: KidUiTier = KidUiTier.SPROUT,
        kind: GameKind = GameKind.ARITHMETIC,
        handle: SavedStateHandle = TestData.handle(tier, kind),
        seeds: GameSeedSource = FixedSeeds(SEED, OTHER_SEED),
        submitter: GameCreditSubmitter = this.submitter,
        timer: GameTimer = this.timer,
        setups: TablesSetupStore = this.setups,
    ) = GameViewModel(
        handle = handle,
        submitter = submitter,
        seeds = seeds,
        currency = { TestData.gbp },
        timer = timer,
        creditScope = creditScope(main.dispatcher),
        setups = setups,
        history = history,
    )

    private fun session(
        tier: KidUiTier,
        kind: GameKind,
        seed: Long = SEED,
        timed: Boolean = false,
        setup: TablesSetup? = if (kind == GameKind.TABLES) TablesSetup.defaultFor(Difficulty.forTier(tier)) else null,
    ) = GameSession.create(GameId(kind, Difficulty.forTier(tier)), seed, timed, setup)

    private fun GameViewModel.playing() = assertIs<GamePlayUiState.Playing>(state.value)

    private fun GameViewModel.arithmetic() = playing().round as RoundUi.Arithmetic

    private fun GameViewModel.tables() = playing().round as RoundUi.Tables

    private fun GameViewModel.start() = assertIs<GamePlayUiState.Start>(state.value)

    /** Answers the current Times tables question on the number pad, right or wrong, then "Go". */
    private fun GameViewModel.answerTables(correct: Boolean) {
        val round = tables().round
        val value = if (correct) round.answer else round.answer + 1
        value.toString().forEach { onEvent(GamePlayEvent.Digit(it.digitToInt())) }
        onEvent(GamePlayEvent.SubmitAnswer)
    }

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
    fun `times tables start from the default setup, the level decides the timing, not the switch`() {
        for (tier in listOf(KidUiTier.SPROUT, KidUiTier.SAPLING)) {
            val vm = viewModel(tier, GameKind.TABLES)
            val start = vm.start()
            assertEquals(20, start.rounds)
            assertEquals(TablesSetup.defaultFor(Difficulty.forTier(tier)), start.setup, "$tier")
            assertTrue(start.timedAvailable, "timing is available, decided by the level")
            assertFalse(start.timed)
            vm.onEvent(GamePlayEvent.TimedChanged(true))
            assertFalse(vm.start().timed, "the Timed switch does nothing for Times tables")
        }
        assertEquals(null, viewModel(KidUiTier.SPROUT).start().setup, "other games have no setup")
    }

    @Test
    fun `times tables take the stored setup, save the chosen one on start and play twenty typed questions`() {
        setups.setups.value = mapOf(TestData.kid to TablesSetup(setOf(3, 4, 6), TablesLevel.STEADY))
        val vm = viewModel(KidUiTier.SPROUT, GameKind.TABLES)
        assertEquals(TablesSetup(setOf(3, 4, 6), TablesLevel.STEADY), vm.start().setup)

        vm.onEvent(GamePlayEvent.TablesChanged(setOf(3, 4)))
        vm.onEvent(GamePlayEvent.LevelChanged(TablesLevel.GENTLE))
        vm.onEvent(GamePlayEvent.TablesChanged(emptySet()))
        vm.onEvent(GamePlayEvent.TablesChanged(setOf(1, 3)))
        val chosen = TablesSetup(setOf(3, 4), TablesLevel.GENTLE)
        assertEquals(chosen, vm.start().setup, "no table, or one outside 2 to 12, is ignored")
        assertTrue(setups.saved.isEmpty(), "nothing is saved before Start")

        vm.onEvent(GamePlayEvent.Start)
        assertEquals(listOf(TestData.kid to chosen), setups.saved)
        val expected = session(KidUiTier.SPROUT, GameKind.TABLES, setup = chosen).rounds
        val playing = vm.playing()
        assertEquals(chosen, playing.setup)
        assertFalse(playing.timed, "Gentle is not timed")
        assertEquals(20_000L, playing.timeLimitMs)

        expected.forEachIndexed { index, round ->
            assertEquals(round, vm.tables().round)
            vm.answerTables(correct = index != 5)
        }

        val score = assertIs<GamePlayUiState.Result>(vm.state.value).score
        assertEquals(Score(correct = 19, total = 20, perfect = false, timedBonus = false), score)
        assertEquals(GameId(GameKind.TABLES, Difficulty.SPROUT), submitter.calls.single().gameId)
    }

    @Test
    fun `every table picked by hand, then one deselected, leaves ten`() {
        val vm = viewModel(KidUiTier.SAPLING, GameKind.TABLES)
        TablesSetup.TABLES.forEach { table ->
            val current = checkNotNull(vm.start().setup).tables
            if (table !in current) vm.onEvent(GamePlayEvent.TablesChanged(TablesChoice.toggle(current, table)))
        }
        assertTrue(checkNotNull(vm.start().setup).isMix, "all eleven by hand is Mix")

        vm.onEvent(GamePlayEvent.TablesChanged(TablesChoice.toggle(checkNotNull(vm.start().setup).tables, 7)))

        assertEquals(TablesSetup.TABLES.toSet() - 7, checkNotNull(vm.start().setup).tables)
        assertEquals(setOf(4), TablesChoice.toggle(setOf(4), 4), "the last table stays")
    }

    @Test
    fun `a broken streak stays broken after process death`() {
        val handle = TestData.handle(KidUiTier.SAPLING, GameKind.TABLES)
        val first = viewModel(KidUiTier.SAPLING, GameKind.TABLES, handle = handle)
        first.onEvent(GamePlayEvent.Start)
        repeat(3) { first.answerTables(correct = true) }
        first.answerTables(correct = false)
        first.answerTables(correct = true)

        val restored = viewModel(KidUiTier.SAPLING, GameKind.TABLES, handle = handle.restored())

        assertEquals(6, restored.playing().roundNumber)
        assertEquals(4 to 1, restored.playing().let { it.right to it.streak })
    }

    @Test
    fun `right and streak count this play's answers`() {
        val vm = viewModel(KidUiTier.SAPLING, GameKind.TABLES)
        vm.onEvent(GamePlayEvent.Start)
        assertEquals(0 to 0, vm.playing().let { it.right to it.streak })

        repeat(4) { vm.answerTables(correct = true) }
        assertEquals(4 to 4, vm.playing().let { it.right to it.streak })
        vm.answerTables(correct = false)
        assertEquals(4 to 0, vm.playing().let { it.right to it.streak })
        repeat(2) { vm.answerTables(correct = true) }
        assertEquals(6 to 2, vm.playing().let { it.right to it.streak })
    }

    @Test
    fun `the number pad types digits, deletes them and checks the answer`() {
        val vm = viewModel(KidUiTier.SPROUT, GameKind.TABLES)
        vm.onEvent(GamePlayEvent.Start)

        vm.onEvent(GamePlayEvent.SubmitAnswer)
        assertEquals(1, vm.playing().roundNumber, "nothing typed, nothing to check")
        vm.onEvent(GamePlayEvent.Digit(4))
        vm.onEvent(GamePlayEvent.Digit(2))
        vm.onEvent(GamePlayEvent.Digit(10))
        assertEquals("42", vm.tables().typed)
        vm.onEvent(GamePlayEvent.Backspace)
        assertEquals("4", vm.tables().typed)
        vm.onEvent(GamePlayEvent.ToggleMinus)
        vm.onEvent(GamePlayEvent.AnswerChanged("-12a"))
        assertEquals("12", vm.tables().typed, "a times-table answer is never negative")
        repeat(6) { vm.onEvent(GamePlayEvent.Digit(9)) }
        assertEquals("12999", vm.tables().typed, "at most five digits")

        vm.onEvent(GamePlayEvent.SubmitAnswer)
        assertEquals(2, vm.playing().roundNumber)
        assertEquals("", vm.tables().typed)
        assertEquals(0, vm.playing().right)
    }

    @Test
    fun `sapling arithmetic types on the number pad too`() {
        val vm = viewModel(KidUiTier.SAPLING)
        vm.onEvent(GamePlayEvent.Start)
        val answer = vm.arithmetic().round.answer

        vm.onEvent(GamePlayEvent.ToggleMinus)
        vm.onEvent(GamePlayEvent.Digit(7))
        assertEquals("-7", vm.arithmetic().typed)
        vm.onEvent(GamePlayEvent.Backspace)
        vm.onEvent(GamePlayEvent.Backspace)
        assertEquals("", vm.arithmetic().typed)
        if (answer < 0) vm.onEvent(GamePlayEvent.ToggleMinus)
        kotlin.math.abs(answer).toString().forEach { vm.onEvent(GamePlayEvent.Digit(it.digitToInt())) }
        vm.onEvent(GamePlayEvent.SubmitAnswer)

        assertEquals(2, vm.playing().roundNumber)
        assertEquals(1 to 1, vm.playing().let { it.right to it.streak })
    }

    @Test
    fun `sprout's number choices ignore the pad`() {
        val vm = viewModel()
        vm.onEvent(GamePlayEvent.Start)
        vm.onEvent(GamePlayEvent.Digit(3))
        vm.onEvent(GamePlayEvent.SubmitAnswer)
        assertEquals("", vm.arithmetic().typed)
        assertEquals(1, vm.playing().roundNumber)
    }

    @Test
    fun `rotation and process death keep the setup and resume the same twenty questions`() {
        val handle = TestData.handle(KidUiTier.SPROUT, GameKind.TABLES)
        val first = viewModel(KidUiTier.SPROUT, GameKind.TABLES, handle = handle)
        first.onEvent(GamePlayEvent.TablesChanged(setOf(7, 8)))
        first.onEvent(GamePlayEvent.LevelChanged(TablesLevel.STEADY))
        val chosen = TablesSetup(setOf(7, 8), TablesLevel.STEADY)

        // On the start card: the handle's choice wins over the store's (the store still has the old one).
        setups.setups.value = mapOf(TestData.kid to TablesSetup(setOf(2), TablesLevel.GENTLE))
        val onStart = tablesViewModel(handle.restored())
        assertEquals(chosen, onStart.start().setup)

        first.onEvent(GamePlayEvent.Start)
        repeat(3) { first.answerTables(correct = true) }
        val restored = tablesViewModel(handle.restored())

        val expected = session(KidUiTier.SPROUT, GameKind.TABLES, setup = chosen).rounds
        assertEquals(4, restored.playing().roundNumber)
        assertEquals(expected[3], restored.tables().round)
        assertEquals(chosen, restored.playing().setup)
        assertEquals(3 to 3, restored.playing().let { it.right to it.streak })
    }

    @Test
    fun `rock star is timed and earns the timed bonus, gentle never does`() {
        val rockStar = viewModel(KidUiTier.SAPLING, GameKind.TABLES)
        rockStar.onEvent(GamePlayEvent.LevelChanged(TablesLevel.ROCK_STAR))
        assertTrue(rockStar.start().timed)
        rockStar.onEvent(GamePlayEvent.Start)
        assertTrue(rockStar.playing().timed)
        assertEquals(5_000L, rockStar.playing().timeLimitMs)
        repeat(20) {
            timer.advanceBy(5_000)
            rockStar.answerTables(correct = true)
        }
        val fast = assertIs<GamePlayUiState.Result>(rockStar.state.value).score
        assertEquals(Score(correct = 20, total = 20, perfect = true, timedBonus = true), fast)

        val gentle = viewModel(KidUiTier.SAPLING, GameKind.TABLES)
        gentle.onEvent(GamePlayEvent.LevelChanged(TablesLevel.GENTLE))
        gentle.onEvent(GamePlayEvent.Start)
        assertFalse(gentle.playing().timed)
        repeat(20) {
            timer.advanceBy(1_000)
            gentle.answerTables(correct = true)
        }
        val untimed = assertIs<GamePlayUiState.Result>(gentle.state.value).score
        assertEquals(Score(correct = 20, total = 20, perfect = true, timedBonus = false), untimed)
        val credit = assertIs<CreditUi.Credited>(assertIs<GamePlayUiState.Result>(gentle.state.value).credit)
        assertEquals(15L, credit.amount.minorUnits, "20 right and perfect is capped at 15 at every level")
    }

    @Test
    fun `the start cards show the history, sapling's last plays, the times tables best this week`() {
        val numbers = GameId(GameKind.ARITHMETIC, Difficulty.SAPLING)
        val plays = listOf(
            GamePlaySummary(CalendarDate(2026, 11, 12), numbers, 9, Money(11)),
            GamePlaySummary(CalendarDate(2026, 11, 11), numbers, 10, Money(15)),
        )
        history.recent.value = plays
        history.best.value = 18

        val sapling = viewModel(KidUiTier.SAPLING)
        assertEquals(plays, sapling.start().history)
        assertEquals(TestData.gbp, sapling.start().currency)
        assertEquals(emptyList(), viewModel(KidUiTier.SPROUT).start().history, "Sprout's card has no history")

        val tables = viewModel(KidUiTier.SPROUT, GameKind.TABLES)
        assertEquals(18, tables.start().bestThisWeek)
        history.best.value = 19
        assertEquals(19, tables.start().bestThisWeek)
        history.best.value = null
        assertEquals(null, tables.start().bestThisWeek)
    }

    @Test
    fun `an unreadable setup store starts from the default`() {
        setups.fail = true
        val vm = viewModel(KidUiTier.SAPLING, GameKind.TABLES)
        assertEquals(TablesSetup.defaultFor(Difficulty.SAPLING), vm.start().setup)
    }

    @Test
    fun `play again keeps the times tables setup`() {
        val vm = viewModel(KidUiTier.SPROUT, GameKind.TABLES)
        vm.onEvent(GamePlayEvent.TablesChanged(setOf(9)))
        vm.onEvent(GamePlayEvent.Start)
        repeat(20) { vm.answerTables(correct = true) }

        vm.onEvent(GamePlayEvent.PlayAgain)

        assertEquals(TablesSetup(setOf(9), TablesLevel.GENTLE), vm.start().setup)
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

    /** A Sprout Times tables play restored from [handle], with a seed source that must not be asked. */
    private fun tablesViewModel(handle: SavedStateHandle) =
        viewModel(KidUiTier.SPROUT, GameKind.TABLES, handle = handle, seeds = FixedSeeds(OTHER_SEED))

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
