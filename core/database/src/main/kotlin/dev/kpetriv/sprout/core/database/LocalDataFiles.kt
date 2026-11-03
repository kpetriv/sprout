package dev.kpetriv.sprout.core.database

import java.io.File

/**
 * Where this device keeps local data outside Room's own tables, for the purge and the wipe (M3 §6).
 *
 * @property databaseFile the SQLCipher database file (`sprout.db`); its `-wal`, `-shm` and `-journal` siblings
 *   belong to it.
 * @property photoDirectory the encrypted chore photos (`noBackupFilesDir/photos`).
 * @property secretsDirectory the wrapped `SecretStore` blobs (`noBackupFilesDir/sprout_secrets`, core/crypto's
 *   `FilesDirBlobStorage`): the database key, the photo key, the PIN lockout state. Keystore keys (the device signing
 *   key and the key that wraps these blobs) are not files and are never touched.
 */
class LocalDataFiles(val databaseFile: File, val photoDirectory: File, val secretsDirectory: File) {
    /** The database file and every sibling SQLite may have created next to it. */
    val databaseFiles: List<File>
        get() = listOf("", "-wal", "-shm", "-journal").map { File(databaseFile.path + it) }

    companion object {
        /** `FilesDirBlobStorage`'s directory under `noBackupFilesDir`. */
        const val SECRETS_DIRECTORY_NAME: String = "sprout_secrets"
    }
}
