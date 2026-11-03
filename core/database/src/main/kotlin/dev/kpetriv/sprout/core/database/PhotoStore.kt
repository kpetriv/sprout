package dev.kpetriv.sprout.core.database

import androidx.room.withTransaction
import dev.kpetriv.sprout.core.crypto.Aead
import dev.kpetriv.sprout.core.crypto.SecretStore
import dev.kpetriv.sprout.core.database.entity.AuditAction
import dev.kpetriv.sprout.core.database.entity.PhotoEntity
import dev.kpetriv.sprout.core.domainledger.JvmSha256Hasher
import dev.kpetriv.sprout.core.domainledger.toHex
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.PhotoRef
import dev.kpetriv.sprout.core.time.Clock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.util.UUID

/**
 * Chore photos on this device (D-M2-3: local only in M2, uploaded encrypted with the household key in M4). Bytes are
 * encrypted at rest; a [PhotoRef] names the file and the SHA-256 of its plaintext.
 */
interface PhotoStore {
    /** Encrypts and stores [bytes] for [kidId]; returns the reference to attach to a submission. */
    suspend fun save(kidId: KidId, bytes: ByteArray): PhotoRef

    /**
     * The plaintext of [ref]. Throws [PhotoIntegrityException] if the photo is unknown, its file is missing, it does
     * not decrypt (tampered, swapped or corrupt) or its SHA-256 differs from [ref]'s.
     */
    suspend fun load(ref: PhotoRef): ByteArray

    /** Deletes the photo's file and row. False if there was no such photo. */
    suspend fun delete(ref: PhotoRef): Boolean
}

/** A stored photo cannot be trusted or read. The message never includes photo content. */
class PhotoIntegrityException(message: String, cause: Throwable? = null) : GeneralSecurityException(message, cause)

/**
 * [PhotoStore] that writes each photo to `<directory>/<fileName>` (in production `noBackupFilesDir/photos`, so it is
 * excluded from cloud backups) encrypted with [aead] under the per-install key [PHOTO_KEY_SECRET] from
 * [secretStore]. The associated data binds each ciphertext to its file name, so swapping two files makes both fail.
 * Files are written to a temporary name and renamed, so a crash never leaves a partial photo under a real name.
 */
@Suppress("LongParameterList") // Storage, crypto, audit and dispatch are all separate collaborators.
class EncryptedPhotoStore(
    private val database: SproutDatabase,
    private val directory: File,
    private val aead: Aead,
    private val secretStore: SecretStore,
    private val authors: SessionAuthorProvider,
    private val clock: Clock,
    private val dispatcher: CoroutineDispatcher,
) : PhotoStore {
    private val photos = database.photoDao()
    private val audit = database.auditLogDao()

    override suspend fun save(kidId: KidId, bytes: ByteArray): PhotoRef {
        require(bytes.isNotEmpty()) { "a photo must not be empty" }
        val author = authors.currentAuthor()
        val ref = PhotoRef(fileName = "${UUID.randomUUID()}$EXTENSION", sha256 = sha256Hex(bytes))
        val ciphertext = secretStore.getOrCreate(PHOTO_KEY_SECRET).use { key ->
            aead.encrypt(key, bytes, associatedData(ref.fileName))
        }
        withContext(dispatcher) { writeAtomically(fileOf(ref), ciphertext) }
        database.withTransaction {
            photos.insert(PhotoEntity(ref.fileName, ref.sha256, kidId, null, clock.now().toEpochMilliseconds()))
            audit.insert(
                auditEntry(author, clock.now().toEpochMilliseconds(), AuditAction.PHOTO_SAVED, ref.fileName) {
                    put("kidId", kidId.value)
                },
            )
        }
        return ref
    }

    override suspend fun load(ref: PhotoRef): ByteArray {
        val stored = photos.get(ref.fileName)
        if (stored == null || stored.sha256 != ref.sha256) throw PhotoIntegrityException("unknown photo")
        val ciphertext = withContext(dispatcher) {
            try {
                fileOf(ref).readBytes()
            } catch (e: IOException) {
                throw PhotoIntegrityException("photo file unreadable", e)
            }
        }
        val plaintext = try {
            secretStore.getOrCreate(PHOTO_KEY_SECRET, createIfMissing = false).use { key ->
                aead.decrypt(key, ciphertext, associatedData(ref.fileName))
            }
        } catch (e: GeneralSecurityException) {
            throw PhotoIntegrityException("photo failed to decrypt", e)
        }
        if (sha256Hex(plaintext) != ref.sha256) throw PhotoIntegrityException("photo hash mismatch")
        return plaintext
    }

    override suspend fun delete(ref: PhotoRef): Boolean {
        val author = authors.currentAuthor()
        val deleted = database.withTransaction {
            (photos.delete(ref.fileName) > 0).also { deleted ->
                if (deleted) {
                    audit.insert(
                        auditEntry(author, clock.now().toEpochMilliseconds(), AuditAction.PHOTO_DELETED, ref.fileName),
                    )
                }
            }
        }
        withContext(dispatcher) { fileOf(ref).delete() }
        return deleted
    }

    /** The photo's file, which [PhotoRef]'s file-name rules keep inside [directory]. */
    private fun fileOf(ref: PhotoRef): File {
        val file = File(directory, ref.fileName)
        check(file.canonicalFile.parentFile == directory.canonicalFile) { "photo outside the photo directory" }
        return file
    }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        directory.mkdirs()
        val temp = File(directory, target.name + TEMP_SUFFIX)
        temp.writeBytes(bytes)
        if (!temp.renameTo(target)) {
            temp.delete()
            throw IOException("could not store photo")
        }
    }

    companion object {
        /** [SecretStore] name of the per-install photo key (replaced by the household key in M4). */
        const val PHOTO_KEY_SECRET: String = "photos.key"

        /** Subdirectory of `noBackupFilesDir` holding the encrypted photos. */
        const val DIRECTORY_NAME: String = "photos"

        private const val EXTENSION = ".photo"
        private const val TEMP_SUFFIX = ".tmp"
        private const val AAD_LABEL = "sprout/chore-photo/v1"

        /** `UTF-8("sprout/chore-photo/v1") || 0x00 || fileName`. */
        internal fun associatedData(fileName: String): ByteArray =
            AAD_LABEL.encodeToByteArray() + 0.toByte() + fileName.encodeToByteArray()

        internal fun sha256Hex(bytes: ByteArray): String = JvmSha256Hasher.sha256(bytes).toHex()
    }
}
