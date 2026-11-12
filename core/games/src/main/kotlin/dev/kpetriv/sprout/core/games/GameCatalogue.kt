package dev.kpetriv.sprout.core.games

import dev.kpetriv.sprout.core.model.KidUiTier

/** Which games each difficulty offers, how long a play is, and the per-round limits of timed mode. */
object GameCatalogue {
    private const val SEEDLING_ROUNDS = 5
    private const val SPROUT_ROUNDS = 8
    private const val SAPLING_ROUNDS = 10
    private const val TABLES_ROUNDS = 20

    private const val ARITHMETIC_LIMIT_MS = 8_000L
    private const val MEMORY_LIMIT_MS = 10_000L
    private const val WORDS_LIMIT_MS = 15_000L

    private val seedlingKinds = listOf(GameKind.ARITHMETIC, GameKind.MEMORY)
    private val allKinds = GameKind.entries.toList()

    /**
     * Seedling gets arithmetic and memory only (word scramble and Times tables are hidden, never shown broken);
     * Sprout and Sapling get all four, Times tables last.
     */
    fun forDifficulty(difficulty: Difficulty): List<GameKind> = when (difficulty) {
        Difficulty.SEEDLING -> seedlingKinds
        Difficulty.SPROUT, Difficulty.SAPLING -> allKinds
    }

    fun forTier(tier: KidUiTier): List<GameKind> = forDifficulty(Difficulty.forTier(tier))

    /** Every playable game id, in catalogue order. */
    val all: List<GameId> by lazy { Difficulty.entries.flatMap { d -> forDifficulty(d).map { GameId(it, d) } } }

    /** Rounds per play: Times tables 20 at every difficulty; otherwise Seedling 5, Sprout 8, Sapling 10. */
    fun rounds(gameId: GameId): Int = when {
        gameId.kind == GameKind.TABLES -> TABLES_ROUNDS

        else -> when (gameId.difficulty) {
            Difficulty.SEEDLING -> SEEDLING_ROUNDS
            Difficulty.SPROUT -> SPROUT_ROUNDS
            Difficulty.SAPLING -> SAPLING_ROUNDS
        }
    }

    /**
     * Whether a difficulty offers the Timed switch for arithmetic, memory and words: Sapling only. For a specific
     * game prefer [supportsTimed] with the [GameId], which also knows Times tables.
     */
    fun supportsTimed(difficulty: Difficulty): Boolean = difficulty == Difficulty.SAPLING

    /**
     * Whether a play of [gameId] can be timed (and so earn the timed bonus): Times tables at every difficulty it is
     * offered (timed when the level is [TablesLevel.ROCK_STAR]); the other games where [supportsTimed] of the
     * difficulty is true.
     */
    fun supportsTimed(gameId: GameId): Boolean = gameId.kind == GameKind.TABLES || supportsTimed(gameId.difficulty)

    /**
     * The per-round limit in timed mode: arithmetic 8 s, memory 10 s, words 15 s, Times tables 5 s (its only timed
     * level, [TablesLevel.ROCK_STAR]). A Times tables session reads its own level's limit instead
     * ([GameSession.timeLimitMs]).
     */
    fun timeLimitMs(kind: GameKind): Long = when (kind) {
        GameKind.ARITHMETIC -> ARITHMETIC_LIMIT_MS
        GameKind.MEMORY -> MEMORY_LIMIT_MS
        GameKind.WORDS -> WORDS_LIMIT_MS
        GameKind.TABLES -> TablesLevel.ROCK_STAR.timeLimitMs
    }
}
