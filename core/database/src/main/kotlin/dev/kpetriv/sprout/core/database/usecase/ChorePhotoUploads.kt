package dev.kpetriv.sprout.core.database.usecase

import androidx.room.withTransaction
import dev.kpetriv.sprout.core.database.BlobTarget
import dev.kpetriv.sprout.core.database.BlobWrites
import dev.kpetriv.sprout.core.database.PhotoIntegrityException
import dev.kpetriv.sprout.core.database.PhotoStore
import dev.kpetriv.sprout.core.database.RoomChoreRepository
import dev.kpetriv.sprout.core.database.SessionAuthorProvider
import dev.kpetriv.sprout.core.database.SproutDatabase
import dev.kpetriv.sprout.core.database.ThreadKeyUnavailableException
import dev.kpetriv.sprout.core.model.BlobId
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.PhotoRef
import dev.kpetriv.sprout.core.model.SubmissionId
import dev.kpetriv.sprout.core.time.Clock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * Uploads every pending chore photo (M4 §2): loads it from the [PhotoStore], encrypts it with the kid's thread key
 * ([BlobTarget.ChorePhoto]), then in **one** transaction under the device clock lock (review M4) queues its chunks,
 * marks the photo uploaded and writes the submission again with `photoRef.blobId`
 * ([RoomChoreRepository.attachPhotoBlobInTransaction]), chunks first so the push order sends the blob before the
 * submission that names it. A crash leaves either all of it or none of it; runs are serialised by one process-wide
 * lock, and a photo another run already marked, or whose submission was decided or re-photographed meanwhile, is
 * left alone (the transaction is rolled back and its cached copy dropped). A photo whose thread has no usable
 * key yet stays pending (retried on the next run); one that no longer loads is skipped. Returns the photos attached
 * now.
 */
class UploadChorePhotosUseCase @Inject constructor(
    private val uploads: PendingPhotoUploads,
    private val photos: PhotoStore,
    private val blobs: BlobWrites,
    private val chores: RoomChoreRepository,
    private val authors: SessionAuthorProvider,
) {
    suspend operator fun invoke(): List<Pair<PendingPhotoUpload, BlobId>> = LOCK.withLock {
        uploads.pending().mapNotNull { pending -> upload(pending)?.let { pending to it } }
    }

    @Suppress("ReturnCount") // Unreadable photo, no key yet, no longer pending.
    private suspend fun upload(pending: PendingPhotoUpload): BlobId? {
        val bytes = try {
            photos.load(pending.ref)
        } catch (expected: PhotoIntegrityException) {
            return null
        }
        val prepared = try {
            blobs.prepare(BlobTarget.ChorePhoto(pending.kidId, pending.submissionId), bytes)
        } catch (expected: ThreadKeyUnavailableException) {
            return null
        } finally {
            bytes.fill(0)
        }
        val blobId = prepared.context.blobId
        val author = authors.currentAuthor()
        return try {
            chores.transaction {
                blobs.queue(prepared)
                if (!uploads.markUploaded(pending.ref, blobId)) throw NotAttached()
                chores.attachPhotoBlobInTransaction(author, pending.submissionId, pending.ref, blobId)
                    ?: throw NotAttached()
            }
            blobId
        } catch (expected: NotAttached) {
            blobs.dropCached(blobId)
            null
        }
    }

    /** Rolls the upload's transaction back: the photo is no longer this run's to upload. */
    private class NotAttached : IllegalStateException("photo no longer pending")

    private companion object {
        /** One upload run at a time, process-wide. */
        val LOCK = Mutex()
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
