package dev.kpetriv.sprout.feature.games

import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.games.ArithmeticRound
import dev.kpetriv.sprout.core.games.Difficulty
import dev.kpetriv.sprout.core.games.GameCatalogue
import dev.kpetriv.sprout.core.games.GameCredit
import dev.kpetriv.sprout.core.games.GameId
import dev.kpetriv.sprout.core.games.GameKind
import dev.kpetriv.sprout.core.games.GameSession
import dev.kpetriv.sprout.core.games.MemoryRound
import dev.kpetriv.sprout.core.games.Score
import dev.kpetriv.sprout.core.games.TablesLevel
import dev.kpetriv.sprout.core.games.TablesRound
import dev.kpetriv.sprout.core.games.TablesSetup
import dev.kpetriv.sprout.core.games.WordRound
import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.model.KidUiTier

/** Fixed states for previews and screenshot tests: sessions come from one seed, so every run renders the same. */
@Suppress("TooManyFunctions") // One small builder per screen state with a golden.
internal object GamesPreviewData {
    const val SEED: Long = 2026L
    val gbp: Currency.Fiat = Currency.Fiat("GBP")
    private const val REMAINING = 23L
    private const val REMAINING_AFTER = 15L
    private const val CLAMPED = 4L

    private const val SCORE_TODAY = 9
    private const val SCORE_YESTERDAY = 10
    private const val SCORE_MONDAY = 7
    private const val AMOUNT_TODAY = 11L
    private const val AMOUNT_YESTERDAY = 15L
    private const val AMOUNT_MONDAY = 7L
    private const val BEST_THIS_WEEK = 18
    private const val TABLES_ROUND = 7
    private const val TABLES_RIGHT = 6
    private const val TABLES_STREAK = 4
    private const val SPROUT_STREAK = 2

    /** The Times tables setup the goldens show: the render's "3, 4, 6" at Sprout, "7, 8, 9" at Sapling. */
    fun tablesSetup(tier: KidUiTier, level: TablesLevel? = null): TablesSetup = when (tier) {
        KidUiTier.SAPLING -> TablesSetup(SAPLING_TABLES, level ?: TablesLevel.STEADY)
        else -> TablesSetup(SPROUT_TABLES, level ?: TablesLevel.GENTLE)
    }

    private val SPROUT_TABLES = setOf(3, 4, 6)
    private val SAPLING_TABLES = setOf(7, 8, 9)
    private val TODAY = CalendarDate(2026, 11, 12)
    private val YESTERDAY = CalendarDate(2026, 11, 11)
    private val MONDAY = CalendarDate(2026, 11, 9)

    fun hub(tier: KidUiTier, remaining: Money = Money(REMAINING)): GamesHubUiState.Ready {
        val games = GameCatalogue.forTier(tier)
        val setup = if (GameKind.TABLES in games) tablesSetup(tier) else null
        return GamesHubUiState.Ready(tier, games, remaining, gbp, setup)
    }

    fun hubCapReached(tier: KidUiTier): GamesHubUiState.Ready = hub(tier, Money(0))

    fun session(
        tier: KidUiTier,
        kind: GameKind,
        seed: Long = SEED,
        setup: TablesSetup? = if (kind == GameKind.TABLES) tablesSetup(tier) else null,
    ): GameSession = GameSession.create(GameId(kind, Difficulty.forTier(tier)), seed, setup = setup)

    /** A sum with, at Sapling, an answer being typed; Seedling shows a counting round with dots. */
    fun arithmetic(tier: KidUiTier): GamePlayUiState.Playing {
        if (tier == KidUiTier.SEEDLING) {
            // The first seed from SEED on whose session opens with a counting round: still fixed on every run.
            val seed = generateSequence(SEED) { it + 1 }.first { candidate ->
                (session(tier, GameKind.ARITHMETIC, seed = candidate).rounds[0] as ArithmeticRound).dots != null
            }
            val round = session(tier, GameKind.ARITHMETIC, seed = seed).rounds[0] as ArithmeticRound
            return playing(tier, GameKind.ARITHMETIC, 0, RoundUi.Arithmetic(round))
        }
        val round = session(tier, GameKind.ARITHMETIC).rounds[2] as ArithmeticRound
        val typed = if (round.choices == null) "1" else ""
        return playing(tier, GameKind.ARITHMETIC, 2, RoundUi.Arithmetic(round, typed))
            .copy(right = SPROUT_STREAK, streak = SPROUT_STREAK)
    }

    /** The memory sequence part-way through being shown. */
    fun memoryShowing(tier: KidUiTier): GamePlayUiState.Playing {
        val round = session(tier, GameKind.MEMORY).rounds.first() as MemoryRound
        return playing(tier, GameKind.MEMORY, 0, RoundUi.Memory(round, showing = true, shownCount = 2))
    }

    /** The sequence hidden and the first two symbols tapped back. */
    fun memoryAnswering(tier: KidUiTier): GamePlayUiState.Playing {
        val round = session(tier, GameKind.MEMORY).rounds.first() as MemoryRound
        val tapped = round.sequence.take(2)
        val ui = RoundUi.Memory(round, showing = false, shownCount = round.sequence.size, tapped = tapped)
        return playing(tier, GameKind.MEMORY, 0, ui)
    }

    /** A word with its first two letters placed (Sprout and Sapling). */
    fun word(tier: KidUiTier): GamePlayUiState.Playing {
        val round = session(tier, GameKind.WORDS).rounds[1] as WordRound
        val answer = round.word.take(2)
        val ui = RoundUi.Word(round, answer, GameViewModel.tilesFor(answer, round.scrambled))
        return playing(tier, GameKind.WORDS, 1, ui)
    }

    /** All but one round right, credited in full with some of today's allowance left. */
    fun resultCredited(tier: KidUiTier): GamePlayUiState.Result {
        val gameId = GameId(GameKind.ARITHMETIC, Difficulty.forTier(tier))
        val rounds = GameCatalogue.rounds(gameId)
        val score = Score(correct = rounds - 1, total = rounds, perfect = false, timedBonus = false)
        val amount = Money(GameCredit.earned(gameId, score) ?: 0L)
        return GamePlayUiState.Result(
            tier = tier,
            kind = GameKind.ARITHMETIC,
            score = score,
            credit = CreditUi.Credited(amount, remainingAfter = Money(REMAINING_AFTER), clamped = false),
            currency = gbp,
        )
    }

    /** The Sapling start card with the Timed switch on. */
    fun startTimed(): GamePlayUiState.Start = GamePlayUiState.Start(
        tier = KidUiTier.SAPLING,
        kind = GameKind.ARITHMETIC,
        rounds = GameCatalogue.rounds(GameId(GameKind.ARITHMETIC, Difficulty.SAPLING)),
        timedAvailable = true,
        timed = true,
        currency = gbp,
    )

    /** The Sapling start card with "Your last plays": three credited plays from the ledger. */
    fun startHistory(): GamePlayUiState.Start {
        val gameId = GameId(GameKind.ARITHMETIC, Difficulty.SAPLING)
        return startTimed().copy(
            history = listOf(
                GamePlaySummary(TODAY, gameId, SCORE_TODAY, Money(AMOUNT_TODAY)),
                GamePlaySummary(YESTERDAY, gameId, SCORE_YESTERDAY, Money(AMOUNT_YESTERDAY)),
                GamePlaySummary(MONDAY, gameId, SCORE_MONDAY, Money(AMOUNT_MONDAY)),
            ),
        )
    }

    /** The Times tables start card (Sprout and Sapling): the setup and the best score this week. */
    fun tablesStart(tier: KidUiTier): GamePlayUiState.Start {
        val setup = tablesSetup(tier)
        return GamePlayUiState.Start(
            tier = tier,
            kind = GameKind.TABLES,
            rounds = GameCatalogue.rounds(GameId(GameKind.TABLES, Difficulty.forTier(tier))),
            timedAvailable = true,
            timed = setup.level.timed,
            setup = setup,
            bestThisWeek = BEST_THIS_WEEK,
        )
    }

    /** A Times tables question part-way through the play, "4" typed, six right with a streak of four. */
    fun tables(tier: KidUiTier, level: TablesLevel? = null): GamePlayUiState.Playing {
        val setup = tablesSetup(tier, level)
        val session = session(tier, GameKind.TABLES, setup = setup)
        val round = session.rounds[TABLES_ROUND] as TablesRound
        return GamePlayUiState.Playing(
            tier = tier,
            kind = GameKind.TABLES,
            roundNumber = TABLES_ROUND + 1,
            roundCount = session.rounds.size,
            timed = session.timed,
            timeLimitMs = session.timeLimitMs(round),
            round = RoundUi.Tables(round, typed = "4"),
            roundKey = TABLES_ROUND.toLong(),
            right = TABLES_RIGHT,
            streak = TABLES_STREAK,
            setup = setup,
        )
    }

    /** The Sapling Rock star play: timed, 5 s a question. */
    fun tablesTimed(): GamePlayUiState.Playing = tables(KidUiTier.SAPLING, TablesLevel.ROCK_STAR)

    /** A timed Sapling round: the time bar runs above the sum. */
    fun arithmeticTimed(): GamePlayUiState.Playing = arithmetic(KidUiTier.SAPLING).copy(timed = true)

    /** A perfect play clamped to the little that was left of today's cap. */
    fun resultClamped(tier: KidUiTier): GamePlayUiState.Result {
        val rounds = GameCatalogue.rounds(GameId(GameKind.ARITHMETIC, Difficulty.forTier(tier)))
        return GamePlayUiState.Result(
            tier = tier,
            kind = GameKind.ARITHMETIC,
            score = Score(correct = rounds, total = rounds, perfect = true, timedBonus = false),
            credit = CreditUi.Credited(Money(CLAMPED), remainingAfter = Money.ZERO, clamped = true),
            currency = gbp,
        )
    }

    /** The ledger refused the credit: nothing was saved, "Try again". */
    fun resultRefused(tier: KidUiTier): GamePlayUiState.Result = resultCredited(tier).copy(credit = CreditUi.Refused)

    /** The app stopped while the credit was saved: the wallet will tell. */
    fun resultUnknown(tier: KidUiTier): GamePlayUiState.Result = resultCredited(tier).copy(credit = CreditUi.Unknown)

    /** A perfect play after today's cap was reached: for fun. */
    fun resultCapped(tier: KidUiTier): GamePlayUiState.Result {
        val rounds = GameCatalogue.rounds(GameId(GameKind.MEMORY, Difficulty.forTier(tier)))
        return GamePlayUiState.Result(
            tier = tier,
            kind = GameKind.MEMORY,
            score = Score(correct = rounds, total = rounds, perfect = true, timedBonus = false),
            credit = CreditUi.CapReached,
            currency = gbp,
        )
    }

    private fun playing(tier: KidUiTier, kind: GameKind, index: Int, round: RoundUi): GamePlayUiState.Playing {
        val session = session(tier, kind)
        return GamePlayUiState.Playing(
            tier = tier,
            kind = kind,
            roundNumber = index + 1,
            roundCount = session.rounds.size,
            timed = false,
            timeLimitMs = session.timeLimitMs(session.rounds[index]),
            round = round,
            roundKey = index.toLong(),
        )
    }
}
