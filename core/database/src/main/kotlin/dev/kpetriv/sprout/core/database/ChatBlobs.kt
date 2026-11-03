package dev.kpetriv.sprout.core.database

import androidx.room.withTransaction
import dev.kpetriv.sprout.core.crypto.Aead
import dev.kpetriv.sprout.core.crypto.DeviceSigner
import dev.kpetriv.sprout.core.crypto.SecretStore
import dev.kpetriv.sprout.core.crypto.chat.BlobDecryptionException
import dev.kpetriv.sprout.core.crypto.chat.EncryptedBlob
import dev.kpetriv.sprout.core.database.entity.AuditAction
import dev.kpetriv.sprout.core.database.entity.BlobEntity
import dev.kpetriv.sprout.core.database.entity.OutboxEntity
import dev.kpetriv.sprout.core.database.entity.OutboxKind
import dev.kpetriv.sprout.core.domainledger.EventAuthor
import dev.kpetriv.sprout.core.domainledger.JvmSha256Hasher
import dev.kpetriv.sprout.core.domainledger.LedgerJson
import dev.kpetriv.sprout.core.domainledger.SignatureCheck
import dev.kpetriv.sprout.core.domainledger.chat.BlobChunkHeader
import dev.kpetriv.sprout.core.domainledger.chat.BlobContext
import dev.kpetriv.sprout.core.domainledger.chat.BlobJson
import dev.kpetriv.sprout.core.domainledger.toHex
import dev.kpetriv.sprout.core.model.BlobId
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.model.SubmissionId
import dev.kpetriv.sprout.core.model.ThreadId
import dev.kpetriv.sprout.core.sync.BlobChunkJson
import dev.kpetriv.sprout.core.time.Clock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.util.UUID
import javax.inject.Inject

/** What a blob is for (M4 §2): a chat thread's photo or voice note, or a chore submission's photo (D-M4-5). */
sealed interface BlobTarget {
    /** The kid whose thread key encrypts the blob. */
    val kidId: KidId

    data class Thread(val threadId: ThreadId) : BlobTarget {
        override val kidId: KidId get() = threadId.kidId
    }

    data class ChorePhoto(override val kidId: KidId, val submissionId: SubmissionId) : BlobTarget
}

/** A blob in the local cache: its AEAD [context], the hex [sha256] of its E2EE [ciphertext], and the ciphertext. */
class CachedBlob(val context: BlobContext, val sha256: String, val ciphertext: ByteArray) {
    override fun toString(): String = "CachedBlob(blobId=${context.blobId}, bytes=${ciphertext.size})"
}

/**
 * The local blob cache (M4 §2): each blob's E2EE ciphertext in `<directory>/<blobId>.blob` (production:
 * `noBackupFilesDir/blobs`, outside cloud backups), encrypted once more with the per-install key [BLOB_KEY_SECRET]
 * (defence in depth: the bytes are already end-to-end encrypted), indexed in `blobs`. Bounded by [maxBytes]: the least
 * recently used blobs are evicted. A file that is missing, does not decrypt or does not match its hash is dropped
 * from the index and reads as absent (it is fetched again).
 */
@Suppress("LongParameterList") // Storage, crypto, time and dispatch are separate collaborators.
class BlobCache(
    private val database: SproutDatabase,
    private val directory: File,
    private val aead: Aead,
    private val secretStore: SecretStore,
    private val clock: Clock,
    private val dispatcher: CoroutineDispatcher,
    private val maxBytes: Long = MAX_BYTES,
) {
    private val blobs = database.blobDao()

    /** Caches [ciphertext] (the whole E2EE blob, hash [sha256]) under its context's blob id, then evicts. */
    suspend fun put(context: BlobContext, sha256: String, ciphertext: ByteArray) {
        val blobId = context.blobId.value
        val fileName = "$blobId$EXTENSION"
        val sealed = secretStore.getOrCreate(BLOB_KEY_SECRET).use { key ->
            aead.encrypt(key, ciphertext, associatedData(blobId, sha256))
        }
        withContext(dispatcher) { writeAtomically(fileOf(fileName), sealed) }
        val now = clock.now().toEpochMilliseconds()
        blobs.upsert(
            BlobEntity(
                blobId = blobId,
                fileName = fileName,
                sha256 = sha256,
                size = ciphertext.size.toLong(),
                contextJson = LedgerJson.json.encodeToString(BlobContext.serializer(), context),
                lastUsedEpochMs = now,
            ),
        )
        evict(keep = blobId)
    }

    /** The cached blob [blobId] (and marks it used), or null when it is not cached or its file is unusable. */
    @Suppress("ReturnCount") // Absent, then unusable.
    suspend fun get(blobId: BlobId): CachedBlob? {
        val row = blobs.get(blobId.value) ?: return null
        val context = try {
            LedgerJson.json.decodeFromString(BlobContext.serializer(), row.contextJson)
        } catch (expected: IllegalArgumentException) {
            null
        }
        val ciphertext = context?.let { readOrNull(row) }
        if (context == null || ciphertext == null || context.blobId != blobId) {
            drop(row)
            return null
        }
        blobs.touch(row.blobId, clock.now().toEpochMilliseconds())
        return CachedBlob(context, row.sha256, ciphertext)
    }

    /** Whether [blobId] is in the index. */
    suspend fun contains(blobId: BlobId): Boolean = blobs.get(blobId.value) != null

    @Suppress("ReturnCount") // One early return per failure.
    private suspend fun readOrNull(row: BlobEntity): ByteArray? {
        val sealed = withContext(dispatcher) {
            try {
                fileOf(row.fileName).readBytes()
            } catch (expected: IOException) {
                null
            }
        } ?: return null
        val ciphertext = try {
            secretStore.getOrCreate(BLOB_KEY_SECRET, createIfMissing = false).use { key ->
                aead.decrypt(key, sealed, associatedData(row.blobId, row.sha256))
            }
        } catch (expected: GeneralSecurityException) {
            return null
        }
        return ciphertext.takeIf { JvmSha256Hasher.sha256(it).toHex() == row.sha256 }
    }

    /** Evicts the least recently used blobs (never [keep]) while the cache is over [maxBytes]. */
    private suspend fun evict(keep: String) {
        var total = blobs.totalSize()
        val candidates = blobs.leastRecentlyUsed().filter { it.blobId != keep }.iterator()
        while (total > maxBytes && candidates.hasNext()) {
            val row = candidates.next()
            drop(row)
            total -= row.size
        }
    }

    private suspend fun drop(row: BlobEntity) {
        blobs.delete(row.blobId)
        withContext(dispatcher) { fileOf(row.fileName).delete() }
    }

    private fun fileOf(fileName: String): File {
        val file = File(directory, fileName)
        check(file.canonicalFile.parentFile == directory.canonicalFile) { "blob outside the blob directory" }
        return file
    }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        directory.mkdirs()
        val temp = File(directory, target.name + TEMP_SUFFIX)
        temp.writeBytes(bytes)
        if (!temp.renameTo(target)) {
            temp.delete()
            throw IOException("could not store blob")
        }
    }

    companion object {
        /** [SecretStore] name of the per-install blob cache key. */
        const val BLOB_KEY_SECRET: String = "blobs.key"

        /** Subdirectory of `noBackupFilesDir` holding the cached blobs. */
        const val DIRECTORY_NAME: String = "blobs"

        /** Cache budget (M4 §2): 50 MB of E2EE ciphertext. */
        const val MAX_BYTES: Long = 50L * 1024 * 1024

        private const val EXTENSION = ".blob"
        private const val TEMP_SUFFIX = ".tmp"
        private const val AAD_LABEL = "sprout/blob-cache/v1"

        /** `UTF-8("sprout/blob-cache/v1") || 0x00 || blobId || 0x00 || sha256`. */
        internal fun associatedData(blobId: String, sha256: String): ByteArray =
            (AAD_LABEL + "\u0000" + blobId + "\u0000" + sha256).encodeToByteArray()
    }
}

/**
 * Queues encrypted blobs for upload (M4 §2): [store] encrypts the bytes with the kid's thread key (the epoch
 * [ThreadKeyStore.sendingKey] allows), splits them into chunks ([dev.kpetriv.sprout.core.crypto.chat.BlobCipher]),
 * signs every chunk header with the device key, and in one transaction under the device clock lock queues one
 * `BLOB_CHUNK` outbox row per chunk (with a `BLOB_QUEUED` audit row). The ciphertext also goes to the [cache], so this
 * device shows its own photos and voice notes without a download. Call it **before** sending the message that names
 * the blob: the outbox is pushed in order, so the blob reaches the server first.
 */
@Suppress("LongParameterList") // The writer's collaborators.
class BlobWrites @Inject constructor(
    private val database: SproutDatabase,
    private val clock: Clock,
    private val signer: DeviceSigner,
    private val authors: SessionAuthorProvider,
    private val crypto: ChatCrypto,
    private val check: SignatureCheck,
    private val cache: BlobCache,
) {
    private val deviceClock = DeviceClock.of(database)

    /**
     * Encrypts and queues [bytes] for [target]; returns the new blob's id.
     *
     * @throws ThreadKeyUnavailableException when this device holds no key it may send with in the kid's thread.
     * @throws IllegalArgumentException when [bytes] is empty or too large for one blob (`ChatLimits.MAX_BLOB_BYTES`),
     *   or a KID session targets another kid.
     */
    suspend fun store(target: BlobTarget, bytes: ByteArray): BlobId {
        require(bytes.isNotEmpty()) { "a blob must not be empty" }
        val session = database.sessionDao().get()?.toDomain() ?: throw NoSessionException()
        require(session.role != Role.KID || session.kidId == target.kidId) { "this device is linked to another kid" }
        val author = authors.currentAuthor()
        val threadId = ThreadId(target.kidId.value)
        val devices = RoomDeviceRegistry(database).devicesOf(session.householdId)
        val key = ThreadKeyStore(database).sendingKey(threadId, devices, check, database.isArchivedKid(threadId))
            ?: throw ThreadKeyUnavailableException(threadId)
        val blobId = BlobId(UUID.randomUUID().toString())
        val context = BlobContext(
            householdId = author.householdId,
            blobId = blobId,
            threadId = (target as? BlobTarget.Thread)?.threadId,
            kidId = target.kidId,
            submissionId = (target as? BlobTarget.ChorePhoto)?.submissionId,
            authorDeviceId = author.deviceId,
            epoch = key.epoch,
        )
        val encrypted = key.use { crypto.blobs.encrypt(it, context, bytes) }
        cache.put(context, encrypted.sha256, encrypted.chunks.reduce { joined, chunk -> joined + chunk })
        queue(author, context, encrypted)
        return blobId
    }

    /** Signs every chunk's header and queues the chunks, in one transaction under the device clock lock. */
    private suspend fun queue(author: EventAuthor, context: BlobContext, encrypted: EncryptedBlob) {
        deviceClock.withLock {
            database.withTransaction {
                val lamport = deviceClock.takeNext(author.deviceId)
                val now = clock.now().toEpochMilliseconds()
                encrypted.chunks.forEachIndexed { index, chunk ->
                    val unsigned = BlobChunkHeader(
                        householdId = author.householdId,
                        blobId = context.blobId,
                        threadId = context.threadId,
                        kidId = context.kidId,
                        submissionId = context.submissionId,
                        authorUid = author.uid,
                        authorDeviceId = author.deviceId,
                        lamport = lamport,
                        chunk = index,
                        chunks = encrypted.chunks.size,
                        sha256 = encrypted.sha256,
                        epoch = context.epoch,
                    )
                    val header = unsigned.withSignature(signer.sign(unsigned.signedPayload(chunk)))
                    database.outboxDao().insert(
                        OutboxEntity(
                            kind = OutboxKind.BLOB_CHUNK,
                            refId = header.docId,
                            payload = LedgerJson.json.encodeToString(
                                JsonObject.serializer(),
                                BlobJson.toJsonObject(header),
                            ),
                            createdAtEpochMs = now,
                            lamport = lamport,
                            data = chunk,
                        ),
                    )
                }
                database.auditLogDao().insert(
                    auditEntry(author, now, AuditAction.BLOB_QUEUED, context.blobId.value) {
                        put("chunks", encrypted.chunks.size)
                        put("epoch", context.epoch)
                        context.threadId?.let { put("threadId", it.value) }
                        context.submissionId?.let { put("submissionId", it.value) }
                    },
                )
            }
        }
    }
}

/** A blob could not be opened. [reason] says why; the message never includes blob content. */
class BlobUnavailableException(val reason: Reason, cause: Throwable? = null) :
    GeneralSecurityException("Blob unavailable: $reason", cause) {
    enum class Reason {
        /** Nothing is stored under the id (not uploaded yet). */
        NOT_FOUND,

        /** The chunks do not verify: unknown or revoked author, bad signature, inconsistent headers, wrong hash. */
        INVALID,

        /** No key this device holds opens it (yet). */
        LOCKED,
    }
}

/**
 * Opens blobs (M4 §2) for the chat and chore photo screens: [open] from the cache, [ingest] for chunks just fetched
 * with `SyncTransport.fetchBlob` (verified against the registry mirror, cached, then decrypted). Plaintext is only
 * returned, never stored or logged.
 */
class ChatBlobs @Inject constructor(
    private val database: SproutDatabase,
    private val cache: BlobCache,
    private val crypto: ChatCrypto,
    private val check: SignatureCheck,
) {
    /**
     * The plaintext of cached blob [blobId], or null when it is not cached (fetch it and call [ingest]).
     *
     * @throws BlobUnavailableException ([BlobUnavailableException.Reason.LOCKED]) when no held key opens it.
     */
    suspend fun open(blobId: BlobId): ByteArray? {
        val cached = cache.get(blobId) ?: return null
        return decrypt(cached.context, listOf(cached.ciphertext), cached.sha256)
    }

    /**
     * Verifies the fetched [chunks] of [blobId], caches the ciphertext and returns the plaintext.
     *
     * Every chunk header must decode, name [blobId] and this household, agree with the others (author, context,
     * chunk count, hash) and sit at its index; its author device must be registered and accept the header's lamport,
     * own the header's uid, and (a KID device) belong to the blob's kid; and its device signature must verify over the
     * chunk bytes.
     *
     * @throws BlobUnavailableException NOT_FOUND for no chunks, INVALID when a check fails, LOCKED when no held key
     *   opens it.
     */
    suspend fun ingest(blobId: BlobId, chunks: List<BlobChunkJson>): ByteArray {
        if (chunks.isEmpty()) throw BlobUnavailableException(BlobUnavailableException.Reason.NOT_FOUND)
        val headers = verifiedHeaders(blobId, chunks)
            ?: throw BlobUnavailableException(BlobUnavailableException.Reason.INVALID)
        val first = headers.first()
        val data = chunks.sortedBy { it.chunk }.map { it.data }
        val plaintext = decrypt(first.context, data, first.sha256)
        cache.put(first.context, first.sha256, data.reduce { joined, chunk -> joined + chunk })
        return plaintext
    }

    /** The decoded headers of [chunks] in chunk order, or null when any check of [ingest] fails. */
    private suspend fun verifiedHeaders(blobId: BlobId, chunks: List<BlobChunkJson>): List<BlobChunkHeader>? {
        val household = database.sessionDao().get()?.householdId
        val sorted = chunks.sortedBy { it.chunk }
        val headers = sorted.map { BlobJson.decodeOrNull(it.header) }.takeIf { all -> all.none { it == null } }
            ?.filterNotNull()
        val consistent = household != null && headers != null && consistent(headers, blobId, household)
        val author = headers?.takeIf { consistent }?.first()?.authorDeviceId
        val device = author?.let { RoomDeviceRegistry(database).device(it) }
        val trusted = device != null && headers.all { header ->
            device.acceptsLamport(header.lamport) && device.uid == header.authorUid &&
                (device.role == Role.PARENT || device.kidId == header.kidId)
        }
        val signed = trusted && headers.zip(sorted).all { (header, chunk) ->
            header.verifyWith(check, requireNotNull(device).publicKey, chunk.data)
        }
        return headers.takeIf { signed }
    }

    /** Every header names [blobId] and [household], sits at its index and agrees with the first. */
    private fun consistent(headers: List<BlobChunkHeader>, blobId: BlobId, household: HouseholdId): Boolean {
        val first = headers.first()
        return headers.withIndex().all { (index, header) ->
            header.blobId == blobId && header.householdId == household && header.chunk == index &&
                header.chunks == headers.size && header.sha256 == first.sha256 && header.context == first.context &&
                header.authorUid == first.authorUid
        }
    }

    private suspend fun decrypt(context: BlobContext, chunks: List<ByteArray>, sha256: String): ByteArray {
        val keys = ThreadKeyStore(database).keysFor(ThreadId(context.kidId.value), context.epoch)
        val plaintext = try {
            keys.firstNotNullOfOrNull { key ->
                try {
                    crypto.blobs.decrypt(key, context, chunks, sha256)
                } catch (expected: BlobDecryptionException) {
                    null
                }
            }
        } finally {
            keys.forEach { it.close() }
        }
        return plaintext ?: throw BlobUnavailableException(BlobUnavailableException.Reason.LOCKED)
    }
}
