package dev.kpetriv.sprout.core.domainhousehold

import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.model.ChoreSchedule
import dev.kpetriv.sprout.core.model.ChoreState
import dev.kpetriv.sprout.core.model.ChoreSubmission
import dev.kpetriv.sprout.core.model.DayOfWeek
import dev.kpetriv.sprout.core.model.SubmissionId
import dev.kpetriv.sprout.core.model.SubmissionStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class KidTodayTest {
    private val monday = date("2026-11-02")

    private fun titled(title: String, schedule: ChoreSchedule, id: String = title.lowercase().replace(' ', '-')) =
        chore(schedule, id = id).copy(title = title)

    private fun submitted(chore: Chore, status: SubmissionStatus, dueOn: CalendarDate = monday) = ChoreSubmission(
        id = SubmissionId("s-${chore.id.value}"),
        choreId = chore.id,
        dueOn = dueOn,
        kidId = KID,
        submittedAt = CREATED_AT,
        status = status,
        decidedAt = CREATED_AT.takeIf { status != SubmissionStatus.SUBMITTED },
    )

    @Test
    fun todayListsTheDueChoresToDoFirstThenByTitle() {
        val water = titled("water plants", ChoreSchedule.Daily(DayOfWeek.entries.toSet()))
        val bins = titled("Bins", ChoreSchedule.Weekly(DayOfWeek.MONDAY))
        val dishes = titled("Dishes", ChoreSchedule.Weekly(DayOfWeek.MONDAY))
        val beds = titled("Beds", ChoreSchedule.Weekly(DayOfWeek.MONDAY))
        val tidy = titled("Tidy", ChoreSchedule.Weekly(DayOfWeek.TUESDAY))
        val proposed = titled("Proposed", ChoreSchedule.Weekly(DayOfWeek.MONDAY)).copy(state = ChoreState.PROPOSED)
        val submissions = listOf(
            submitted(dishes, SubmissionStatus.SUBMITTED),
            submitted(beds, SubmissionStatus.APPROVED),
            submitted(bins, SubmissionStatus.REJECTED, dueOn = date("2026-10-26")), // last week's: today is open
        )

        val today = KidToday.occurrences(listOf(water, bins, dishes, beds, tidy, proposed), submissions, monday)

        assertEquals(listOf("Bins", "water plants", "Dishes", "Beds"), today.map { it.chore.title })
        assertEquals(
            listOf(TodayStatus.TO_DO, TodayStatus.TO_DO, TodayStatus.WAITING, TodayStatus.DONE),
            today.map { it.status },
        )
        assertSame(submissions[0], today[2].submission)
        assertEquals(monday, today[0].dueOn)
    }

    @Test
    fun anAnyTimeChoreIsKeyedOnItsCreationDayAndLeavesOnceApproved() {
        val car = titled("Wash the car", ChoreSchedule.Once(null))
        val key = ChoreScheduler.anyTimeDueOn(car)

        val open = KidToday.occurrence(car, emptyList(), monday)
        assertEquals(key, open?.dueOn)
        assertEquals(TodayStatus.TO_DO, open?.status)
        assertEquals(
            TodayStatus.NOT_ACCEPTED,
            KidToday.occurrence(car, listOf(submitted(car, SubmissionStatus.REJECTED, key)), monday)?.status,
        )
        assertNull(KidToday.occurrence(car, listOf(submitted(car, SubmissionStatus.APPROVED, key)), monday))
    }

    @Test
    fun aChoreNotDueTodayHasNoEntry() {
        assertNull(KidToday.occurrence(titled("Tidy", ChoreSchedule.Weekly(DayOfWeek.TUESDAY)), emptyList(), monday))
        assertEquals(emptyList(), KidToday.occurrences(emptyList(), emptyList(), monday))
        assertEquals(TodayStatus.TO_DO, KidToday.statusOf(null))
        val secret = KidToday.occurrence(titled("Secret plan", ChoreSchedule.Once(null)), emptyList(), monday)
        assertEquals(false, "Secret" in secret.toString(), "titles never reach toString")
    }
}
