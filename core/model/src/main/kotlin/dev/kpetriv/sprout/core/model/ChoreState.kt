package dev.kpetriv.sprout.core.model

import kotlinx.serialization.Serializable

/** Lifecycle of a chore definition. */
@Serializable
enum class ChoreState {
    /** Assigned and on the kid's list; occurrences can be submitted. */
    ACTIVE,

    /** Proposed by a Sapling-tier kid; waits for a parent to accept it. Cannot be submitted. */
    PROPOSED,

    /** Retired (or a declined proposal). Kept for history; hidden from day-to-day screens. */
    ARCHIVED,
}
