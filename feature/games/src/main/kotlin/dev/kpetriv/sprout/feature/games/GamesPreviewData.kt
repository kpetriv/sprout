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
import dev.kpetriv.sprout.core.games.WordRound
import dev.kpetriv.sprout.core.model.KidUiTier

/** Fixed states for previews and screenshot tests: sessions come from one seed, so every run renders the same. */
@Suppress("TooManyFunctions") // One small builder per screen state with a golden.
internal object GamesPreviewData {
    const val SEED: Long = 2026L
    val gbp: Currency.Fiat = Currency.Fiat("GBP")
    private const val REMAINING = 23L
    private const val REMAINING_AFTER = 15L
    private const val CLAMPED = 4L

    fun hub(tier: KidUiTier, remaining: Money = Money(REMAINING)): GamesHubUiState.Ready =
        GamesHubUiState.Ready(tier, GameCatalogue.forTier(tier), remaining, gbp)

    fun hubCapReached(tier: KidUiTier): GamesHubUiState.Ready = hub(tier, Money(0))

    fun session(tier: KidUiTier, kind: GameKind, seed: Long = SEED): GameSession =
        // Interim until M9-games slice G-C: Times tables take the default setup.
        GameId(kind, Difficulty.forTier(tier)).let {
            GameSession.create(it, seed, setup = GameViewModel.interimTablesSetup(it))
        }

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
    )

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
