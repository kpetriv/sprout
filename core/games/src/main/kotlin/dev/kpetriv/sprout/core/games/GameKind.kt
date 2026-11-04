package dev.kpetriv.sprout.core.games

/** The three brain games. [wire] is the lower-case name used in [GameId.wire]. */
enum class GameKind {
    /** Counting and sums; multiple choice below Sapling, typed answers at Sapling. */
    ARITHMETIC,

    /** Watch a sequence of symbols, then tap it back in order. */
    MEMORY,

    /** Unscramble a word. Not offered at Seedling. */
    WORDS,
    ;

    val wire: String get() = name.lowercase()
}
