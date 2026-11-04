package dev.kpetriv.sprout.feature.chores

import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.model.AgeBand
import dev.kpetriv.sprout.core.model.ChoreSchedule
import dev.kpetriv.sprout.core.model.KidId

/**
 * Chore ideas for the editor's "Suggest chores" sheet (M6 §3.2). The app adapts it to the on-device AI gateway's
 * `suggestChores` (Gemini Nano where available, the curated catalogue per [AgeBand] otherwise, D-M6-4); tests use a
 * fake. Called from [ChoreEditorViewModel] only while the editor is on screen; nothing it returns is stored until the
 * parent saves the chore (D-M6-7).
 */
fun interface ChoreSuggestions {
    /**
     * Three to five ideas for [kidId], whose age band is [band], none of them titled like one of [existing] (the
     * kid's current chore titles). May throw; the sheet then shows an error with a retry.
     */
    suspend fun suggest(kidId: KidId, band: AgeBand, existing: List<String>): List<ChoreSuggestionUi>
}

/**
 * One idea. Applying it fills the editor's fields through the ordinary edit events; the parent still reviews and saves.
 *
 * @property money a reward in the household's fiat currency (minor units), or null.
 * @property minutes a screen-time reward in whole minutes, or null.
 */
data class ChoreSuggestionUi(
    val title: String,
    val description: String?,
    val money: Money?,
    val minutes: Long?,
    val schedule: ChoreSchedule,
)

/** The "Suggest chores" sheet while it is open. */
sealed interface SuggestionsUi {
    data object Loading : SuggestionsUi

    data class Ready(val items: List<ChoreSuggestionUi>) : SuggestionsUi

    /** The source had no idea that is not already one of the kid's chores. */
    data object Empty : SuggestionsUi

    /** The source failed or took too long; the sheet offers a retry. */
    data object Failed : SuggestionsUi
}
