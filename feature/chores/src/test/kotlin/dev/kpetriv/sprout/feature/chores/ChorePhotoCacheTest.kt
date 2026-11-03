package dev.kpetriv.sprout.feature.chores

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Audit L1: plaintext camera captures never outlive the next capture or the next app start. */
@RunWith(RobolectricTestRunner::class)
class ChorePhotoCacheTest {
    private val context = RuntimeEnvironment.getApplication()
    private val directory = File(context.cacheDir, "chore_photos")

    private fun leftover(name: String) = File(
        directory.apply {
            mkdirs()
        },
        name,
    ).apply { writeBytes(byteArrayOf(1, 2)) }

    @Test
    fun clearDeletesEveryLeftoverCapture() {
        leftover("capture-a.jpg")
        leftover("capture-b.jpg")
        File(directory, "nested").mkdirs()

        assertEquals(3, ChorePhotoCache(context).clear())
        assertTrue(directory.listFiles().orEmpty().isEmpty())
        // Nothing to clear is fine.
        assertEquals(0, ChorePhotoCache(context).clear())
    }

    @Test
    fun aNewCaptureStartsFromAnEmptyDirectory() {
        val old = leftover("capture-old.jpg")

        val fresh = ChorePhotoFiles.newCaptureFile(context)

        assertTrue(!old.exists(), "a capture left over from an earlier attempt is deleted")
        assertEquals(directory.canonicalFile, fresh.parentFile?.canonicalFile)
        assertTrue(directory.listFiles().orEmpty().isEmpty(), "the new capture file is created by the camera app")
    }
}
