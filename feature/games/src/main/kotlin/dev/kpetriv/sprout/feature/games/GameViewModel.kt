package dev.kpetriv.sprout.feature.games

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.games.ArithmeticRound
import dev.kpetriv.sprout.core.games.Difficulty
import dev.kpetriv.sprout.core.games.GameCatalogue
import dev.kpetriv.sprout.core.games.GameCredit
import dev.kpetriv.sprout.core.games.GameId
import dev.kpetriv.sprout.core.games.GameKind
import dev.kpetriv.sprout.core.games.GameScorer
import dev.kpetriv.sprout.core.games.GameSession
import dev.kpetriv.sprout.core.games.MemoryRound
import dev.kpetriv.sprout.core.games.RoundOutcome
import dev.kpetriv.sprout.core.games.Score
import dev.kpetriv.sprout.core.games.Symbol
import dev.kpetriv.sprout.core.games.WordRound
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.time.Clock
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * One play of a brain game, from the start card through every round to the credited result. Arguments from the
 * [SavedStateHandle]: [KID_ID_KEY], [TIER_KEY] (a [KidUiTier] name) and [KIND_KEY] (a [GameKind] name); the game is
 * the kind at the tier's [Difficulty].
 *
 * The handle also holds the play itself: its [SEED_KEY] (from [GameSeedSource]), [TIMED_KEY], whether it
 * [STARTED_KEY], the [INDEX_KEY] of the current round and the outcomes so far ([CORRECT_KEY], [ELAPSED_KEY]). The
 * session is regenerated from the seed, so rotation and process death resume the same round, never a re-rolled one;
 * an answer in progress (taps, letters) starts over, and a memory round shows its sequence again.
 *
 * When the last round is answered the score goes to [GameCreditSubmitter] once. Its result is saved in the handle;
 * if the app stops while the credit is being saved, the restored result says so ([CreditUi.Unknown]) instead of
 * submitting the same play twice. Elapsed time per round comes from [Clock] (timed mode and its bonus).
 */
@HiltViewModel
@Suppress("TooManyFunctions") // One small handler per kind of answer keeps each rule readable.
class GameViewModel @Inject constructor(
    private val handle: SavedStateHandle,
    private val submitter: GameCreditSubmitter,
    private val seeds: GameSeedSource,
    private val currency: GameCurrencySource,
    private val clock: Clock,
) : ViewModel() {
    private val kidId = KidId(checkNotNull(handle.get<String>(KID_ID_KEY)) { "kidId argument missing" })
    private val tier = handle.get<String>(TIER_KEY)?.let(KidUiTier::valueOf) ?: KidUiTier.SEEDLING
    private val kind = GameKind.valueOf(checkNotNull(handle.get<String>(KIND_KEY)) { "kind argument missing" })
    private val difficulty = Difficulty.forTier(tier)
    private val gameId: GameId? =
        if (kind in GameCatalogue.forDifficulty(difficulty)) GameId(kind, difficulty) else null
    private val timedAvailable = GameCatalogue.supportsTimed(difficulty)

    private val mutableState = MutableStateFlow<GamePlayUiState>(GamePlayUiState.Unavailable(tier, kind))
    val state: StateFlow<GamePlayUiState> = mutableState.asStateFlow()

    private var session: GameSession? = null
    private var reveal: Job? = null
    private var submitting: Job? = null
    private var roundKey = 0L

    init {
        restore()
    }

    fun onEvent(event: GamePlayEvent) {
        when (event) {
            is GamePlayEvent.TimedChanged -> setTimed(event.timed)
            GamePlayEvent.Start -> start()
            is GamePlayEvent.Choose -> choose(event.value)
            is GamePlayEvent.AnswerChanged -> updateArithmetic { it.copy(typed = sanitizeAnswer(event.text)) }
            GamePlayEvent.ToggleMinus -> updateArithmetic { it.copy(typed = toggleMinus(it.typed)) }
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
        val timed = timedAvailable && handle.get<Boolean>(TIMED_KEY) == true
        val created = GameSession.create(id, seed, timed)
        session = created
        if (handle.get<Boolean>(STARTED_KEY) != true) {
            showStart(timed)
            return
        }
        val index = outcomes().size
        if (index >= created.rounds.size) restoreResult(created) else enterRound(created, index, resumed = true)
    }

    private fun showStart(timed: Boolean) {
        val id = gameId ?: return
        mutableState.value = GamePlayUiState.Start(
            tier = tier,
            kind = kind,
            rounds = GameCatalogue.rounds(id),
            timedAvailable = timedAvailable,
            timed = timed,
        )
    }

    private fun setTimed(timed: Boolean) {
        val start = mutableState.value as? GamePlayUiState.Start ?: return
        if (!start.timedAvailable) return
        handle[TIMED_KEY] = timed
        mutableState.value = start.copy(timed = timed)
    }

    private fun start() {
        val id = gameId ?: return
        val start = mutableState.value as? GamePlayUiState.Start ?: return
        val seed = checkNotNull(handle.get<Long>(SEED_KEY))
        val created = GameSession.create(id, seed, start.timed)
        session = created
        saveOutcomes(emptyList())
        handle[STARTED_KEY] = true
        enterRound(created, 0, resumed = false)
    }

    private fun enterRound(session: GameSession, index: Int, resumed: Boolean) {
        reveal?.cancel()
        roundKey++
        val round = session.rounds[index]
        val ui = when (round) {
            is ArithmeticRound -> RoundUi.Arithmetic(round)
            is MemoryRound -> RoundUi.Memory(round, showing = true, shownCount = 0)
            is WordRound -> RoundUi.Word(round)
        }
        mutableState.value = GamePlayUiState.Playing(
            tier = tier,
            kind = kind,
            roundNumber = index + 1,
            roundCount = session.rounds.size,
            timed = session.timed,
            timeLimitMs = session.timeLimitMs(round),
            round = ui,
            roundKey = roundKey,
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
        handle[ROUND_STARTED_KEY] = clock.now().toEpochMilliseconds()
    }

    /** Records the current round's outcome and moves to the next round or, after the last, the result. */
    private fun record(correct: Boolean) {
        val current = session ?: return
        reveal?.cancel()
        val startedAt = handle.get<Long>(ROUND_STARTED_KEY)
        val elapsed = if (startedAt == null) 0L else (clock.now().toEpochMilliseconds() - startedAt).coerceAtLeast(0)
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
        submitting = viewModelScope.launch {
            val result = attempt { submitter.submit(kidId, session.gameId, score) }
                .getOrElse { GameCreditResult.Refused(SUBMIT_FAILED) }
            val credit = creditOf(session.gameId, score, result)
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
        if (result.credit != CreditUi.Refused) return
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
        currency = currency.fiatCurrency(),
    )

    // endregion

    // region Answers

    private fun choose(value: Int) {
        val round = (playing()?.round as? RoundUi.Arithmetic)?.round ?: return
        if (round.choices == null || value !in round.choices.orEmpty()) return
        record(value == round.answer)
    }

    @Suppress("ReturnCount") // Guard clauses: each early return is a precondition that is not met.
    private fun submitTyped() {
        val ui = playing()?.round as? RoundUi.Arithmetic ?: return
        if (ui.round.choices != null) return
        val value = ui.typed.toIntOrNull() ?: return
        record(value == ui.round.answer)
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
            record(ui.answer.equals(ui.round.word, ignoreCase = true))
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

            CreditUi.NoAccount -> handle[RESULT_KEY] = RESULT_NO_ACCOUNT

            CreditUi.Refused -> handle[RESULT_KEY] = RESULT_REFUSED

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

        RESULT_NO_ACCOUNT -> CreditUi.NoAccount

        RESULT_REFUSED -> CreditUi.Refused

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
        const val STARTED_KEY: String = "play.started"
        const val INDEX_KEY: String = "play.roundIndex"
        const val CORRECT_KEY: String = "play.correct"
        const val ELAPSED_KEY: String = "play.elapsedMs"

        /** When the current round started taking answers (epoch milliseconds). */
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

        /** Everything "Play again" forgets; the seed is replaced and the timed choice is kept. */
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
        private const val RESULT_NO_ACCOUNT = "noAccount"
        private const val RESULT_REFUSED = "refused"
        private const val SUBMIT_FAILED = "submit failed"
        private const val MAX_ANSWER_DIGITS = 5
        private const val MINUS = '-'

        /** Digits with an optional leading minus (also accepts U+2212), at most [MAX_ANSWER_DIGITS] digits. */
        internal fun sanitizeAnswer(text: String): String {
            val negative = text.trimStart().firstOrNull()?.let { it == MINUS || it == '−' } == true
            val digits = text.filter { it.isDigit() }.take(MAX_ANSWER_DIGITS)
            return if (negative) "$MINUS$digits" else digits
        }

        internal fun toggleMinus(text: String): String = if (text.startsWith(MINUS)) text.drop(1) else "$MINUS$text"

        /** The scrambled tiles [answer] uses, matched left to right to the first unused tile with that letter. */
        internal fun tilesFor(answer: String, scrambled: String): List<Int> {
            val used = mutableListOf<Int>()
            answer.forEach { letter ->
                val tile = scrambled.indices.firstOrNull { it !in used && scrambled[it].equals(letter, true) }
                if (tile != null) used += tile
            }
            return used
        }

        /** The result screen's view of [result]: a credit is [CreditUi.Credited.clamped] below what [score] earned. */
        internal fun creditOf(gameId: GameId, score: Score, result: GameCreditResult): CreditUi = when (result) {
            is GameCreditResult.Credited -> CreditUi.Credited(
                amount = result.amount,
                remainingAfter = result.remainingAfter,
                clamped = result.amount.minorUnits < (GameCredit.earned(gameId, score) ?: 0L),
            )

            GameCreditResult.CapReached -> CreditUi.CapReached

            GameCreditResult.NoAccount -> CreditUi.NoAccount

            is GameCreditResult.Refused -> CreditUi.Refused
        }
    }
}
