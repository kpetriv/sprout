package dev.kpetriv.sprout.core.games

import dev.kpetriv.sprout.core.model.KidUiTier

/** Game difficulty. There is exactly one per [KidUiTier]; a tier plays only its own difficulty. */
enum class Difficulty {
    SEEDLING,
    SPROUT,
    SAPLING,
    ;

    val wire: String get() = name.lowercase()

    companion object {
        fun forTier(tier: KidUiTier): Difficulty = when (tier) {
            KidUiTier.SEEDLING -> SEEDLING
            KidUiTier.SPROUT -> SPROUT
            KidUiTier.SAPLING -> SAPLING
        }
    }
}
