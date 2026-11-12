package dev.kpetriv.sprout.feature.chores

import dev.kpetriv.sprout.core.designsystem.components.Pictogram
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.model.AgeBand
import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.model.ChoreId
import dev.kpetriv.sprout.core.model.ChoreSchedule
import dev.kpetriv.sprout.core.model.ChoreState
import dev.kpetriv.sprout.core.model.DayOfWeek
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.model.SubmissionId
import kotlin.time.Instant

/** Fixed chore states for previews, screenshot goldens and UI tests: fixed ids, dates and amounts. */
@Suppress("MagicNumber") // Fixed preview amounts and dates.
object ChoresPreviewData {
    val gbp = Currency.Fiat("GBP")
    val today = CalendarDate(2026, 11, 4)
    private val now = Instant.fromEpochMilliseconds(1_793_800_000_000L)

    private val names = mapOf(KidUiTier.SEEDLING to "Mia", KidUiTier.SPROUT to "Leo", KidUiTier.SAPLING to "Ava")

    fun reward(moneyMinor: Long? = null, minutes: Long? = null) =
        RewardUi(moneyMinor?.let(::Money), minutes?.let(::Money), gbp)

    private fun todayChore(id: String, title: String, reward: RewardUi, status: OccurrenceStatus) = TodayChoreUi(
        choreId = ChoreId(id),
        title = title,
        description = null,
        pictogram = ChorePictogram.forTitle(title),
        dueOn = today,
        reward = reward,
        status = status,
    )

    /** Today for [tier]: four chores to do or, with [pending], three: one to do, one waiting and one done. */
    fun today(tier: KidUiTier, pending: Boolean = false) = KidTodayUiState.Ready(
        kidName = names.getValue(tier),
        tier = tier,
        date = today,
        today = listOf(
            todayChore("bed", "Make your bed", reward(moneyMinor = 50), OccurrenceStatus.TO_DO),
            todayChore(
                "teeth",
                "Brush teeth",
                reward(minutes = 10),
                if (pending) OccurrenceStatus.WAITING else OccurrenceStatus.TO_DO,
            ),
            todayChore(
                "plants",
                "Water the plants",
                reward(moneyMinor = 150, minutes = 15),
                if (pending) OccurrenceStatus.DONE else OccurrenceStatus.TO_DO,
            ),
            todayChore("toys", "Tidy toys", reward(moneyMinor = 100), OccurrenceStatus.TO_DO),
        ).filterNot { pending && it.choreId.value == "toys" }.sortedBy { it.status.ordinal },
        upcoming = if (tier == KidUiTier.SEEDLING) {
            emptyList()
        } else {
            listOf(
                UpcomingChoreUi(
                    ChoreId("bed"),
                    "Make your bed",
                    Pictogram.BED,
                    today.plusDays(1),
                    reward(moneyMinor = 50),
                ),
                UpcomingChoreUi(
                    ChoreId("bins"),
                    "Take the bins out",
                    Pictogram.BIN,
                    today.plusDays(2),
                    reward(moneyMinor = 200),
                ),
                UpcomingChoreUi(
                    ChoreId("car"),
                    "Wash the car",
                    Pictogram.CAR,
                    today.plusDays(4),
                    reward(moneyMinor = 500),
                ),
            )
        },
        proposals = if (tier == KidUiTier.SAPLING) {
            listOf(ProposedChoreUi(ChoreId("idea"), "Walk the dog", reward(moneyMinor = 300)))
        } else {
            emptyList()
        },
        currency = gbp,
    )

    /** Today for [tier] with nothing due and nothing coming up. */
    fun todayEmpty(tier: KidUiTier) =
        today(tier).copy(today = emptyList(), upcoming = emptyList(), proposals = emptyList())

    /** Today for [tier] with one chore a parent did not accept (with a reason) above one still to do. */
    fun todayRejected(tier: KidUiTier) = today(tier).let { ready ->
        ready.copy(
            today = listOf(
                ready.today.first { it.choreId.value == "teeth" }.copy(
                    status = OccurrenceStatus.NOT_ACCEPTED,
                    rejectionReason = "Please brush for two minutes",
                ),
                ready.today.first { it.choreId.value == "bed" },
            ),
        )
    }

    /** The Sprout / Sapling submission sheet for "Make your bed" with a note and a photo attached. */
    val submitSheet = SubmitSheetUi(
        choreId = ChoreId("bed"),
        title = "Make your bed",
        note = "Done before school!",
        hasPhoto = true,
    )

    /** Sapling's "Propose a chore" sheet, filled in, after a first try that left the minutes invalid. */
    val proposeSheet = ProposeSheetUi(
        title = "Walk the dog",
        moneyText = "3.00",
        minutesText = "ten",
        errors = setOf(EditorError.MINUTES_INVALID),
    )

    /** Two submissions and a proposal; avatar tints follow the household order of [choreList] (Leo, Ava, Mia). */
    fun approvals(): ApprovalsUiState.Ready = ApprovalsUiState.Ready(
        submissions = listOf(
            SubmissionUi(
                id = SubmissionId("sub-1"),
                kidName = "Leo",
                choreTitle = "Make bed",
                dueOn = today,
                submittedAt = now,
                note = "Did it before school!",
                reward = reward(moneyMinor = 250, minutes = 15),
                photo = null,
                tintIndex = 0,
            ),
            SubmissionUi(
                id = SubmissionId("sub-2"),
                kidName = "Mia",
                choreTitle = "Tidy toys",
                dueOn = today.plusDays(-1),
                submittedAt = now,
                note = null,
                reward = reward(moneyMinor = 100),
                photo = PhotoUi.Unavailable,
                tintIndex = 2,
            ),
        ),
        proposals = listOf(
            ProposalUi(
                choreId = ChoreId("idea"),
                kidName = "Ava",
                title = "Walk the dog",
                description = null,
                reward = reward(moneyMinor = 300),
                schedule = ChoreSchedule.Once(null),
                tintIndex = 1,
            ),
        ),
    )

    fun editor(errors: Set<EditorError> = emptySet()) = ChoreEditorUiState(
        isLoading = false,
        isNew = true,
        kids = listOf(
            KidOption(KidId("kid-leo"), "Leo", AgeBand.AGES_8_TO_11),
            KidOption(KidId("kid-mia"), "Mia", AgeBand.AGES_5_TO_7),
        ),
        kidId = if (errors.isEmpty()) KidId("kid-leo") else null,
        title = if (errors.isEmpty()) "Make bed" else "",
        moneyText = if (errors.isEmpty()) "2.50" else "2.5.0",
        minutesText = if (errors.isEmpty()) "15" else "",
        schedule = ScheduleForm(
            kind = ScheduleKind.DAILY,
            dailyDays = if (errors.isEmpty()) {
                setOf(
                    DayOfWeek.MONDAY,
                    DayOfWeek.WEDNESDAY,
                    DayOfWeek.FRIDAY,
                )
            } else {
                emptySet()
            },
        ),
        currency = gbp,
        today = today,
        errors = errors,
    )

    /** Ideas for an 8 to 11 year old, as the rules catalogue would offer them. */
    val suggestions = listOf(
        ChoreSuggestionUi(
            title = "Feed the cat",
            description = "Fresh water and one scoop of food",
            money = Money(50),
            minutes = null,
            schedule = ChoreSchedule.Daily(DayOfWeek.entries.toSet()),
        ),
        ChoreSuggestionUi(
            title = "Hoover your room",
            description = null,
            money = Money(150),
            minutes = 15,
            schedule = ChoreSchedule.Weekly(DayOfWeek.SATURDAY),
        ),
        ChoreSuggestionUi(
            title = "Help cook dinner",
            description = "Wash the vegetables and set the table",
            money = null,
            minutes = 20,
            schedule = ChoreSchedule.Weekly(DayOfWeek.SUNDAY),
        ),
        ChoreSuggestionUi(
            title = "Sort the recycling",
            description = null,
            money = Money(100),
            minutes = null,
            schedule = ChoreSchedule.Once(null),
        ),
    )

    /** The chore list in the household's order (Leo, Ava, Mia), which is also each kid's avatar tint. */
    fun choreList() = ChoreListUiState.Ready(
        groups = listOf(
            KidChoresUi(
                KidId("kid-leo"),
                "Leo",
                listOf(
                    ChoreRowUi(
                        ChoreId("bed"),
                        "Make bed",
                        reward(moneyMinor = 250),
                        ChoreSchedule.Daily.EVERY_DAY,
                        ChoreState.ACTIVE,
                    ),
                    ChoreRowUi(
                        ChoreId("car"),
                        "Wash the car",
                        reward(moneyMinor = 500, minutes = 30),
                        ChoreSchedule.Weekly(DayOfWeek.SATURDAY),
                        ChoreState.ACTIVE,
                    ),
                ),
                tintIndex = 0,
            ),
            KidChoresUi(
                KidId("kid-ava"),
                "Ava",
                listOf(
                    ChoreRowUi(
                        ChoreId("dog"),
                        "Walk the dog",
                        reward(moneyMinor = 300),
                        ChoreSchedule.Once(null),
                        ChoreState.PROPOSED,
                    ),
                ),
                tintIndex = 1,
            ),
            KidChoresUi(KidId("kid-mia"), "Mia", emptyList(), tintIndex = 2),
        ),
        pendingApprovals = 2,
    )
}
