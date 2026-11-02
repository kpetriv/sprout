package dev.kpetriv.sprout.core.ui

/** What a piece of kid-mode text is for; [tierTextStyle] picks the type slot per tier. */
enum class TierTextRole {
    /** The headline number: a balance or a coin count. */
    Balance,

    /** Section and card titles. */
    Title,

    /** Running text. */
    Body,

    /** Captions, chips and secondary lines. */
    Label,
}
