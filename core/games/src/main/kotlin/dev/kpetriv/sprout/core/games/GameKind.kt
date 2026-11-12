package dev.kpetriv.sprout.core.games

/** The four brain games. [wire] is the lower-case name used in [GameId.wire]. */
enum class GameKind {
    /** Counting and sums; multiple choice below Sapling, typed answers at Sapling. */
    ARITHMETIC,

    /** Watch a sequence of symbols, then tap it back in order. */
    MEMORY,

    /** Unscramble a word. Not offered at Seedling. */
    WORDS,

    /**
     * Times tables (M9-games §6): 20 typed questions from the tables the kid chose ([TablesSetup]) at a chosen speed
     * ([TablesLevel]). Not offered at Seedling.
     */
    TABLES,
    ;

    val wire: String get() = name.lowercase()
}
