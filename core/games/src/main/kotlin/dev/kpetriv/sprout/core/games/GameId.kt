package dev.kpetriv.sprout.core.games

/**
 * A playable game: a [kind] at a [difficulty]. Only combinations offered by [GameCatalogue.forDifficulty] exist,
 * so `words.seedling` and `tables.seedling` can be neither constructed nor parsed.
 *
 * The wire form `"<kind>.<difficulty>"` (for example `arithmetic.sprout`) is what a `GamePlayed` ledger event carries.
 */
data class GameId(val kind: GameKind, val difficulty: Difficulty) {
    init {
        require(kind in GameCatalogue.forDifficulty(difficulty)) { "$kind is not offered at $difficulty" }
    }

    val wire: String get() = "${kind.wire}$SEPARATOR${difficulty.wire}"

    override fun toString(): String = wire

    companion object {
        private const val SEPARATOR = '.'

        /**
         * Parses a wire form strictly: lower case, exactly one dot, a known kind and difficulty, and a combination
         * the catalogue offers. Anything else returns null.
         */
        fun parse(wire: String): GameId? {
            val parts = wire.split(SEPARATOR)
            if (parts.size != 2) return null
            val kind = GameKind.entries.firstOrNull { it.wire == parts[0] }
            val difficulty = Difficulty.entries.firstOrNull { it.wire == parts[1] }
            return if (kind != null && difficulty != null && kind in GameCatalogue.forDifficulty(difficulty)) {
                GameId(kind, difficulty)
            } else {
                null
            }
        }
    }
}
