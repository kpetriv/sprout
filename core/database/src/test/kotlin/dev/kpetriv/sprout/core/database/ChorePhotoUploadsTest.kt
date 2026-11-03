package dev.kpetriv.sprout.core.database

import dev.kpetriv.sprout.core.database.entity.AuditAction
import dev.kpetriv.sprout.core.database.entity.OutboxKind
import dev.kpetriv.sprout.core.domainhousehold.Chore
import dev.kpetriv.sprout.core.domainhousehold.ChoreReward
import dev.kpetriv.sprout.core.domainledger.JvmSha256Hasher
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.domainledger.chat.BlobJson
import dev.kpetriv.sprout.core.domainledger.toHex
import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.model.ChoreId
import dev.kpetriv.sprout.core.model.ChoreSchedule
import dev.kpetriv.sprout.core.model.SubmissionId
import dev.kpetriv.sprout.core.model.SubmissionStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
        parent.confirmThreadKeys()
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
        // The parent applies the re-signed submission, then fetches, verifies and decrypts the photo it names.
        parent.applier.applyDocuments(kid.queuedDocuments())
        val target = BlobTarget.ChorePhoto(KID_X, upload.submissionId)
        val expected = BlobExpectation(target, plaintextSha256 = photo.sha256)
        assertContentEquals(bytes, parent.chatBlobs.ingest(blobId, kid.queuedBlobChunks(), expected))
        assertTrue(kid.uploadChorePhotos().isEmpty(), "never uploaded twice")
        assertNotNull(submission)
    }

    @Test
    fun queuingTheChunksMarkingThePhotoAndAttachingTheBlobHappenTogetherAndInPushOrder() = runTest {
        linkedWithChore()
        val bytes = byteArrayOf(5, 6, 7)
        val photo = kid.photos.save(KID_X, bytes)
        val submission = kid.chores.submit(bed, today, photo = photo)
        val (_, blobId) = kid.uploadChorePhotos().single()

        assertEquals(blobId.value, kid.chores.getSubmission(submission.id)?.photoRef?.blobId)
        assertEquals(blobId.value, kid.database.photoDao().get(photo.fileName)?.blobId)
        val rows = kid.database.outboxDao().all()
        val chunkSeq = rows.single { it.kind == OutboxKind.BLOB_CHUNK }.seq
        val submissionSeq = rows.single { it.kind == OutboxKind.DOCUMENT && it.docId == submission.id.value }.seq
        assertTrue(chunkSeq < submissionSeq, "the blob is pushed before the submission that names it")
        assertNull(kid.chores.attachPhotoBlob(submission.id, photo, blobId), "attached once")

        // The parent's next pull applies the re-signed submission: it now names the blob, which decrypts there.
        parent.applier.applyDocuments(kid.queuedDocuments())
        val received = assertNotNull(parent.chores.getSubmission(submission.id))
        assertEquals(blobId.value, received.photoRef?.blobId)
        val ref = assertNotNull(received.photoRef)
        val expected = BlobExpectation(BlobTarget.ChorePhoto(received.kidId, received.id), plaintextSha256 = ref.sha256)
        assertContentEquals(bytes, parent.chatBlobs.ingest(blobId, kid.queuedBlobChunks(), expected))
        // A blob whose bytes are not the submission's photo is refused.
        val other = BlobExpectation(
            BlobTarget.ChorePhoto(KID_X, submission.id),
            plaintextSha256 = JvmSha256Hasher.sha256(byteArrayOf(1)).toHex(),
        )
        assertEquals(
            BlobUnavailableException.Reason.INVALID,
            assertFailsWith<BlobUnavailableException> { parent.chatBlobs.open(blobId, other) }.reason,
        )
    }

    @Test
    fun aBlobNamedByTwoSubmissionsOpensOnlyForTheOneItWasUploadedFor() = runTest {
        linkedWithChore()
        val bytes = byteArrayOf(8, 9)
        val photo = kid.photos.save(KID_X, bytes)
        val genuine = kid.chores.submit(bed, today, photo = photo)
        val (_, blobId) = kid.uploadChorePhotos().single()
        parent.applier.applyDocuments(kid.queuedDocuments())
        val chunks = kid.queuedBlobChunks()
        // Another submission (a crafted one, audit A2-M1) names the same blob with the same hash: its binding is
        // the submission on screen, which the blob's signed header and AAD do not name.
        val copycat = BlobExpectation(
            BlobTarget.ChorePhoto(KID_X, SubmissionId("copycat")),
            plaintextSha256 = photo.sha256,
        )
        assertEquals(
            BlobUnavailableException.Reason.INVALID,
            assertFailsWith<BlobUnavailableException> { parent.chatBlobs.ingest(blobId, chunks, copycat) }.reason,
        )
        val genuineExpectation =
            BlobExpectation(BlobTarget.ChorePhoto(KID_X, genuine.id), plaintextSha256 = photo.sha256)
        assertContentEquals(bytes, parent.chatBlobs.ingest(blobId, chunks, genuineExpectation))
        // Cached now: the copycat is still refused from the cache.
        assertEquals(
            BlobUnavailableException.Reason.INVALID,
            assertFailsWith<BlobUnavailableException> { parent.chatBlobs.open(blobId, copycat) }.reason,
        )
    }

    @Test
    fun concurrentRunsUploadEachPhotoOnce() = runTest {
        linkedWithChore()
        kid.chores.submit(bed, today, photo = kid.photos.save(KID_X, byteArrayOf(4, 4)))
        val runs = List(3) { async { kid.uploadChorePhotos() } }.awaitAll()

        assertEquals(1, runs.sumOf { it.size })
        assertEquals(1, kid.database.outboxDao().all().count { it.kind == OutboxKind.BLOB_CHUNK })
        assertEquals(1, kid.database.auditLogDao().all().count { it.action == AuditAction.BLOB_QUEUED })
    }

    @Test
    fun aReplacedPhotoOrADecidedSubmissionIsNeverUploaded() = runTest {
        linkedWithChore()
        val first = kid.photos.save(KID_X, byteArrayOf(1))
        val submission = kid.chores.submit(bed, today, photo = first)
        // The kid replaced the photo before any upload: only the new one is pending and uploaded.
        val second = kid.photos.save(KID_X, byteArrayOf(2))
        kid.chores.submit(bed, today, photo = second)
        assertEquals(listOf(second.fileName), kid.photoUploads.pending().map { it.ref.fileName })
        val (upload, blobId) = kid.uploadChorePhotos().single()
        assertEquals(second.fileName, upload.ref.fileName)
        assertEquals(blobId.value, kid.chores.getSubmission(submission.id)?.photoRef?.blobId)
        assertNull(kid.database.photoDao().get(first.fileName)?.blobId)

        parent.applier.applyDocuments(kid.queuedDocuments())
        parent.chores.decide(submission.id, SubmissionDecision.REJECT)
        kid.applier.applyDocuments(parent.queuedDocuments())
        assertEquals(SubmissionStatus.REJECTED, kid.chores.getSubmission(submission.id)?.status)
        assertTrue(kid.photoUploads.pending().isEmpty(), "decided submissions stay as they are")
        assertTrue(kid.uploadChorePhotos().isEmpty())
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
