package dev.kpetriv.sprout.core.database.usecase

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.kpetriv.sprout.core.common.Dispatcher
import dev.kpetriv.sprout.core.common.SproutDispatchers
import dev.kpetriv.sprout.core.database.LocalDataFiles
import dev.kpetriv.sprout.core.database.SessionStore
import dev.kpetriv.sprout.core.database.SproutDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * Erases this device's household data when the household is gone (`SyncError.HouseholdGone`, deletion, or this
 * device was revoked, or this phone signed out; M3 §6): clears the session and every pull cursor, closes the database,
 * deletes the database files (and their WAL siblings; with them every chat message, thread key and read mark), the
 * encrypted photo directory, the encrypted blob cache (M4) and every wrapped `SecretStore` blob
 * ([LocalDataFiles.SECRET_NAMES]: database key, photo and blob cache keys, the chat X25519 private key
 * `chat.x25519.v1`, PIN lockout state), and everything in the app's `cacheDir` (audit A2-M2: temporary voice note
 * recordings and captures; nothing there is needed again). The Keystore keys stay: the device signing key keeps this
 * device's identity, and the wrapping key is useless without the blobs.
 *
 * The database instance is closed afterwards, so the caller must restart the process (which lands on onboarding,
 * since there is no session). Deleting is best effort per file: a file that cannot be deleted does not stop the
 * others, and a database that cannot be cleared or closed does not stop the files. The result says whether the wipe
 * is complete: true only when no database file, no photo or blob directory and no `SecretStore` blob is left, so the
 * caller can keep the removal and try again (audit L4). `cacheDir` is cleared best effort and not part of that answer:
 * the platform and libraries may write to it at any time, and the voice note temp files in it are also swept at every
 * start.
 *
 * @param cacheDirectory the app's `cacheDir`, whose contents are deleted (the directory itself stays); null in tests
 *   that do not need it.
 */
class WipeLocalDataUseCase internal constructor(
    private val database: SproutDatabase,
    private val sessions: SessionStore,
    private val files: LocalDataFiles,
    private val dispatcher: CoroutineDispatcher,
    private val cacheDirectory: File? = null,
) {
    @Inject
    constructor(
        database: SproutDatabase,
        sessions: SessionStore,
        files: LocalDataFiles,
        @Dispatcher(SproutDispatchers.IO) dispatcher: CoroutineDispatcher,
        @ApplicationContext context: Context,
    ) : this(database, sessions, files, dispatcher, context.cacheDir)

    /** Erases everything listed above; true when it is all gone. */
    suspend operator fun invoke(): Boolean {
        bestEffort {
            sessions.clear()
            database.syncCursorDao().deleteAll()
        }
        // A database that cannot be read, or is already closed, is deleted below anyway.
        bestEffort { database.close() }
        return withContext(dispatcher) {
            files.databaseFiles.forEach { it.delete() }
            files.photoDirectory.deleteRecursively()
            files.blobDirectory.deleteRecursively()
            files.secretsDirectory.listFiles()?.forEach { it.deleteRecursively() }
            cacheDirectory?.listFiles()?.forEach { it.deleteRecursively() }
            files.databaseFiles.none { it.exists() } &&
                !files.photoDirectory.exists() &&
                !files.blobDirectory.exists() &&
                files.secretsDirectory.listFiles().isNullOrEmpty()
        }
    }

    // Dropped unlogged: a storage failure may carry personal data, and the files go whatever state the database is in.
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private suspend fun bestEffort(step: suspend () -> Unit) {
        try {
            step()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            // See above.
        }
    }
}
