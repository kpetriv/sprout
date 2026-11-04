package dev.kpetriv.sprout.feature.parenthome

import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

/**
 * The weekly summary card's source (M6 §3.3). The app adapts it to `WeeklyFactsUseCase` (facts computed from the
 * ledger) and the on-device AI gateway's `weeklySummary`, which rewrites the facts with Gemini Nano where available and
 * otherwise uses the rules template (D-M6-3). Nothing is stored (D-M6-7): every [observe] recomputes. The dashboard
 * collects it only while it is on screen; a failing source shows [WeeklySummaryUi.Unavailable], never an error.
 */
fun interface WeeklySummarySource {
    /** The summary of [week]: [WeeklySummaryUi.Loading] first, then one or more results. */
    fun observe(week: SummaryWeek): Flow<WeeklySummaryUi>
}

/** Which ISO week the card summarises. */
enum class SummaryWeek {
    /** The current ISO week, Monday up to now. */
    THIS_WEEK,

    /** The ISO week before the current one. */
    LAST_WEEK,
}

/** Who wrote the summary text; both run on this phone. */
enum class SummaryPath {
    /** Gemini Nano through AICore rewrote the facts (numbers checked against them). */
    NANO,

    /** The built-in template. */
    RULES,
}

/** What the weekly summary card shows. */
sealed interface WeeklySummaryUi {
    data object Loading : WeeklySummaryUi

    /**
     * @property text two to four sentences about the household's [week]; names kids and amounts, so it is kept out of
     *   [toString] and never logged.
     * @property offerModelDownload the on-device model can be downloaded and the parent has not agreed to it yet: the
     *   card offers "Download the on-device model", which opens Settings → On-device AI (the consent row, D-M6-6).
     */
    data class Ready(
        val text: String,
        val week: SummaryWeek,
        val path: SummaryPath,
        val generatedAt: Instant,
        val offerModelDownload: Boolean = false,
    ) : WeeklySummaryUi {
        override fun toString(): String =
            "Ready(week=$week, path=$path, generatedAt=$generatedAt, offerModelDownload=$offerModelDownload)"
    }

    /** The facts could not be read; the card says so and the rest of the dashboard is unaffected. */
    data object Unavailable : WeeklySummaryUi
}
