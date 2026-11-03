package dev.kpetriv.sprout.home

import dev.kpetriv.sprout.core.model.AgeBand
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.startup.StartupFailure

/** What the home screen shows. */
sealed interface HomeUiState {
    data object Loading : HomeUiState

    /**
     * The active kids, in repository order. Empty until the household has a kid.
     *
     * @property startupFailure set when the process-start work failed; shown as an error card with a retry button
     *   above whatever kids could still be read.
     */
    data class Ready(val kids: List<KidRow>, val startupFailure: StartupFailure? = null) : HomeUiState

    /** The kid list could not be read; [HomeEvent.Retry] tries again. */
    data class Error(val startupFailure: StartupFailure? = null) : HomeUiState
}

/** Everything the home screen can ask the [HomeViewModel] to do. */
sealed interface HomeEvent {
    /** Retry whatever failed: the startup work and the kid list. */
    data object Retry : HomeEvent
}

/**
 * One kid in the home list. [ageBand] is null when it cannot be computed (for example a birth month after today on a
 * device with a wrong clock); the screen shows "Unknown". [toString] redacts the name so a state dump never puts it
 * in a log.
 */
data class KidRow(val id: KidId, val firstName: String, val ageBand: AgeBand?, val tier: KidUiTier) {
    override fun toString(): String = "KidRow(id=$id, firstName=<redacted>, ageBand=$ageBand, tier=$tier)"
}
