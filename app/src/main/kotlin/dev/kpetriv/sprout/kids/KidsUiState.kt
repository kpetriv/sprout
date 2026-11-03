package dev.kpetriv.sprout.kids

import dev.kpetriv.sprout.core.model.AgeBand
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.startup.StartupFailure

/** The kid list the app shell picks from, and the startup state. */
sealed interface KidsUiState {
    data object Loading : KidsUiState

    /**
     * The active kids, in repository order. Empty until the household has a kid.
     *
     * @property startupFailure set when the process-start work failed; shown as an error card with a retry button
     *   above whatever kids could still be read.
     */
    data class Ready(val kids: List<KidRow>, val startupFailure: StartupFailure? = null) : KidsUiState

    /** The kid list could not be read; [KidsEvent.Retry] tries again. */
    data class Error(val startupFailure: StartupFailure? = null) : KidsUiState
}

/** Everything the shell can ask the [KidsViewModel] to do. */
sealed interface KidsEvent {
    /** Retry whatever failed: the startup work and the kid list. */
    data object Retry : KidsEvent
}

/**
 * One kid in the picker. [ageBand] is null when it cannot be computed (for example a birth month after today on a
 * device with a wrong clock); the screen shows "Unknown". [toString] redacts the name so a state dump never puts it
 * in a log.
 */
data class KidRow(val id: KidId, val firstName: String, val ageBand: AgeBand?, val tier: KidUiTier) {
    override fun toString(): String = "KidRow(id=$id, firstName=<redacted>, ageBand=$ageBand, tier=$tier)"
}
