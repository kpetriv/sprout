package dev.kpetriv.sprout.core.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class HouseholdModelTest {
    private val sha = "a".repeat(64)
    private val at = Instant.fromEpochMilliseconds(1_790_000_000_000)
    private val later = Instant.fromEpochMilliseconds(1_790_000_100_000)

    private fun submission(
        status: SubmissionStatus = SubmissionStatus.SUBMITTED,
        decidedAt: Instant? = null,
        decidedBy: UserId? = null,
        note: String? = null,
        rejectionReason: String? = null,
    ) = ChoreSubmission(
        id = SubmissionId("s1"),
        choreId = ChoreId("c1"),
        dueOn = CalendarDate(2026, 11, 2),
        kidId = KidId("k1"),
        submittedAt = at,
        note = note,
        status = status,
        decidedAt = decidedAt,
        decidedBy = decidedBy,
        rejectionReason = rejectionReason,
    )

    @Test
    fun newIdsFollowTheIdRule() {
        val constructors: List<(String) -> Any> = listOf(::ChoreId, ::RewardId, ::SubmissionId, ::GoalId, ::ScheduleId)
        for (create in constructors) {
            create("x".repeat(MAX_ID_LENGTH))
            create("a.b_c-D9")
            for (bad in listOf("", "x".repeat(MAX_ID_LENGTH + 1), "a b", "a/b", "é")) {
                assertFailsWith<IllegalArgumentException>(bad) { create(bad) }
            }
        }
        assertEquals("\"c-1\"", Json.encodeToString(ChoreId.serializer(), ChoreId("c-1")))
        assertEquals(GoalId("g"), Json.decodeFromString(GoalId.serializer(), "\"g\""))
    }

    @Test
    fun schedulesSerialiseWithStableTypeNames() {
        val schedules = listOf(
            ChoreSchedule.Once(),
            ChoreSchedule.Once(CalendarDate(2026, 11, 2)),
            ChoreSchedule.Daily(setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY)),
            ChoreSchedule.Weekly(DayOfWeek.SATURDAY),
            ChoreSchedule.Monthly(28),
        )
        for (schedule in schedules) {
            val json = Json.encodeToString(ChoreSchedule.serializer(), schedule)
            assertEquals(schedule, Json.decodeFromString(ChoreSchedule.serializer(), json))
        }
        assertEquals(
            """{"type":"weekly","dayOfWeek":"SATURDAY"}""",
            Json.encodeToString(ChoreSchedule.serializer(), ChoreSchedule.Weekly(DayOfWeek.SATURDAY)),
        )
        assertEquals(
            """{"type":"monthly","dayOfMonth":3}""",
            Json.encodeToString(AllowanceCadence.serializer(), AllowanceCadence.Monthly(3)),
        )
    }

    @Test
    fun scheduleParametersAreValidated() {
        assertFailsWith<IllegalArgumentException> { ChoreSchedule.Daily(emptySet()) }
        assertFailsWith<IllegalArgumentException> { ChoreSchedule.Monthly(0) }
        assertFailsWith<IllegalArgumentException> { ChoreSchedule.Monthly(29) }
        assertFailsWith<IllegalArgumentException> { AllowanceCadence.Monthly(29) }
        assertFailsWith<IllegalArgumentException> { AllowanceCadence.Monthly(0) }
        assertEquals(7, ChoreSchedule.Daily.EVERY_DAY.daysOfWeek.size)
    }

    @Test
    fun submissionDecisionInvariants() {
        submission()
        submission(SubmissionStatus.APPROVED, decidedAt = later, decidedBy = UserId("p1"))
        submission(SubmissionStatus.REJECTED, decidedAt = at, rejectionReason = "not done")
        assertFailsWith<IllegalArgumentException> { submission(SubmissionStatus.APPROVED) }
        assertFailsWith<IllegalArgumentException> { submission(decidedAt = later) }
        assertFailsWith<IllegalArgumentException> { submission(decidedBy = UserId("p1")) }
        assertFailsWith<IllegalArgumentException> {
            submission(SubmissionStatus.APPROVED, decidedAt = later, rejectionReason = "x")
        }
        assertFailsWith<IllegalArgumentException> {
            submission(SubmissionStatus.APPROVED, decidedAt = Instant.fromEpochMilliseconds(0))
        }
    }

    @Test
    fun submissionTextLimitsAndRedaction() {
        submission(note = "n".repeat(MAX_NOTE_LENGTH))
        assertFailsWith<IllegalArgumentException> { submission(note = "n".repeat(MAX_NOTE_LENGTH + 1)) }
        assertFailsWith<IllegalArgumentException> {
            submission(SubmissionStatus.REJECTED, decidedAt = later, rejectionReason = "r".repeat(MAX_NOTE_LENGTH + 1))
        }
        val text = submission(
            SubmissionStatus.REJECTED,
            decidedAt = later,
            note = "Grandma Jo",
            rejectionReason = "Ted",
        )
            .toString()
        assertFalse("Grandma" in text || "Ted" in text, text)
        assertTrue("note=<redacted>" in text && "rejectionReason=<redacted>" in text, text)
        assertTrue("note=null" in submission().toString())
    }

    @Test
    fun submissionRoundTripsThroughJson() {
        val value = submission(SubmissionStatus.APPROVED, decidedAt = later, decidedBy = UserId("p1"), note = "done")
            .copy(photoRef = PhotoRef("p-1.jpg.enc", sha))
        val json = Json.encodeToString(ChoreSubmission.serializer(), value)
        assertEquals(value, Json.decodeFromString(ChoreSubmission.serializer(), json))
        assertEquals(ChoreOccurrence(ChoreId("c1"), CalendarDate(2026, 11, 2)), value.occurrence)
    }

    @Test
    fun photoRefIsABareFileNameAndAHash() {
        PhotoRef("0f3c.jpg", sha)
        for (bad in listOf("", ".", "..", "a/b.jpg", "../x", "C:\\x", "a b")) {
            assertFailsWith<IllegalArgumentException>(bad) { PhotoRef(bad, sha) }
        }
        assertFailsWith<IllegalArgumentException> { PhotoRef("a.jpg", "A".repeat(64)) }
        assertFailsWith<IllegalArgumentException> { PhotoRef("a.jpg", "a".repeat(63)) }
    }

    @Test
    fun photoRefBlobIdIsOptionalAndBounded() {
        assertEquals(PhotoRef("a.jpg", sha), PhotoRef("a.jpg", sha, blobId = null))
        PhotoRef("a.jpg", sha, blobId = "0f8c2a3e-7f42-4b0e-9a51-2c1d1b4f5e6a")
        PhotoRef("a.jpg", sha, blobId = "b".repeat(MAX_PHOTO_BLOB_ID_LENGTH))
        for (bad in listOf("", "a/b", "a b", "b".repeat(MAX_PHOTO_BLOB_ID_LENGTH + 1))) {
            assertFailsWith<IllegalArgumentException>(bad) { PhotoRef("a.jpg", sha, blobId = bad) }
        }
    }

    @Test
    fun photoRefWithoutBlobIdEncodesAsBefore() {
        val json = Json { encodeDefaults = true }
        // A null blobId is left out even with encodeDefaults, so pre-M4 payloads encode byte for byte as before.
        assertEquals(
            """{"fileName":"a.jpg","sha256":"$sha"}""",
            json.encodeToString(PhotoRef.serializer(), PhotoRef("a.jpg", sha)),
        )
        val uploaded = PhotoRef("a.jpg", sha, blobId = "blob-1")
        assertEquals(
            """{"fileName":"a.jpg","sha256":"$sha","blobId":"blob-1"}""",
            json.encodeToString(PhotoRef.serializer(), uploaded),
        )
        val encoded = json.encodeToString(PhotoRef.serializer(), uploaded)
        assertEquals(uploaded, json.decodeFromString(PhotoRef.serializer(), encoded))
        val legacy = """{"fileName":"a.jpg","sha256":"$sha"}"""
        assertEquals(PhotoRef("a.jpg", sha), json.decodeFromString(PhotoRef.serializer(), legacy))
    }

    @Test
    fun interestPolicyLimits() {
        val household = HouseholdId("h")
        assertFalse(InterestPolicy(household).isEnabled)
        assertEquals(1, InterestPolicy(household).accrualDay)
        assertTrue(InterestPolicy(household, 2000, 28).isEnabled)
        assertFailsWith<IllegalArgumentException> { InterestPolicy(household, 2001) }
        assertFailsWith<IllegalArgumentException> { InterestPolicy(household, -1) }
        assertFailsWith<IllegalArgumentException> { InterestPolicy(household, 100, 29) }
        assertFailsWith<IllegalArgumentException> { InterestPolicy(household, 100, 0) }
        val policy = InterestPolicy(household, 250, 15)
        assertEquals(
            policy,
            Json.decodeFromString(
                InterestPolicy.serializer(),
                Json.encodeToString(InterestPolicy.serializer(), policy),
            ),
        )
    }
}
