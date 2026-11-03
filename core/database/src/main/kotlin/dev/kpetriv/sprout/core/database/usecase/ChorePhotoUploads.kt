package dev.kpetriv.sprout.core.database.usecase

import androidx.room.withTransaction
import dev.kpetriv.sprout.core.database.BlobTarget
import dev.kpetriv.sprout.core.database.BlobWrites
import dev.kpetriv.sprout.core.database.PhotoIntegrityException
import dev.kpetriv.sprout.core.database.PhotoStore
import dev.kpetriv.sprout.core.database.SproutDatabase
import dev.kpetriv.sprout.core.database.ThreadKeyUnavailableException
import dev.kpetriv.sprout.core.model.BlobId
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.PhotoRef
import dev.kpetriv.sprout.core.model.SubmissionId
import dev.kpetriv.sprout.core.time.Clock
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/** A chore photo waiting to be uploaded: attached to a submission that is still waiting for a decision. */
data class PendingPhotoUpload(val ref: PhotoRef, val kidId: KidId, val submissionId: SubmissionId)

/**
 * The chore photo upload queue (M4 §2, D-M2-3, D-M4-5): photos attached to a SUBMITTED submission that have not been
 * queued as a blob yet ([pending]), and [markUploaded] once [BlobWrites] queued one. The local encrypted copy stays
 * until the submission is decided, as before.
 */
class PendingPhotoUploads @Inject constructor(private val database: SproutDatabase) {
    suspend fun pending(): List<PendingPhotoUpload> = database.chatHouseholdDao().pendingUploads().mapNotNull { row ->
        row.submissionId?.let { PendingPhotoUpload(row.toRef(), row.kidId, it) }
    }

    /** Records that [ref] was queued as [blobId]; false when it was already marked (or is gone). */
    suspend fun markUploaded(ref: PhotoRef, blobId: BlobId): Boolean =
        database.chatHouseholdDao().markUploaded(ref.fileName, blobId.value) > 0
}

/**
 * Uploads every pending chore photo (M4 §2): loads it from the [PhotoStore], encrypts and queues it with the kid's
 * thread key ([BlobTarget.ChorePhoto]), and marks it uploaded. A photo whose thread has no usable key yet stays
 * pending (retried on the next run); one that no longer loads is skipped. Returns the photos queued now.
 */
class UploadChorePhotosUseCase @Inject constructor(
    private val uploads: PendingPhotoUploads,
    private val photos: PhotoStore,
    private val blobs: BlobWrites,
) {
    suspend operator fun invoke(): List<Pair<PendingPhotoUpload, BlobId>> = uploads.pending().mapNotNull { pending ->
        val bytes = try {
            photos.load(pending.ref)
        } catch (expected: PhotoIntegrityException) {
            return@mapNotNull null
        }
        val blobId = try {
            blobs.store(BlobTarget.ChorePhoto(pending.kidId, pending.submissionId), bytes)
        } catch (expected: ThreadKeyUnavailableException) {
            return@mapNotNull null
        } finally {
            bytes.fill(0)
        }
        uploads.markUploaded(pending.ref, blobId)
        pending to blobId
    }
}

/**
 * The D-M2-14 orphan sweep, run at start-up: deletes encrypted chore photos (file and row, audited `PHOTO_DELETED`)
 * that were never attached to a submission and are older than [maxAge] (24 hours by default), left behind when the
 * submit sheet was dismissed. Returns how many were deleted.
 */
class SweepOrphanPhotosUseCase @Inject constructor(
    private val database: SproutDatabase,
    private val photos: PhotoStore,
    private val clock: Clock,
) {
    suspend operator fun invoke(maxAge: Duration = DEFAULT_MAX_AGE): Int {
        val before = (clock.now() - maxAge).toEpochMilliseconds()
        val orphans = database.withTransaction { database.chatHouseholdDao().orphansBefore(before) }
        return orphans.count { photos.delete(it.toRef()) }
    }

    companion object {
        val DEFAULT_MAX_AGE: Duration = 24.hours
    }
}
