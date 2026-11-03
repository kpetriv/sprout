package dev.kpetriv.sprout.core.domainhousehold

import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.model.ChoreSchedule
import dev.kpetriv.sprout.core.model.MAX_DESCRIPTION_LENGTH
import dev.kpetriv.sprout.core.model.MAX_TITLE_LENGTH

/** Something wrong with a chore form; the UI maps each to a message. */
enum class ChoreViolation {
    TITLE_BLANK,
    TITLE_TOO_LONG,
    DESCRIPTION_TOO_LONG,

    /** Neither money nor minutes. */
    NO_REWARD,

    /** A money or minutes reward of zero or less. */
    REWARD_NOT_POSITIVE,

    /** A one-off chore due before today. */
    DUE_DATE_IN_PAST,
}

/**
 * The editable fields of a chore form, before a [Chore] is built. [description] is redacted by [toString].
 */
data class ChoreDraft(
    val title: String,
    val description: String? = null,
    val money: Money? = null,
    val minutes: Money? = null,
    val schedule: ChoreSchedule,
) {
    override fun toString(): String =
        "ChoreDraft(title=$title, description=${redacted(description)}, money=$money, minutes=$minutes, " +
            "schedule=$schedule)"

    companion object {
        /** The draft for editing an existing [chore]. */
        fun of(chore: Chore): ChoreDraft =
            ChoreDraft(chore.title, chore.description, chore.reward.money, chore.reward.minutes, chore.schedule)
    }
}

/**
 * Form-level checks for creating or editing a chore, run before building a [Chore] (whose constructor enforces the
 * same limits by throwing). Schedule parameters themselves (a non-empty day set, a day of month in 1..28) are
 * guaranteed by [ChoreSchedule]; here the schedule is only checked against today.
 */
object ChoreValidation {
    /** Every violation in [draft] as of [today], in a stable order; empty when the chore can be saved. */
    fun validate(draft: ChoreDraft, today: CalendarDate): List<ChoreViolation> = buildList {
        if (draft.title.isBlank()) add(ChoreViolation.TITLE_BLANK)
        if (draft.title.length > MAX_TITLE_LENGTH) add(ChoreViolation.TITLE_TOO_LONG)
        if (draft.description != null && draft.description.length > MAX_DESCRIPTION_LENGTH) {
            add(ChoreViolation.DESCRIPTION_TOO_LONG)
        }
        if (draft.money == null && draft.minutes == null) add(ChoreViolation.NO_REWARD)
        if (listOfNotNull(draft.money, draft.minutes).any { !it.isPositive }) add(ChoreViolation.REWARD_NOT_POSITIVE)
        val dueOn = (draft.schedule as? ChoreSchedule.Once)?.dueOn
        if (dueOn != null && dueOn < today) add(ChoreViolation.DUE_DATE_IN_PAST)
    }

    /** Checks an existing [chore] against [today] (its other limits already hold by construction). */
    fun validate(chore: Chore, today: CalendarDate): List<ChoreViolation> = validate(ChoreDraft.of(chore), today)
}
