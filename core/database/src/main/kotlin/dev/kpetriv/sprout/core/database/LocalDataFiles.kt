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
 *   X25519 private key with its record and stash, the PIN lockout state). Keystore keys (the device signing key and
 *   the key that wraps these blobs) are not files and are never touched. The chat X25519 private key and its record
 *   belong to the device identity ([DEVICE_IDENTITY_SECRETS]): the sign-out of a parent session keeps them, every
 *   other removal deletes them (D-REL-6, D-REL-28).
 * @property blobDirectory the encrypted chat and chore photo blob cache (`noBackupFilesDir/blobs`, M4 §2).
 * @property tablesSetupFile the Times tables setup of each kid on this device (M9-games §6.3, D-M9-G7): the app's
 *   Preferences DataStore `noBackupFilesDir/datastore/games_tables_setup.preferences_pb`, a local preference that is
 *   never synced. It names kid ids, so the wipe deletes it with the household's data.
 */
class LocalDataFiles(
    val databaseFile: File,
    val photoDirectory: File,
    val secretsDirectory: File,
    val blobDirectory: File = File(photoDirectory.parentFile, BlobCache.DIRECTORY_NAME),
    val tablesSetupFile: File = File(
        File(photoDirectory.absoluteFile.parentFile, DATASTORE_DIRECTORY_NAME),
        TABLES_SETUP_FILE_NAME,
    ),
) {
    /**
     * The local preference files the wipe deletes: [tablesSetupFile] and the `.tmp` sibling DataStore writes before
     * it renames it into place.
     */
    val preferenceFiles: List<File>
        get() = listOf(tablesSetupFile, File("${tablesSetupFile.path}.tmp"))

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
        /** Where the app keeps its wiped Preferences DataStore files, under `noBackupFilesDir`. */
        const val DATASTORE_DIRECTORY_NAME: String = "datastore"

        /** The Times tables setup DataStore (`DataStoreTablesSetupStore` in the app). */
        const val TABLES_SETUP_FILE_NAME: String = "games_tables_setup.preferences_pb"

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
            ChatIdentityKeeper.RECORD,
            ChatIdentityKeeper.STASH_KEY,
            ChatIdentityKeeper.STASH_RECORD,
            EncryptedLockoutStateStore.SECRET_NAME,
        )

        /**
         * The secrets that belong to this device's identity rather than to its copy of the household: the chat X25519
         * private key, registered with the household against this device id (M4 §1.1), and its record (household,
         * device id and this device's own chat counters, [ChatIdentityKeeper], D-REL-30). Like the Keystore device
         * signing key, they survive the sign-out wipe of a parent session on this phone, so the same device restored
         * by the same parent finds its registered key again; every other removal deletes them, and before this phone
         * registers as a new device they are moved to a stash (`ForgetChatKeyUseCase`, D-REL-29). A stash is never
         * kept by a wipe.
         */
        val DEVICE_IDENTITY_SECRETS: List<String> = listOf(EncryptionKeyStore.SECRET_NAME, ChatIdentityKeeper.RECORD)
    }
}
