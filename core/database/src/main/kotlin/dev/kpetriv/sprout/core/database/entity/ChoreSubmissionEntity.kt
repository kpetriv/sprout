package dev.kpetriv.sprout.core.database.entity

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.model.ChoreId
import dev.kpetriv.sprout.core.model.ChoreSubmission
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.PhotoRef
import dev.kpetriv.sprout.core.model.SubmissionId
import dev.kpetriv.sprout.core.model.SubmissionStatus
import dev.kpetriv.sprout.core.model.UserId
import kotlin.time.Instant

/**
 * A kid's submission of one chore occurrence ([ChoreSubmission]). [submissionId] is the deterministic id of
 * `(choreId, dueOn)` (`SubmissionIds`), and `(choreId, dueOn)` is unique, so one occurrence has exactly one row on
 * every device. A synced document.
 *
 * @property note kid-written free text; never logged or audited.
 * @property rejectionReason parent-written free text; never logged or audited.
 * @property photoBlobId the id of the end-to-end encrypted blob the kid's device uploaded the photo as
 *   ([PhotoRef.blobId], M4 §2); null while the photo exists only on the kid's device (schema version 6).
 * @property stamp author, signature and tombstone flag of the row's current state (schema version 3).
 */
@Entity(
    tableName = "chore_submissions",
    indices = [
        Index(value = ["choreId", "dueOn"], unique = true),
        Index(value = ["kidId"]),
        Index(value = ["status"]),
    ],
)
data class ChoreSubmissionEntity(
    @PrimaryKey val submissionId: SubmissionId,
    val choreId: ChoreId,
    val dueOn: CalendarDate,
    val kidId: KidId,
    val submittedAtEpochMs: Long,
    val note: String?,
    val photoFileName: String?,
    val photoSha256: String?,
    val photoBlobId: String? = null,
    val status: SubmissionStatus,
    val decidedAtEpochMs: Long?,
    val decidedBy: UserId?,
    val rejectionReason: String?,
    val lamport: Long,
    @Embedded val stamp: DocumentStamp = DocumentStamp(),
) {
    fun toDomain(): ChoreSubmission = ChoreSubmission(
        id = submissionId,
        choreId = choreId,
        dueOn = dueOn,
        kidId = kidId,
        submittedAt = Instant.fromEpochMilliseconds(submittedAtEpochMs),
        note = note,
        photoRef = photoFileName?.let { name -> photoSha256?.let { PhotoRef(name, it, photoBlobId) } },
        status = status,
        decidedAt = decidedAtEpochMs?.let(Instant::fromEpochMilliseconds),
        decidedBy = decidedBy,
        rejectionReason = rejectionReason,
    )

    override fun toString(): String =
        "ChoreSubmissionEntity(submissionId=$submissionId, choreId=$choreId, dueOn=$dueOn, status=$status)"

    companion object {
        fun from(submission: ChoreSubmission, lamport: Long) = ChoreSubmissionEntity(
            submissionId = submission.id,
            choreId = submission.choreId,
            dueOn = submission.dueOn,
            kidId = submission.kidId,
            submittedAtEpochMs = submission.submittedAt.toEpochMilliseconds(),
            note = submission.note,
            photoFileName = submission.photoRef?.fileName,
            photoSha256 = submission.photoRef?.sha256,
            photoBlobId = submission.photoRef?.blobId,
            status = submission.status,
            decidedAtEpochMs = submission.decidedAt?.toEpochMilliseconds(),
            decidedBy = submission.decidedBy,
            rejectionReason = submission.rejectionReason,
            lamport = lamport,
        )
    }
}
