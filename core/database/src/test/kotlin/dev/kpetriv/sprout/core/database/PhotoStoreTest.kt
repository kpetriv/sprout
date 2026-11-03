package dev.kpetriv.sprout.core.database

import dev.kpetriv.sprout.core.crypto.AesGcmAead
import dev.kpetriv.sprout.core.crypto.InMemoryBlobStorage
import dev.kpetriv.sprout.core.crypto.InMemoryKeyWrapper
import dev.kpetriv.sprout.core.crypto.WrappedSecretStore
import dev.kpetriv.sprout.core.database.entity.AuditAction
import dev.kpetriv.sprout.core.domainhousehold.Chore
import dev.kpetriv.sprout.core.domainhousehold.ChoreReward
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.model.ChoreId
import dev.kpetriv.sprout.core.model.ChoreSchedule
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.PhotoRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.security.SecureRandom
import java.util.UUID
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class PhotoStoreTest {
    private val household = TestHousehold()
    private val directory = File(RuntimeEnvironment.getApplication().noBackupFilesDir, "photos-${UUID.randomUUID()}")
    private val secrets = WrappedSecretStore(
        InMemoryKeyWrapper(),
        InMemoryBlobStorage(),
        SecureRandom(),
        Dispatchers.Unconfined,
    )
    private val store = EncryptedPhotoStore(
        household.database,
        directory,
        AesGcmAead(SecureRandom()),
        secrets,
        household.authors,
        household.clock,
        Dispatchers.Unconfined,
    )
    private val kid = KidId("kid-leo")
    private val photo = Random(7).nextBytes(4_096)

    @After
    fun close() {
        household.close()
        directory.deleteRecursively()
    }

    @Test
    fun aPhotoRoundTripsAndIsEncryptedAtRest() = runTest {
        val ref = store.save(kid, photo)

        assertEquals(EncryptedPhotoStore.sha256Hex(photo), ref.sha256)
        assertContentEquals(photo, store.load(ref))
        val onDisk = File(directory, ref.fileName).readBytes()
        assertNotEquals(photo.size, onDisk.size)
        assertFalse(onDisk.toList().windowed(32).any { it == photo.take(32) }, "plaintext must not be on disk")
        assertEquals(kid, household.database.photoDao().get(ref.fileName)?.kidId)
        assertEquals(1, household.database.auditLogDao().all().count { it.action == AuditAction.PHOTO_SAVED })
        // No temporary file is left behind.
        assertEquals(listOf(ref.fileName), directory.list()?.toList())
    }

    @Test
    fun aTamperedOrSwappedFileIsDetected() = runTest {
        val ref = store.save(kid, photo)
        val other = store.save(kid, Random(8).nextBytes(1_000))
        val file = File(directory, ref.fileName)
        val original = file.readBytes()

        file.writeBytes(original.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() })
        assertFailsWith<PhotoIntegrityException> { store.load(ref) }

        // The other photo's (valid) ciphertext under this name fails too: it is bound to its own file name.
        file.writeBytes(File(directory, other.fileName).readBytes())
        assertFailsWith<PhotoIntegrityException> { store.load(ref) }

        file.writeBytes(original)
        assertContentEquals(photo, store.load(ref))
        // A reference with the wrong hash, an unknown photo, and a missing file are all refused.
        assertFailsWith<PhotoIntegrityException> { store.load(ref.copy(sha256 = other.sha256)) }
        assertFailsWith<PhotoIntegrityException> { store.load(PhotoRef("unknown.photo", ref.sha256)) }
        file.delete()
        assertFailsWith<PhotoIntegrityException> { store.load(ref) }
    }

    @Test
    fun deleteRemovesTheFileAndTheRow() = runTest {
        val ref = store.save(kid, photo)
        assertTrue(store.delete(ref))
        assertFalse(store.delete(ref))
        assertFalse(File(directory, ref.fileName).exists())
        assertFailsWith<PhotoIntegrityException> { store.load(ref) }
        assertFailsWith<IllegalArgumentException> { store.save(kid, ByteArray(0)) }
    }

    @Test
    fun aSubmittedPhotoIsAttachedToItsSubmission() = runTest {
        val chore = Chore(
            ChoreId("dishes"),
            LocalOnlyAuthorProvider.LOCAL_HOUSEHOLD,
            kid,
            "Dishes",
            reward = ChoreReward(money = Money(10)),
            schedule = ChoreSchedule.Once(),
            createdAt = household.clock.now(),
        )
        household.chores.upsert(chore)
        val ref = store.save(kid, photo)
        val wrongKid = store.save(KidId("kid-zoe"), photo)

        assertFailsWith<IllegalArgumentException> {
            household.chores.submit(chore.id, CalendarDate(2026, 11, 2), photo = wrongKid)
        }
        val submission = household.chores.submit(chore.id, CalendarDate(2026, 11, 2), photo = ref)

        assertEquals(ref, submission.photoRef)
        assertEquals(submission.id, household.database.photoDao().get(ref.fileName)?.submissionId)
        assertContentEquals(photo, store.load(household.chores.getSubmission(submission.id)!!.photoRef!!))
    }
}
