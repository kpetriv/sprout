package dev.kpetriv.sprout.core.crypto

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [FilesDirBlobStorage]'s delete and rename cover every file `AtomicFile` may leave (review L2, D-REL-29). */
@RunWith(RobolectricTestRunner::class)
class FilesDirBlobStorageTest {
    private val context = RuntimeEnvironment.getApplication()
    private val directory = File(context.noBackupFilesDir, "sprout_secrets")
    private val storage = FilesDirBlobStorage(context, Dispatchers.Unconfined)

    private fun names() = directory.listFiles().orEmpty().map { it.name }.toSet()

    @After
    fun clean() {
        directory.deleteRecursively()
    }

    @Test
    fun `delete removes the blob with its new and bak siblings`() = runTest {
        storage.write("a", byteArrayOf(1))
        File(directory, "a.bin.new").writeBytes(byteArrayOf(2))
        File(directory, "a.bin.bak").writeBytes(byteArrayOf(3))

        storage.delete("a")

        assertTrue(names().isEmpty())
    }

    @Test
    fun `rename moves the blob, replaces the target and leaves nothing of the source`() = runTest {
        storage.write("a", byteArrayOf(1))
        storage.write("b", byteArrayOf(9))
        File(directory, "a.bin.new").writeBytes(byteArrayOf(2)) // A write that never finished.

        assertTrue(storage.rename("a", "b"))

        assertEquals(setOf("b.bin"), names())
        assertContentEquals(byteArrayOf(1), storage.read("b"))
        assertNull(storage.read("a"))
        assertFalse(storage.rename("a", "b"), "nothing left to move")
        assertContentEquals(byteArrayOf(1), storage.read("b"))
    }

    @Test
    fun `rename recovers a blob only its backup holds`() = runTest {
        directory.mkdirs()
        File(directory, "a.bin.bak").writeBytes(byteArrayOf(5)) // AtomicFile's legacy backup of an interrupted write.

        assertTrue(storage.rename("a", "b"))

        assertContentEquals(byteArrayOf(5), storage.read("b"))
        assertEquals(setOf("b.bin"), names())
    }
}
