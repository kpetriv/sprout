package dev.kpetriv.sprout.core.ui

/** Which way a ledger row moved value, from the kid's point of view. */
enum class AmountDirection {
    /** Credited: chores, allowance, games, interest. */
    IN,

    /** Debited: shop, screen time. */
    OUT,

    /** Moved between the kid's own accounts (to savings). */
    MOVE,
}
