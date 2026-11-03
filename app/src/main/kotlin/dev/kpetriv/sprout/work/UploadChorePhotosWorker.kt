package dev.kpetriv.sprout.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.kpetriv.sprout.core.database.ChoreRepository
import dev.kpetriv.sprout.core.database.SessionStore
import dev.kpetriv.sprout.core.database.SproutDatabase
import dev.kpetriv.sprout.core.database.SubmissionFilter
import dev.kpetriv.sprout.core.database.usecase.PendingPhotoUpload
import dev.kpetriv.sprout.core.database.usecase.PendingPhotoUploads
import dev.kpetriv.sprout.core.database.usecase.UploadChorePhotosUseCase
import dev.kpetriv.sprout.core.model.BlobId
import dev.kpetriv.sprout.core.model.PhotoRef
import dev.kpetriv.sprout.core.model.SubmissionId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Uploads chore photos (M4 §2, D-M2-3, D-M3-9): [UploadChorePhotosUseCase] encrypts each photo of a submission still
 * awaiting review with the kid's thread key and queues it as a blob, then [ChoreRepository.attachPhotoBlob] writes
 * the submission again with `photoRef.blobId`, so the parent's next pull names the blob. A photo uploaded by an
 * earlier run whose submission was not updated (the process died in between) is attached here too. Photos whose
 * thread has no key yet stay pending for the next run. Nothing is logged.
 */
@HiltWorker
class UploadChorePhotosWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val photoUploads: ChorePhotoUploader,
) : CoroutineWorker(context, params) {
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // Storage or key failures retry; nothing is logged.
    override suspend fun doWork(): Result = try {
        photoUploads.uploadAndAttach()
        Result.success()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Exception) {
        if (runAttemptCount + 1 < MAX_ATTEMPTS) Result.retry() else Result.failure()
    }

    companion object {
        /** Unique name of the one-time upload work. */
        const val UNIQUE_NAME: String = "upload-chore-photos"
        const val MAX_ATTEMPTS: Int = 3
    }
}

/** The upload run of [UploadChorePhotosWorker], and whether one is needed. */
class ChorePhotoUploader internal constructor(
    private val session: suspend () -> Boolean,
    private val pendingCount: suspend () -> Int,
    private val upload: suspend () -> List<Pair<PendingPhotoUpload, BlobId>>,
    private val unattached: suspend () -> List<Unattached>,
    private val attach: suspend (Unattached) -> Unit,
) {
    @Inject
    constructor(
        sessions: SessionStore,
        uploads: PendingPhotoUploads,
        useCase: UploadChorePhotosUseCase,
        chores: ChoreRepository,
        database: SproutDatabase,
    ) : this(
        session = { sessions.get()?.isLocalTrial == false },
        pendingCount = { uploads.pending().size },
        upload = { useCase() },
        unattached = {
            chores.observeSubmissions(SubmissionFilter.PendingForParent).first().mapNotNull { submission ->
                val ref = submission.photoRef?.takeIf { it.blobId == null } ?: return@mapNotNull null
                val blobId = database.photoDao().get(ref.fileName)?.blobId ?: return@mapNotNull null
                Unattached(submission.id, ref, BlobId(blobId))
            }
        },
        attach = { chores.attachPhotoBlob(it.submissionId, it.photo, it.blobId) },
    )

    /** A photo already uploaded as [blobId] whose submission [submissionId] does not name it yet. */
    data class Unattached(val submissionId: SubmissionId, val photo: PhotoRef, val blobId: BlobId)

    /** Whether a run has anything to do: a real household's session with photos waiting. */
    suspend fun needed(): Boolean = session() && (pendingCount() > 0 || unattached().isNotEmpty())

    /** Uploads the pending photos, then attaches every uploaded photo to its submission. Returns how many. */
    suspend fun uploadAndAttach(): Int {
        if (!session()) return 0
        upload()
        val waiting = unattached()
        waiting.forEach { attach(it) }
        return waiting.size
    }
}

/** Enqueues [UploadChorePhotosWorker] (kept if one is already waiting). */
fun interface PhotoUploadScheduler {
    fun enqueue()
}

class WorkManagerPhotoUploadScheduler @Inject constructor(@param:ApplicationContext private val context: Context) :
    PhotoUploadScheduler {
    override fun enqueue() {
        WorkManager.getInstance(context).enqueueUniqueWork(
            UploadChorePhotosWorker.UNIQUE_NAME,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<UploadChorePhotosWorker>().build(),
        )
    }
}
