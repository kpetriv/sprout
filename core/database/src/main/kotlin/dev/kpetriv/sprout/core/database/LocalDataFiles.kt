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
 *   blobs) are not files and are never touched. The chat X25519 private key belongs to the device identity
 *   ([DEVICE_IDENTITY_SECRETS]): a sign-out keeps it, every other removal deletes it.
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

    /**
     * The files in [secretsDirectory] that hold the `SecretStore` blob [name]: `FilesDirBlobStorage`'s `<name>.bin`
     * and the `.new` and `.bak` siblings its `AtomicFile` may leave during or after a write.
     */
    fun secretFiles(name: String): List<File> =
        listOf("", ".new", ".bak").map { File(secretsDirectory, "$name$SECRET_FILE_EXTENSION$it") }

    companion object {
        /** `FilesDirBlobStorage`'s directory under `noBackupFilesDir`. */
        const val SECRETS_DIRECTORY_NAME: String = "sprout_secrets"

        /** `FilesDirBlobStorage`'s file name extension. */
        const val SECRET_FILE_EXTENSION: String = ".bin"

        /**
         * Every `SecretStore` name this app writes. The wipe deletes the whole secrets directory (except
         * [DEVICE_IDENTITY_SECRETS] on a sign-out), so this list is documentation and a test fixture: a new secret
         * must be added here (and is then covered by the wipe test).
         */
        val SECRET_NAMES: List<String> = listOf(
            DatabaseFactory.PASSPHRASE_SECRET,
            EncryptedPhotoStore.PHOTO_KEY_SECRET,
            BlobCache.BLOB_KEY_SECRET,
            EncryptionKeyStore.SECRET_NAME,
            EncryptedLockoutStateStore.SECRET_NAME,
        )

        /**
         * The secrets that belong to this device's identity rather than to its copy of the household: the chat X25519
         * private key, registered with the household against this device id (M4 §1.1). Like the Keystore device
         * signing key, they survive a sign-out wipe on this phone, so the same device restored by the same parent
         * finds its registered key again; they are deleted by every other removal and before this phone registers
         * as a new device (`ForgetChatKeyUseCase`).
         */
        val DEVICE_IDENTITY_SECRETS: List<String> = listOf(EncryptionKeyStore.SECRET_NAME)
    }
}
