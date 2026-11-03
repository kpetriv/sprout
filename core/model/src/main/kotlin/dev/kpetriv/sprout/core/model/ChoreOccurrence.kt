package dev.kpetriv.sprout.core.model

import kotlinx.serialization.Serializable

/** One due date of a chore. Derived from the chore's [ChoreSchedule]; never stored. */
@Serializable
data class ChoreOccurrence(val choreId: ChoreId, val dueOn: CalendarDate)
