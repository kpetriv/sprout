package dev.kpetriv.sprout.feature.games

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kpetriv.sprout.core.domainledger.AppendResult
import dev.kpetriv.sprout.core.domainledger.GameCreditResult
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.domainledger.Violation
import dev.kpetriv.sprout.core.games.ArithmeticRound
import dev.kpetriv.sprout.core.games.Difficulty
import dev.kpetriv.sprout.core.games.GameCatalogue
import dev.kpetriv.sprout.core.games.GameId
import dev.kpetriv.sprout.core.games.GameKind
import dev.kpetriv.sprout.core.games.GameScorer
import dev.kpetriv.sprout.core.games.GameSession
import dev.kpetriv.sprout.core.games.MemoryRound
import dev.kpetriv.sprout.core.games.RoundOutcome
import dev.kpetriv.sprout.core.games.Score
import dev.kpetriv.sprout.core.games.Symbol
import dev.kpetriv.sprout.core.games.TablesLevel
import dev.kpetriv.sprout.core.games.TablesRound
import dev.kpetriv.sprout.core.games.TablesSetup
import dev.kpetriv.sprout.core.games.WordRound
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.KidUiTier
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * One play of a brain game, from the start card through every round to the credited result. Arguments from the
 * [SavedStateHandle]: [KID_ID_KEY], [TIER_KEY] (a [KidUiTier] name) and [KIND_KEY] (a [GameKind] name); the game is
 * the kind at the tier's [Difficulty].
 *
 * The handle also holds the play itself: its [SEED_KEY] (from [GameSeedSource]), [TIMED_KEY], the Times tables
 * setup ([SETUP_TABLES_KEY], [SETUP_LEVEL_KEY]), whether it [STARTED_KEY], the [INDEX_KEY] of the current round and
 * the outcomes so far ([CORRECT_KEY], [ELAPSED_KEY]). The session is regenerated from the seed and the setup, so
 * rotation and process death resume the same round (the same 20 questions), never a re-rolled one; an answer in
 * progress (taps, letters, digits) starts over, and a memory round shows its sequence again.
 *
 * Times tables start from the kid's last setup in [TablesSetupStore] (the difficulty's default until there is one);
 * "Start" saves the chosen one there. The start cards read the kid's past plays from [GameHistorySource]: the last
 * three on Sapling's start card, the best score this week on the Times tables one.
 *
 * When the last round is answered the score goes to [GameCreditSubmitter] once, on the [GameCreditScope]: leaving
 * the screen stops waiting for the credit but never cancels it (M5 review M1). Its result is saved in the handle; if
 * the app stops while the credit is being saved, the restored result says so ([CreditUi.Unknown]) instead of
 * submitting the same play twice. Elapsed time per round comes from the monotonic [GameTimer] (timed mode and its
 * bonus), never the wall clock (M5 audit I2).
 */
@HiltViewModel
@Suppress(
    "TooManyFunctions", // One small handler per kind of answer keeps each rule readable.
    "LongParameterList", // Hilt's constructor: the ports the play needs, each a single seam for tests.
)
class GameViewModel @Inject constructor(
    private val handle: SavedStateHandle,
    private val submitter: GameCreditSubmitter,
    private val seeds: GameSeedSource,
    private val currency: GameCurrencySource,
    private val timer: GameTimer,
    private val creditScope: GameCreditScope,
    private val setups: TablesSetupStore,
    private val history: GameHistorySource,
) : ViewModel() {
    private val kidId = KidId(checkNotNull(handle.get<String>(KID_ID_KEY)) { "kidId argument missing" })
    private val tier = handle.get<String>(TIER_KEY)?.let(KidUiTier::valueOf) ?: KidUiTier.SEEDLING
    private val kind = GameKind.valueOf(checkNotNull(handle.get<String>(KIND_KEY)) { "kind argument missing" })
    private val difficulty = Difficulty.forTier(tier)
    private val gameId: GameId? =
        if (kind in GameCatalogue.forDifficulty(difficulty)) GameId(kind, difficulty) else null

    private val tables = kind == GameKind.TABLES
    private val timedAvailable = gameId?.let(GameCatalogue::supportsTimed) == true

    private val mutableState = MutableStateFlow<GamePlayUiState>(GamePlayUiState.Unavailable(tier, kind))
    val state: StateFlow<GamePlayUiState> = mutableState.asStateFlow()

    private var session: GameSession? = null
    private var reveal: Job? = null
    private var submitting: Job? = null
    private var roundKey = 0L

    /** The household policy's currency as last observed (D-REL-10). */
    private var fiat = currency.fiatCurrency()

    /** The start cards' history as last observed: Sapling's last plays, the Times tables best this week. */
    private var recentPlays: List<GamePlaySummary> = emptyList()
    private var bestThisWeek: Int? = null

    init {
        restore()
        if (gameId != null) observeStartCard()
        viewModelScope.launch {
            currency.observeFiatCurrency().collect { latest ->
                fiat = latest
                mutableState.update { state ->
                    when (state) {
                        is GamePlayUiState.Result -> state.copy(currency = latest)
                        is GamePlayUiState.Start -> state.copy(currency = latest)
                        else -> state
                    }
                }
            }
        }
    }

    @Suppress("CyclomaticComplexMethod") // One branch per event, each delegating to its handler.
    fun onEvent(event: GamePlayEvent) {
        when (event) {
            is GamePlayEvent.TimedChanged -> setTimed(event.timed)
            GamePlayEvent.Start -> start()
            is GamePlayEvent.Choose -> choose(event.value)
            is GamePlayEvent.AnswerChanged -> updateTyped { _, negative -> sanitizeAnswer(event.text, negative) }
            is GamePlayEvent.Digit -> typeDigit(event.digit)
            GamePlayEvent.Backspace -> updateTyped { typed, _ -> typed.dropLast(1) }
            GamePlayEvent.ToggleMinus -> updateArithmetic { it.copy(typed = toggleMinus(it.typed)) }
            is GamePlayEvent.TablesChanged -> changeSetup { it.withTables(event.tables) }
            is GamePlayEvent.LevelChanged -> changeSetup { it.copy(level = event.level) }
            GamePlayEvent.SubmitAnswer -> submitTyped()
            is GamePlayEvent.TapSymbol -> tapSymbol(event.symbol)
            is GamePlayEvent.TapLetter -> tapLetter(event.index)
            is GamePlayEvent.WordTyped -> typeWord(event.text)
            GamePlayEvent.ClearWord -> updateWord { it.copy(answer = "", used = emptyList()) }
            GamePlayEvent.RetryCredit -> retryCredit()
            GamePlayEvent.PlayAgain -> playAgain()
        }
    }

    // region Lifecycle of a play

    private fun restore() {
        val id = gameId ?: return
        val seed = handle.get<Long>(SEED_KEY) ?: seeds.next().also { handle[SEED_KEY] = it }
        val setup = currentSetup()
        val created = GameSession.create(id, seed, chosenTimed(), setup)
        session = created
        if (handle.get<Boolean>(STARTED_KEY) != true) {
            showStart()
            return
        }
        val index = outcomes().size
        if (index >= created.rounds.size) restoreResult(created) else enterRound(created, index, resumed = true)
    }

    /** The Timed switch's choice; Times tables are timed by their level ([TablesLevel.timed]), never the switch. */
    private fun chosenTimed(): Boolean = !tables && timedAvailable && handle.get<Boolean>(TIMED_KEY) == true

    private fun showStart() {
        val id = gameId ?: return
        val setup = currentSetup()
        mutableState.value = GamePlayUiState.Start(
            tier = tier,
            kind = kind,
            rounds = GameCatalogue.rounds(id),
            timedAvailable = timedAvailable,
            timed = setup?.level?.timed ?: chosenTimed(),
            setup = setup,
            history = recentPlays,
            bestThisWeek = bestThisWeek,
            currency = fiat,
        )
    }

    /**
     * Keeps the start card's history current: the Times tables best this week, or Sapling's last plays; and, for
     * Times tables, applies the kid's stored setup unless one was already chosen in this play (the handle has it).
     * A source that fails leaves the card without that line.
     */
    private fun observeStartCard() {
        if (tables) {
            viewModelScope.launch {
                val stored = setups.observe(kidId).catch { }.firstOrNull() ?: return@launch
                if (!handle.contains(SETUP_TABLES_KEY) && mutableState.value is GamePlayUiState.Start) {
                    saveSetup(stored)
                    showStart()
                }
            }
            viewModelScope.launch {
                history.observeBestScoreThisWeek(kidId, kind).catch { }.collect { best ->
                    bestThisWeek = best
                    updateStart { it.copy(bestThisWeek = best) }
                }
            }
        } else if (tier == KidUiTier.SAPLING) {
            viewModelScope.launch {
                history.observeRecent(kidId, kind).catch { }.collect { plays ->
                    recentPlays = plays
                    updateStart { it.copy(history = plays) }
                }
            }
        }
    }

    private fun updateStart(transform: (GamePlayUiState.Start) -> GamePlayUiState.Start) {
        mutableState.update { state -> (state as? GamePlayUiState.Start)?.let(transform) ?: state }
    }

    private fun setTimed(timed: Boolean) {
        val start = mutableState.value as? GamePlayUiState.Start ?: return
        if (!start.timedAvailable || tables) return
        handle[TIMED_KEY] = timed
        mutableState.value = start.copy(timed = timed)
    }

    /** A change to the Times tables setup on the start card; an invalid one (no table) is ignored. */
    private fun changeSetup(transform: (TablesSetup) -> TablesSetup?) {
        if (mutableState.value !is GamePlayUiState.Start) return
        val setup = currentSetup()?.let(transform) ?: return
        saveSetup(setup)
        showStart()
    }

    private fun start() {
        val id = gameId ?: return
        if (mutableState.value !is GamePlayUiState.Start) return
        val seed = checkNotNull(handle.get<Long>(SEED_KEY))
        val setup = currentSetup()
        if (setup != null) {
            // The setup is part of the play from here (process death resumes the same 20 questions), and the next
            // play starts from it.
            saveSetup(setup)
            viewModelScope.launch { attempt { setups.save(kidId, setup) } }
        }
        val created = GameSession.create(id, seed, chosenTimed(), setup)
        session = created
        saveOutcomes(emptyList())
        handle[STARTED_KEY] = true
        enterRound(created, 0, resumed = false)
    }

    /** The Times tables setup of this play: the handle's, else the difficulty's default; null for other games. */
    private fun currentSetup(): TablesSetup? {
        if (!tables) return null
        val chosen = handle.get<IntArray>(SETUP_TABLES_KEY)?.toSet()
        val level = handle.get<String>(SETUP_LEVEL_KEY)?.let { name ->
            TablesLevel.entries.firstOrNull { it.name == name }
        }
        return if (chosen != null && level != null) {
            chosen.withLevel(level) ?: TablesSetup.defaultFor(difficulty)
        } else {
            TablesSetup.defaultFor(difficulty)
        }
    }

    private fun saveSetup(setup: TablesSetup) {
        handle[SETUP_TABLES_KEY] = setup.tables.sorted().toIntArray()
        handle[SETUP_LEVEL_KEY] = setup.level.name
    }

    private fun enterRound(session: GameSession, index: Int, resumed: Boolean) {
        reveal?.cancel()
        roundKey++
        val round = session.rounds[index]
        val ui = when (round) {
            is ArithmeticRound -> RoundUi.Arithmetic(round)
            is MemoryRound -> RoundUi.Memory(round, showing = true, shownCount = 0)
            is WordRound -> RoundUi.Word(round)
            is TablesRound -> RoundUi.Tables(round)
        }
        val outcomes = outcomes().take(index)
        mutableState.value = GamePlayUiState.Playing(
            tier = tier,
            kind = kind,
            roundNumber = index + 1,
            roundCount = session.rounds.size,
            timed = session.timed,
            timeLimitMs = session.timeLimitMs(round),
            round = ui,
            roundKey = roundKey,
            right = outcomes.count { it.correct },
            streak = outcomes.takeLastWhile { it.correct }.size,
            setup = session.setup,
        )
        if (round is MemoryRound) {
            // The clock starts when the sequence hides; a resumed memory round shows its sequence again.
            handle.remove<Long>(ROUND_STARTED_KEY)
            reveal = viewModelScope.launch { revealSequence(round) }
        } else if (!resumed || handle.get<Long>(ROUND_STARTED_KEY) == null) {
            markRoundStart()
        }
    }

    /** Shows the sequence one symbol more every [SYMBOL_SHOW_MILLIS], then hides it and starts the round's clock. */
    private suspend fun revealSequence(round: MemoryRound) {
        for (count in 1..round.sequence.size) {
            updateMemory { it.copy(shownCount = count) }
            delay(SYMBOL_SHOW_MILLIS)
        }
        markRoundStart()
        updateMemory { it.copy(showing = false) }
    }

    private fun markRoundStart() {
        handle[ROUND_STARTED_KEY] = timer.elapsedMillis()
    }

    /**
     * How long the current round has taken. A start later than now means the device rebooted mid-round (the timer
     * restarts at zero), so the time is unknown and counts as over every limit; no start at all counts as zero.
     */
    private fun roundElapsed(): Long {
        val startedAt = handle.get<Long>(ROUND_STARTED_KEY) ?: return 0L
        val now = timer.elapsedMillis()
        return if (now < startedAt) ELAPSED_UNKNOWN else now - startedAt
    }

    /** Records the current round's outcome and moves to the next round or, after the last, the result. */
    private fun record(correct: Boolean) {
        val current = session ?: return
        reveal?.cancel()
        val elapsed = roundElapsed()
        handle.remove<Long>(ROUND_STARTED_KEY)
        val outcomes = outcomes() + RoundOutcome(correct, elapsed)
        saveOutcomes(outcomes)
        if (outcomes.size < current.rounds.size) {
            enterRound(current, outcomes.size, resumed = false)
        } else {
            finish(current, GameScorer.score(current, outcomes))
        }
    }

    private fun finish(session: GameSession, score: Score) {
        mutableState.value = result(score, CreditUi.Submitting)
        submit(session, score)
    }

    private fun submit(session: GameSession, score: Score) {
        if (submitting?.isActive == true) return
        handle[SUBMITTING_KEY] = true
        val gameId = session.gameId
        // On the app's scope and not cancellable: a cleared ViewModel stops waiting, the credit still lands.
        val pending = creditScope.scope.async(NonCancellable) {
            attempt { submitter.submit(kidId, gameId, score) }
                .getOrElse { GameCreditResult.Refused(AppendResult.Rejected(SUBMIT_FAILED)) }
        }
        submitting = viewModelScope.launch {
            val credit = creditOf(pending.await())
            saveCredit(credit)
            handle.remove<Boolean>(SUBMITTING_KEY)
            mutableState.value = result(score, credit)
        }
    }

    private fun restoreResult(session: GameSession) {
        val score = GameScorer.score(session, outcomes())
        val saved = savedCredit()
        when {
            saved != null -> mutableState.value = result(score, saved)
            handle.get<Boolean>(SUBMITTING_KEY) == true -> mutableState.value = result(score, CreditUi.Unknown)
            else -> finish(session, score)
        }
    }

    @Suppress("ReturnCount") // Guard clauses: each early return is a precondition that is not met.
    private fun retryCredit() {
        val current = session ?: return
        val result = mutableState.value as? GamePlayUiState.Result ?: return
        if (!result.credit.retryable) return
        handle.remove<String>(RESULT_KEY)
        mutableState.value = result.copy(credit = CreditUi.Submitting)
        submit(current, result.score)
    }

    private fun playAgain() {
        val result = mutableState.value as? GamePlayUiState.Result ?: return
        if (result.credit == CreditUi.Submitting) return
        PLAY_KEYS.forEach { handle.remove<Any>(it) }
        handle[SEED_KEY] = seeds.next()
        restore()
    }

    private fun result(score: Score, credit: CreditUi) = GamePlayUiState.Result(
        tier = tier,
        kind = kind,
        score = score,
        credit = credit,
        currency = fiat,
    )

    // endregion

    // region Answers

    private fun choose(value: Int) {
        val round = (playing()?.round as? RoundUi.Arithmetic)?.round ?: return
        if (round.choices == null || value !in round.choices.orEmpty()) return
        record(value == round.answer)
    }

    private fun submitTyped() {
        when (val ui = playing()?.round) {
            is RoundUi.Arithmetic -> if (ui.round.choices == null) {
                ui.typed.toIntOrNull()?.let { record(it == ui.round.answer) }
            }

            is RoundUi.Tables -> ui.typed.toIntOrNull()?.let { record(ui.round.accepts(it)) }

            else -> Unit
        }
    }

    private fun typeDigit(digit: Int) {
        if (digit !in 0..MAX_DIGIT) return
        updateTyped { typed, _ ->
            if (typed.count { it.isDigit() } < MAX_ANSWER_DIGITS) "$typed$digit" else typed
        }
    }

    private fun tapSymbol(symbol: Symbol) {
        val ui = playing()?.round as? RoundUi.Memory ?: return
        if (ui.showing || symbol !in ui.round.choices) return
        val expected = ui.round.sequence[ui.tapped.size]
        val tapped = ui.tapped + symbol
        when {
            symbol != expected -> record(correct = false)
            tapped.size == ui.round.sequence.size -> record(correct = true)
            else -> updateMemory { it.copy(tapped = tapped) }
        }
    }

    private fun tapLetter(index: Int) {
        val ui = playing()?.round as? RoundUi.Word ?: return
        if (index !in ui.round.scrambled.indices || index in ui.used) return
        checkWord(ui.copy(answer = ui.answer + ui.round.scrambled[index], used = ui.used + index))
    }

    private fun typeWord(text: String) {
        val ui = playing()?.round as? RoundUi.Word ?: return
        val answer = text.filter { it.isLetter() }.lowercase().take(ui.round.word.length)
        checkWord(ui.copy(answer = answer, used = tilesFor(answer, ui.round.scrambled)))
    }

    /** Ends the round once the answer is as long as the word; otherwise shows the answer so far. */
    private fun checkWord(ui: RoundUi.Word) {
        if (ui.answer.length == ui.round.word.length) {
            record(ui.round.accepts(ui.answer))
        } else {
            updateWord { ui }
        }
    }

    // endregion

    // region State helpers

    private fun playing(): GamePlayUiState.Playing? = mutableState.value as? GamePlayUiState.Playing

    private fun updateRound(transform: (RoundUi) -> RoundUi?) {
        mutableState.update { state ->
            val playing = state as? GamePlayUiState.Playing ?: return@update state
            transform(playing.round)?.let { playing.copy(round = it) } ?: state
        }
    }

    private fun updateArithmetic(transform: (RoundUi.Arithmetic) -> RoundUi.Arithmetic) =
        updateRound { (it as? RoundUi.Arithmetic)?.takeIf { ui -> ui.round.choices == null }?.let(transform) }

    /**
     * Changes the typed answer of a typed round (Sapling arithmetic or Times tables); [transform] gets the answer so
     * far and whether a minus is allowed (arithmetic answers can be negative, times tables never are).
     */
    private fun updateTyped(transform: (typed: String, negative: Boolean) -> String) = updateRound { round ->
        when (round) {
            is RoundUi.Arithmetic -> if (round.round.choices == null) {
                round.copy(typed = transform(round.typed, true))
            } else {
                null
            }

            is RoundUi.Tables -> round.copy(typed = transform(round.typed, false))

            else -> null
        }
    }

    private fun updateMemory(transform: (RoundUi.Memory) -> RoundUi.Memory) =
        updateRound { (it as? RoundUi.Memory)?.let(transform) }

    private fun updateWord(transform: (RoundUi.Word) -> RoundUi.Word) =
        updateRound { (it as? RoundUi.Word)?.let(transform) }

    private fun outcomes(): List<RoundOutcome> {
        val correct = handle.get<BooleanArray>(CORRECT_KEY) ?: BooleanArray(0)
        val elapsed = handle.get<LongArray>(ELAPSED_KEY) ?: LongArray(0)
        val count = minOf(handle.get<Int>(INDEX_KEY) ?: 0, correct.size, elapsed.size)
        return List(count) { RoundOutcome(correct[it], elapsed[it].coerceAtLeast(0)) }
    }

    private fun saveOutcomes(outcomes: List<RoundOutcome>) {
        handle[CORRECT_KEY] = outcomes.map { it.correct }.toBooleanArray()
        handle[ELAPSED_KEY] = outcomes.map { it.elapsedMs }.toLongArray()
        handle[INDEX_KEY] = outcomes.size
    }

    private fun saveCredit(credit: CreditUi) {
        when (credit) {
            is CreditUi.Credited -> {
                handle[RESULT_AMOUNT_KEY] = credit.amount.minorUnits
                handle[RESULT_REMAINING_KEY] = credit.remainingAfter.minorUnits
                handle[RESULT_CLAMPED_KEY] = credit.clamped
                handle[RESULT_KEY] = RESULT_CREDITED
            }

            CreditUi.CapReached -> handle[RESULT_KEY] = RESULT_CAP_REACHED

            CreditUi.NothingEarned -> handle[RESULT_KEY] = RESULT_NOTHING_EARNED

            CreditUi.NoAccount -> handle[RESULT_KEY] = RESULT_NO_ACCOUNT

            CreditUi.Refused -> handle[RESULT_KEY] = RESULT_REFUSED

            CreditUi.CheckDate -> handle[RESULT_KEY] = RESULT_CHECK_DATE

            CreditUi.Submitting, CreditUi.Unknown -> handle.remove<String>(RESULT_KEY)
        }
    }

    private fun savedCredit(): CreditUi? = when (handle.get<String>(RESULT_KEY)) {
        RESULT_CREDITED -> CreditUi.Credited(
            amount = Money(handle.get<Long>(RESULT_AMOUNT_KEY) ?: 0L),
            remainingAfter = Money(handle.get<Long>(RESULT_REMAINING_KEY) ?: 0L),
            clamped = handle.get<Boolean>(RESULT_CLAMPED_KEY) == true,
        )

        RESULT_CAP_REACHED -> CreditUi.CapReached

        RESULT_NOTHING_EARNED -> CreditUi.NothingEarned

        RESULT_NO_ACCOUNT -> CreditUi.NoAccount

        RESULT_REFUSED -> CreditUi.Refused

        RESULT_CHECK_DATE -> CreditUi.CheckDate

        else -> null
    }

    // endregion

    companion object {
        /** [SavedStateHandle] arguments: the kid id string, the [KidUiTier] name and the [GameKind] name. */
        const val KID_ID_KEY: String = "kidId"
        const val TIER_KEY: String = "tier"
        const val KIND_KEY: String = "kind"

        /** [SavedStateHandle] keys of the play: seed, timed mode, started, round index and outcomes so far. */
        const val SEED_KEY: String = "play.seed"
        const val TIMED_KEY: String = "play.timed"

        /** The Times tables setup of the play: the tables (an `IntArray`) and the [TablesLevel] name. */
        const val SETUP_TABLES_KEY: String = "play.setup.tables"
        const val SETUP_LEVEL_KEY: String = "play.setup.level"
        const val STARTED_KEY: String = "play.started"
        const val INDEX_KEY: String = "play.roundIndex"
        const val CORRECT_KEY: String = "play.correct"
        const val ELAPSED_KEY: String = "play.elapsedMs"

        /** When the current round started taking answers ([GameTimer] milliseconds, monotonic). */
        const val ROUND_STARTED_KEY: String = "play.roundStartedAt"

        /** Set while the credit is being saved; with no [RESULT_KEY] on restore, the outcome is unknown. */
        const val SUBMITTING_KEY: String = "play.submitting"

        /** The saved credit result: its kind and, when credited, the amounts in minor units. */
        const val RESULT_KEY: String = "play.result"
        const val RESULT_AMOUNT_KEY: String = "play.result.amount"
        const val RESULT_REMAINING_KEY: String = "play.result.remaining"
        const val RESULT_CLAMPED_KEY: String = "play.result.clamped"

        /** How long each memory symbol is shown before the next one appears. */
        const val SYMBOL_SHOW_MILLIS: Long = 700L

        /** Everything "Play again" forgets; the seed is replaced and the timed choice and the setup are kept. */
        private val PLAY_KEYS = listOf(
            STARTED_KEY,
            INDEX_KEY,
            CORRECT_KEY,
            ELAPSED_KEY,
            ROUND_STARTED_KEY,
            SUBMITTING_KEY,
            RESULT_KEY,
            RESULT_AMOUNT_KEY,
            RESULT_REMAINING_KEY,
            RESULT_CLAMPED_KEY,
        )

        private const val RESULT_CREDITED = "credited"
        private const val RESULT_CAP_REACHED = "capReached"
        private const val RESULT_NOTHING_EARNED = "nothingEarned"
        private const val RESULT_NO_ACCOUNT = "noAccount"
        private const val RESULT_REFUSED = "refused"
        private const val RESULT_CHECK_DATE = "checkDate"
        private const val SUBMIT_FAILED = "submit failed"
        private const val MAX_ANSWER_DIGITS = 5
        private const val MAX_DIGIT = 9
        private const val MINUS = '-'

        /** A round whose time is unknown (the device rebooted mid-round): over every limit, so no timed bonus. */
        private const val ELAPSED_UNKNOWN = Long.MAX_VALUE

        /**
         * Digits with, when [allowMinus], an optional leading minus (also accepts U+2212), at most
         * [MAX_ANSWER_DIGITS] digits.
         */
        internal fun sanitizeAnswer(text: String, allowMinus: Boolean = true): String {
            val negative = allowMinus && text.trimStart().firstOrNull()?.let { it == MINUS || it == '−' } == true
            val digits = text.filter { it.isDigit() }.take(MAX_ANSWER_DIGITS)
            return if (negative) "$MINUS$digits" else digits
        }

        internal fun toggleMinus(text: String): String = if (text.startsWith(MINUS)) text.drop(1) else "$MINUS$text"

        /** These tables at [level], or null when they are not a valid choice (none, or one outside 2 to 12). */
        internal fun Set<Int>.withLevel(level: TablesLevel): TablesSetup? =
            takeIf { isNotEmpty() && all { it in TablesSetup.TABLES } }?.let { TablesSetup(it, level) }

        private fun TablesSetup.withTables(tables: Set<Int>): TablesSetup? = tables.withLevel(level)

        /** The scrambled tiles [answer] uses, matched left to right to the first unused tile with that letter. */
        internal fun tilesFor(answer: String, scrambled: String): List<Int> {
            val used = mutableListOf<Int>()
            answer.forEach { letter ->
                val tile = scrambled.indices.firstOrNull { it !in used && scrambled[it].equals(letter, true) }
                if (tile != null) used += tile
            }
            return used
        }

        /**
         * The result screen's view of [result] (the mapping in [GameCreditSubmitter]'s KDoc): a credit is
         * [CreditUi.Credited.clamped] when it is less than the play earned; a zero credit was a play "for fun"; a
         * refusal for the played-on date asks to check the phone's date.
         */
        internal fun creditOf(result: GameCreditResult): CreditUi = when (result) {
            is GameCreditResult.Credited -> when {
                result.amount.isPositive -> CreditUi.Credited(
                    amount = result.amount,
                    remainingAfter = result.remainingAfter,
                    clamped = result.amount < result.earned,
                )

                result.remainingAfter.isPositive -> CreditUi.NothingEarned

                else -> CreditUi.CapReached
            }

            GameCreditResult.CapReached -> CreditUi.CapReached

            GameCreditResult.NoAccount -> CreditUi.NoAccount

            is GameCreditResult.Refused -> if (result.isDeviceDate()) CreditUi.CheckDate else CreditUi.Refused
        }

        /** Refused for the played-on date: the device's date is outside the household's window (D-M5-13). */
        private fun GameCreditResult.Refused.isDeviceDate(): Boolean =
            (result as? AppendResult.Rejected)?.violations.orEmpty().any { it is Violation.PlayedOnOutOfRange }
    }
}
