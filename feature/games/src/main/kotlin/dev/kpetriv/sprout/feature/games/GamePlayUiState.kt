package dev.kpetriv.sprout.feature.games

import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.games.ArithmeticRound
import dev.kpetriv.sprout.core.games.GameKind
import dev.kpetriv.sprout.core.games.MemoryRound
import dev.kpetriv.sprout.core.games.Score
import dev.kpetriv.sprout.core.games.Symbol
import dev.kpetriv.sprout.core.games.TablesLevel
import dev.kpetriv.sprout.core.games.TablesRound
import dev.kpetriv.sprout.core.games.TablesSetup
import dev.kpetriv.sprout.core.games.WordRound
import dev.kpetriv.sprout.core.model.KidUiTier

/** One play of a game: the start card, a round, or the result. */
sealed interface GamePlayUiState {
    val tier: KidUiTier
    val kind: GameKind

    /**
     * Before the first round: how many [rounds] the play has, and (Sapling only, [timedAvailable]) the "Timed" switch.
     * Times tables show their [setup] instead (which tables, which level; the level decides [timed], so the switch is
     * never shown for them) with the kid's [bestThisWeek] score from the ledger. Sapling's other games list the
     * [history] of their last plays ("Your last plays", at most three), their amounts in [currency].
     */
    data class Start(
        override val tier: KidUiTier,
        override val kind: GameKind,
        val rounds: Int,
        val timedAvailable: Boolean,
        val timed: Boolean,
        val setup: TablesSetup? = null,
        val history: List<GamePlaySummary> = emptyList(),
        val bestThisWeek: Int? = null,
        val currency: Currency.Fiat? = null,
    ) : GamePlayUiState

    /**
     * Round [roundNumber] (1-based) of [roundCount]. With [timed], a countdown ring runs for [timeLimitMs] once the
     * round takes answers (for memory, after the sequence is hidden); Times tables always show it (informational
     * below Rock star). [roundKey] changes with every new round, so effects (read-aloud, the ring) restart even when
     * two rounds look alike. [right] and [streak] count this play's right answers and the run of them up to now (the
     * "Right 6 · Streak 4" line and the streak pill); [setup] is the Times tables setup of the play.
     */
    data class Playing(
        override val tier: KidUiTier,
        override val kind: GameKind,
        val roundNumber: Int,
        val roundCount: Int,
        val timed: Boolean,
        val timeLimitMs: Long,
        val round: RoundUi,
        val roundKey: Long,
        val right: Int = 0,
        val streak: Int = 0,
        val setup: TablesSetup? = null,
    ) : GamePlayUiState

    /** The play is over: [score] and what it earned ([credit]). */
    data class Result(
        override val tier: KidUiTier,
        override val kind: GameKind,
        val score: Score,
        val credit: CreditUi,
        val currency: Currency.Fiat,
    ) : GamePlayUiState

    /** [kind] is not offered at this tier (word scramble on Seedling, D-M5-6). */
    data class Unavailable(override val tier: KidUiTier, override val kind: GameKind) : GamePlayUiState
}

/** The current round and the kid's answer in progress. */
sealed interface RoundUi {
    /** A sum or a counting round; [typed] is the Sapling answer field's text. */
    data class Arithmetic(val round: ArithmeticRound, val typed: String = "") : RoundUi

    /**
     * A memory round. While [showing] the first [shownCount] symbols of the sequence are visible (one more every
     * 700 ms); then the sequence hides and the kid taps it back, [tapped] so far.
     */
    data class Memory(
        val round: MemoryRound,
        val showing: Boolean,
        val shownCount: Int,
        val tapped: List<Symbol> = emptyList(),
    ) : RoundUi

    /**
     * A word scramble. [answer] is what the kid has spelled so far (tapped or typed); [used] are the indices of the
     * scrambled letter tiles already used.
     */
    data class Word(val round: WordRound, val answer: String = "", val used: List<Int> = emptyList()) : RoundUi

    /** A Times tables question; [typed] is the answer entered so far on the number pad (digits only). */
    data class Tables(val round: TablesRound, val typed: String = "") : RoundUi
}

/** What the finished play earned, as the result screen shows it. */
sealed interface CreditUi {
    /** Nothing was credited and the kid can try again ("Try again" on the result). */
    val retryable: Boolean get() = false

    data object Submitting : CreditUi

    /** [amount] (> 0) was credited; it is [clamped] when it is less than the score earned (the daily cap). */
    data class Credited(val amount: Money, val remainingAfter: Money, val clamped: Boolean) : CreditUi

    /** The play earned nothing (no round right): nothing was appended, it was for fun. Never shown as "+0". */
    data object NothingEarned : CreditUi

    /** The daily cap was already reached: the play was for fun (D-M5-7). */
    data object CapReached : CreditUi

    data object NoAccount : CreditUi

    /** Nothing was credited; the kid can try again. */
    data object Refused : CreditUi {
        override val retryable: Boolean get() = true
    }

    /** Refused because the phone's date is off (D-M5-13): check it, then try again. */
    data object CheckDate : CreditUi {
        override val retryable: Boolean get() = true
    }

    /** The app stopped while the credit was being saved; the wallet shows whether it landed. */
    data object Unknown : CreditUi
}

sealed interface GamePlayEvent {
    data class TimedChanged(val timed: Boolean) : GamePlayEvent

    data object Start : GamePlayEvent

    /** An arithmetic choice (Seedling and Sprout). */
    data class Choose(val value: Int) : GamePlayEvent

    /** The typed answer field changed; only digits (and, for arithmetic, one leading minus) are kept. */
    data class AnswerChanged(val text: String) : GamePlayEvent

    /** A number-pad digit (0 to 9) for the typed answer (Sapling arithmetic, Times tables). */
    data class Digit(val digit: Int) : GamePlayEvent

    /** The number pad's backspace: removes the last character of the typed answer. */
    data object Backspace : GamePlayEvent

    /** The Times tables start card: the tables chosen (2 to 12, at least one). */
    data class TablesChanged(val tables: Set<Int>) : GamePlayEvent

    /** The Times tables start card: the level chosen. */
    data class LevelChanged(val level: TablesLevel) : GamePlayEvent

    /** Flips the sign of the Sapling answer. */
    data object ToggleMinus : GamePlayEvent

    /** Checks the typed answer (IME done, "Check" or the number pad's "Go"). */
    data object SubmitAnswer : GamePlayEvent

    data class TapSymbol(val symbol: Symbol) : GamePlayEvent

    /** A scrambled letter tile, by index. */
    data class TapLetter(val index: Int) : GamePlayEvent

    /** The Sapling word field changed. */
    data class WordTyped(val text: String) : GamePlayEvent

    data object ClearWord : GamePlayEvent

    /** Retries a refused credit. */
    data object RetryCredit : GamePlayEvent

    /** A new play of the same game with a new seed. */
    data object PlayAgain : GamePlayEvent
}
