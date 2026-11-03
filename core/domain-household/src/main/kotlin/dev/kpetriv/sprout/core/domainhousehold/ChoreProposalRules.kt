package dev.kpetriv.sprout.core.domainhousehold

import dev.kpetriv.sprout.core.model.ChoreState
import dev.kpetriv.sprout.core.model.KidUiTier

/**
 * Sapling-tier kids can propose chores (M2 `feature/chores`). A proposal is a [Chore] in [ChoreState.PROPOSED]: it
 * has no occurrences and cannot be submitted until a parent accepts it, which makes it [ChoreState.ACTIVE]. A
 * declined proposal is archived, so its history stays.
 */
object ChoreProposalRules {
    /** Whether a kid on [tier] may propose chores. */
    fun canPropose(tier: KidUiTier): Boolean = tier == KidUiTier.SAPLING

    /** Whether occurrences of [chore] can be submitted: only active chores. */
    fun canSubmit(chore: Chore): Boolean = chore.state == ChoreState.ACTIVE

    /** [chore] as a fresh proposal, whatever state the kid's draft was built with. */
    fun propose(chore: Chore): Chore = chore.copy(state = ChoreState.PROPOSED)

    /** A parent accepts the proposal [chore]: it becomes active. */
    fun accept(chore: Chore): Chore {
        require(chore.state == ChoreState.PROPOSED) { "only a proposed chore can be accepted" }
        return chore.copy(state = ChoreState.ACTIVE)
    }

    /** A parent declines the proposal [chore]: it is archived. */
    fun decline(chore: Chore): Chore {
        require(chore.state == ChoreState.PROPOSED) { "only a proposed chore can be declined" }
        return chore.copy(state = ChoreState.ARCHIVED)
    }
}
