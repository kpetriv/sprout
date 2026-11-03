package dev.kpetriv.sprout.core.database

import dev.kpetriv.sprout.core.crypto.EncryptedLockoutStateStore
import dev.kpetriv.sprout.core.crypto.EncryptionKeyStore
import java.io.File

/**
 * Where this device keeps local data outside Room's own tables, for the purge and the wipe (M3 §6).
 *
 * @property databaseFile the SQLCipher database file (`sprout.db`); its `-wal`, `-shm` and `-journal` siblings
 *   belong to it.
 * @property photoDirectory the encrypted chore photos (`noBackupFilesDir/photos`).
 * @property secretsDirectory the wrapped `SecretStore` blobs (`noBackupFilesDir/sprout_secrets`, core/crypto's
 *   `FilesDirBlobStorage`): every name in [SECRET_NAMES] (the database key, the photo and blob cache keys, the chat
 *   X25519 private key, the PIN lockout state). Keystore keys (the device signing key and the key that wraps these
 *   blobs) are not files and are never touched.
 * @property blobDirectory the encrypted chat and chore photo blob cache (`noBackupFilesDir/blobs`, M4 §2).
 */
class LocalDataFiles(
    val databaseFile: File,
    val photoDirectory: File,
    val secretsDirectory: File,
    val blobDirectory: File = File(photoDirectory.parentFile, BlobCache.DIRECTORY_NAME),
) {
    /** The database file and every sibling SQLite may have created next to it. */
    val databaseFiles: List<File>
        get() = listOf("", "-wal", "-shm", "-journal").map { File(databaseFile.path + it) }

    companion object {
        /** `FilesDirBlobStorage`'s directory under `noBackupFilesDir`. */
        const val SECRETS_DIRECTORY_NAME: String = "sprout_secrets"

        /**
         * Every `SecretStore` name this app writes. The wipe deletes the whole secrets directory, so this list is
         * documentation and a test fixture: a new secret must be added here (and is then covered by the wipe test).
         */
        val SECRET_NAMES: List<String> = listOf(
            DatabaseFactory.PASSPHRASE_SECRET,
            EncryptedPhotoStore.PHOTO_KEY_SECRET,
            BlobCache.BLOB_KEY_SECRET,
            EncryptionKeyStore.SECRET_NAME,
            EncryptedLockoutStateStore.SECRET_NAME,
        )
    }
}
