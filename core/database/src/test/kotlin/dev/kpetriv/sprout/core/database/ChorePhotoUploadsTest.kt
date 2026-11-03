package dev.kpetriv.sprout.core.database

import dev.kpetriv.sprout.core.domainhousehold.Chore
import dev.kpetriv.sprout.core.domainhousehold.ChoreReward
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.domainledger.chat.BlobJson
import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.model.ChoreId
import dev.kpetriv.sprout.core.model.ChoreSchedule
import dev.kpetriv.sprout.core.model.SubmissionStatus
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

@RunWith(RobolectricTestRunner::class)
class ChorePhotoUploadsTest {
    private val parent = parentDevice()
    private val kid = kidDevice()
    private val bed = ChoreId("bed")
    private val today = CalendarDate(2026, 11, 2)

    @After
    fun close() {
        parent.close()
        kid.close()
    }

    private suspend fun linkedWithChore() {
        registerChatDevices(parent, kid)
        parent.addKid(KID_X.value)
        parent.chores.upsert(
            Chore(
                bed,
                HOME,
                KID_X,
                "Bed",
                reward = ChoreReward(money = Money(20)),
                schedule = ChoreSchedule.Daily.EVERY_DAY,
                createdAt = parent.clock.now(),
            ),
        )
        parent.threadKeyMaintenance()
        kid.applier.applyDocuments(parent.queuedDocuments())
    }

    @Test
    fun aSubmittedPhotoIsUploadedWithTheKidsThreadKeyOnce() = runTest {
        linkedWithChore()
        val bytes = byteArrayOf(10, 20, 30, 40)
        val photo = kid.photos.save(KID_X, bytes)
        val submission = kid.chores.submit(bed, today, photo = photo)
        assertEquals(1, kid.photoUploads.pending().size)

        val (upload, blobId) = kid.uploadChorePhotos().single()

        assertEquals(photo, upload.ref)
        assertTrue(kid.photoUploads.pending().isEmpty())
        assertEquals(blobId.value, kid.database.photoDao().get(photo.fileName)?.blobId)
        val header = assertNotNull(BlobJson.decodeOrNull(kid.queuedBlobChunks().single().header))
        assertEquals(KID_X, header.kidId)
        assertNull(header.threadId)
        assertEquals(upload.submissionId, header.submissionId)
        // The parent fetches, verifies and decrypts it with the same thread key.
        assertContentEquals(bytes, parent.chatBlobs.ingest(blobId, kid.queuedBlobChunks()))
        assertTrue(kid.uploadChorePhotos().isEmpty(), "never uploaded twice")
        assertNotNull(submission)
    }

    @Test
    fun theUploadedBlobIdReachesTheParentThroughTheSignedSubmission() = runTest {
        linkedWithChore()
        val bytes = byteArrayOf(5, 6, 7)
        val photo = kid.photos.save(KID_X, bytes)
        val submission = kid.chores.submit(bed, today, photo = photo)
        val (upload, blobId) = kid.uploadChorePhotos().single()

        val updated = assertNotNull(kid.chores.attachPhotoBlob(upload.submissionId, upload.ref, blobId))
        assertEquals(photo.copy(blobId = blobId.value), updated.photoRef)
        assertEquals(blobId.value, kid.chores.getSubmission(submission.id)?.photoRef?.blobId)
        assertNull(kid.chores.attachPhotoBlob(upload.submissionId, upload.ref, blobId), "attached once")

        // The parent's next pull applies the re-signed submission: it now names the blob, which decrypts there.
        parent.applier.applyDocuments(kid.queuedDocuments())
        val received = assertNotNull(parent.chores.getSubmission(submission.id))
        assertEquals(blobId.value, received.photoRef?.blobId)
        assertContentEquals(bytes, parent.chatBlobs.ingest(blobId, kid.queuedBlobChunks()))
    }

    @Test
    fun aBlobIsNotAttachedToAReplacedPhotoOrADecidedSubmission() = runTest {
        linkedWithChore()
        val first = kid.photos.save(KID_X, byteArrayOf(1))
        val submission = kid.chores.submit(bed, today, photo = first)
        val (upload, blobId) = kid.uploadChorePhotos().single()
        // The kid replaced the photo before the upload was recorded on the submission.
        val second = kid.photos.save(KID_X, byteArrayOf(2))
        kid.chores.submit(bed, today, photo = second)
        assertNull(kid.chores.attachPhotoBlob(upload.submissionId, upload.ref, blobId))
        assertNull(kid.chores.getSubmission(submission.id)?.photoRef?.blobId)

        parent.applier.applyDocuments(kid.queuedDocuments())
        parent.chores.decide(submission.id, SubmissionDecision.REJECT)
        kid.applier.applyDocuments(parent.queuedDocuments())
        assertEquals(SubmissionStatus.REJECTED, kid.chores.getSubmission(submission.id)?.status)
        assertNull(kid.chores.attachPhotoBlob(submission.id, second, blobId), "decided submissions stay as they are")
    }

    @Test
    fun aPhotoWaitsWhileTheThreadHasNoKey() = runTest {
        registerChatDevices(parent, kid)
        parent.addKid(KID_X.value)
        parent.chores.upsert(
            Chore(
                bed,
                HOME,
                KID_X,
                "Bed",
                reward = ChoreReward(money = Money(20)),
                schedule = ChoreSchedule.Daily.EVERY_DAY,
                createdAt = parent.clock.now(),
            ),
        )
        kid.applier.applyDocuments(parent.queuedDocuments())
        kid.chores.submit(bed, today, photo = kid.photos.save(KID_X, byteArrayOf(1)))

        assertTrue(kid.uploadChorePhotos().isEmpty())
        assertEquals(1, kid.photoUploads.pending().size, "retried on the next run")
    }

    @Test
    fun theOrphanSweepDeletesOnlyOldUnattachedPhotos() = runTest {
        linkedWithChore()
        val orphan = kid.photos.save(KID_X, byteArrayOf(1))
        val attached = kid.photos.save(KID_X, byteArrayOf(2))
        kid.chores.submit(bed, today, photo = attached)
        kid.clock.advanceBy(25.hours)
        val fresh = kid.photos.save(KID_X, byteArrayOf(3))

        assertEquals(1, kid.sweepOrphanPhotos())

        assertNull(kid.database.photoDao().get(orphan.fileName))
        assertNotNull(kid.database.photoDao().get(attached.fileName))
        assertNotNull(kid.database.photoDao().get(fresh.fileName))
        assertTrue(kid.photoDirectory.listFiles().orEmpty().none { it.name == orphan.fileName })
    }
}
