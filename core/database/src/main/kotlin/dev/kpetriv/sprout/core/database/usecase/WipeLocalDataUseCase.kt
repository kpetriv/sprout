package dev.kpetriv.sprout.core.database.usecase

import dev.kpetriv.sprout.core.common.Dispatcher
import dev.kpetriv.sprout.core.common.SproutDispatchers
import dev.kpetriv.sprout.core.database.LocalDataFiles
import dev.kpetriv.sprout.core.database.SessionStore
import dev.kpetriv.sprout.core.database.SproutDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Erases this device's household data when the household is gone (`SyncError.HouseholdGone`, deletion, or this
 * device was revoked, or this phone signed out; M3 §6): clears the session and every pull cursor, closes the database,
 * deletes the database files (and their WAL
 * siblings), the encrypted photo directory and every wrapped `SecretStore` blob (database key, photo key, PIN lockout
 * state). The Keystore keys stay: the device signing key keeps this device's identity, and the wrapping key is
 * useless without the blobs.
 *
 * It returns normally; the database instance is closed, so the caller must restart the process (which lands on
 * onboarding, since there is no session). Deleting is best effort per file: a file that cannot be deleted does not
 * stop the others.
 */
class WipeLocalDataUseCase @Inject constructor(
    private val database: SproutDatabase,
    private val sessions: SessionStore,
    private val files: LocalDataFiles,
    @param:Dispatcher(SproutDispatchers.IO) private val dispatcher: CoroutineDispatcher,
) {
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // The files go whatever state the database is in.
    suspend operator fun invoke() {
        try {
            sessions.clear()
            database.syncCursorDao().deleteAll()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (unreadable: Exception) {
            // A database that cannot be read is deleted below anyway.
        }
        database.close()
        withContext(dispatcher) {
            files.databaseFiles.forEach { it.delete() }
            files.photoDirectory.deleteRecursively()
            files.secretsDirectory.listFiles()?.forEach { it.deleteRecursively() }
        }
    }
}
