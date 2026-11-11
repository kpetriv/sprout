package dev.kpetriv.sprout.core.crypto

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * [BlobStorage] in app-private internal storage: `noBackupFilesDir/sprout_secrets/<name>.bin`, written through
 * [AtomicFile]. `noBackupFilesDir` is the no-backup variant of `filesDir`: wrapped blobs are useless without the
 * device's Keystore key, so they are kept out of backups and device transfer on top of `allowBackup=false` (T14).
 */
class FilesDirBlobStorage(context: Context, private val dispatcher: CoroutineDispatcher) : BlobStorage {
    private val directory: File by lazy { File(context.noBackupFilesDir, DIRECTORY).apply { mkdirs() } }

    override suspend fun read(name: String): ByteArray? = withContext(dispatcher) {
        try {
            fileFor(name).readFully()
        } catch (expected: FileNotFoundException) {
            null
        }
    }

    override suspend fun write(name: String, blob: ByteArray) = withContext(dispatcher) {
        val file = fileFor(name)
        val stream = file.startWrite()
        try {
            stream.write(blob)
            file.finishWrite(stream)
        } catch (e: IOException) {
            file.failWrite(stream)
            throw e
        }
    }

    /** Deletes every file of [name]: the base file and the `.new` and `.bak` siblings [AtomicFile] may leave. */
    override suspend fun delete(name: String) = withContext(dispatcher) { fileFor(name).delete() }

    /**
     * [BlobStorage.rename]'s copy through [AtomicFile]: a read that recovers an interrupted write, an atomic write
     * of [to], then [delete] of [from] with its siblings. A crash in between leaves both copies; running it again
     * finishes.
     */
    override suspend fun rename(from: String, to: String): Boolean {
        val blob = read(from) ?: return false
        write(to, blob)
        delete(from)
        return true
    }

    private fun fileFor(name: String): AtomicFile {
        require(WrappedSecretStore.isValidName(name)) { "Invalid blob name" }
        return AtomicFile(File(directory, "$name$EXTENSION"))
    }

    private companion object {
        const val DIRECTORY = "sprout_secrets"
        const val EXTENSION = ".bin"
    }
}
