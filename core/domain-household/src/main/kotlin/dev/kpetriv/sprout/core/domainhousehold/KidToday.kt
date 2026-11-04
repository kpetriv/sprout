package dev.kpetriv.sprout.core.domainhousehold

import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.model.ChoreSchedule
import dev.kpetriv.sprout.core.model.ChoreState
import dev.kpetriv.sprout.core.model.ChoreSubmission
import dev.kpetriv.sprout.core.model.SubmissionStatus

/** Where one of today's chores stands for the kid, in the order the kid's today list shows them. */
enum class TodayStatus {
    TO_DO,

    /** Submitted; waiting for a parent. */
    WAITING,

    /** A parent rejected it. */
    NOT_ACCEPTED,

    /** Approved and paid. */
    DONE,
}

/**
 * One chore due today: [dueOn] is its occurrence key ([ChoreScheduler.occurrenceDate]), [submission] the kid's
 * submission for that occurrence, if any. `toString` never prints the chore's title.
 */
class TodayOccurrence(
    val chore: Chore,
    val dueOn: CalendarDate,
    val status: TodayStatus,
    val submission: ChoreSubmission?,
) {
    override fun toString(): String = "TodayOccurrence(choreId=${chore.id}, dueOn=$dueOn, status=$status)"
}

/**
 * The kid's "today" (M7 §4): every active chore due on a date and where it stands, as the kid's today screen and the
 * home screen widget both show it. Pure, so both derive the same list from the same rows.
 */
object KidToday {
    /**
     * Every [ChoreState.ACTIVE] chore of [chores] due on [today] ([occurrence]), ordered by [TodayStatus] (to do
     * first) and then by title, ignoring case; chores that compare equal keep their order in [chores].
     */
    fun occurrences(
        chores: List<Chore>,
        submissions: List<ChoreSubmission>,
        today: CalendarDate,
    ): List<TodayOccurrence> = chores.filter { it.state == ChoreState.ACTIVE }
        .mapNotNull { occurrence(it, submissions, today) }
        .sortedWith(ORDER)

    /**
     * [chore]'s entry for [today], or null when it is not due ([ChoreScheduler.isDueOn]). A one-off "any time" chore
     * (`Once(null)`) has a single occurrence keyed on [ChoreScheduler.anyTimeDueOn] whatever day it is done, so it pays
     * once: it stays (waiting, or not accepted) until it is approved, and then disappears.
     */
    @Suppress("ReturnCount") // Guard clauses: each early return is a precondition that is not met.
    fun occurrence(chore: Chore, submissions: List<ChoreSubmission>, today: CalendarDate): TodayOccurrence? {
        if (!ChoreScheduler.isDueOn(chore, today)) return null
        val dueOn = ChoreScheduler.occurrenceDate(chore, today)
        val submission = submissions.firstOrNull { it.choreId == chore.id && it.dueOn == dueOn }
        val anyTime = chore.schedule == ChoreSchedule.Once(null)
        if (anyTime && submission?.status == SubmissionStatus.APPROVED) return null
        return TodayOccurrence(chore, dueOn, statusOf(submission), submission)
    }

    /** What a submission (or none) means for the kid. */
    fun statusOf(submission: ChoreSubmission?): TodayStatus = when (submission?.status) {
        null -> TodayStatus.TO_DO
        SubmissionStatus.SUBMITTED -> TodayStatus.WAITING
        SubmissionStatus.APPROVED -> TodayStatus.DONE
        SubmissionStatus.REJECTED -> TodayStatus.NOT_ACCEPTED
    }

    private val ORDER: Comparator<TodayOccurrence> =
        compareBy<TodayOccurrence> { it.status.ordinal }.thenBy { it.chore.title.lowercase() }
}
