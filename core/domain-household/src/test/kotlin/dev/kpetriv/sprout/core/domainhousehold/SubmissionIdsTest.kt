package dev.kpetriv.sprout.core.domainhousehold

import dev.kpetriv.sprout.core.model.ChoreId
import dev.kpetriv.sprout.core.model.SubmissionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SubmissionIdsTest {
    @Test
    fun idIsTheDomainSeparatedSha256OfChoreAndDate() {
        // Pinned with Python: hashlib.sha256(b"sprout/chore-submission/v1\x00c1\x002026-11-02").hexdigest()
        assertEquals(
            SubmissionId("aacd3f0cea5d210cf9155a801257cef8f2262913cd1cf160232596cf360aefdc"),
            SubmissionIds.deterministic(ChoreId("c1"), date("2026-11-02")),
        )
    }

    @Test
    fun sameOccurrenceSameIdOtherwiseDifferent() {
        val id = SubmissionIds.deterministic(ChoreId("c1"), date("2026-11-02"))
        assertEquals(id, SubmissionIds.deterministic(ChoreId("c1"), date("2026-11-02")))
        assertNotEquals(id, SubmissionIds.deterministic(ChoreId("c1"), date("2026-11-03")))
        assertNotEquals(id, SubmissionIds.deterministic(ChoreId("c2"), date("2026-11-02")))
        // Concatenation ambiguity is ruled out by the separator.
        assertNotEquals(
            SubmissionIds.deterministic(ChoreId("c1-2026-11-02"), date("2026-11-02")),
            SubmissionIds.deterministic(ChoreId("c1"), date("2026-11-02")),
        )
    }

    @Test
    fun theHasherIsInjectable() {
        val fixed = SubmissionIds.deterministic(ChoreId("c1"), date("2026-11-02")) { ByteArray(32) { 1 } }
        assertEquals("01".repeat(32), fixed.value)
    }

    @Test
    fun submittedSubmissionsCarryTheDeterministicId() {
        val chore = chore(dev.kpetriv.sprout.core.model.ChoreSchedule.Daily.EVERY_DAY)
        val submission = ChoreSubmissions.submit(chore, date("2026-11-02"), CREATED_AT)
        assertTrue(SubmissionIds.isDeterministic(submission))
        assertFalse(SubmissionIds.isDeterministic(submission.copy(id = SubmissionId("other"))))
    }
}
