package dev.kpetriv.sprout.core.crypto

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.GeneralSecurityException
import java.security.SecureRandom

/** Named, locally generated secrets (for example the SQLCipher passphrase) that survive process restarts. */
interface SecretStore {
    /**
     * Returns the 32-byte secret called [name], generating and persisting it on first use. Each call returns a
     * fresh [SymmetricKey]; close it when done. Names match `[a-z0-9._-]{1,64}`.
     *
     * [createIfMissing] is the caller's guard against orphaning data: pass false when something already depends
     * on the secret (the database layer passes `createIfMissing = !databaseFileExists`), so a missing blob throws
     * instead of a fresh secret being minted that can never open the existing data.
     *
     * Throws [SecretUnavailableException] if a stored secret exists but cannot be unwrapped, or if none is stored
     * and [createIfMissing] is false. It never silently replaces a stored secret, because a new passphrase would
     * orphan the encrypted database.
     */
    suspend fun getOrCreate(name: String, createIfMissing: Boolean = true): SymmetricKey

    /**
     * The record [name] written by [writeRecord], or null when none is stored. A record is a small wrapped byte string
     * (not a 32-byte secret); its wrapping is bound to [boundTo], the name it was written under, which differs from
     * [name] only for a record [rename]d since (a stash read under its own name).
     *
     * @throws SecretUnavailableException when a stored record cannot be unwrapped.
     */
    suspend fun readRecord(name: String, boundTo: String = name): ByteArray?

    /** Replaces the record [name] atomically with [record], wrapped and bound to [name]. */
    suspend fun writeRecord(name: String, record: ByteArray)

    /** True when a secret or record is stored under [name] (readable or not). */
    suspend fun contains(name: String): Boolean

    /** Deletes the secret or record [name] (every file of it); nothing stored is not a failure. */
    suspend fun delete(name: String)

    /**
     * Moves the stored blob [from] to [to] as it is, replacing what [to] held; true when there was one to move. The
     * wrapping stays bound to [from]: under [to] it does not open as a secret ([getOrCreate] fails), and a record is
     * read with `readRecord(to, boundTo = from)`. Moving it back makes it usable again.
     */
    suspend fun rename(from: String, to: String): Boolean
}

/** Wraps and unwraps small secrets with a key the app process never sees (Android Keystore on device). */
interface KeyWrapper {
    fun wrap(plaintext: ByteArray, associatedData: ByteArray): ByteArray

    /** Throws [GeneralSecurityException] when the blob or associated data do not authenticate. */
    fun unwrap(wrapped: ByteArray, associatedData: ByteArray): ByteArray
}

/** App-private persistence for wrapped blobs. Blobs are already encrypted; storage adds no protection. */
interface BlobStorage {
    suspend fun read(name: String): ByteArray?

    /** Replaces the blob atomically: readers see the old or the new content, never a partial write. */
    suspend fun write(name: String, blob: ByteArray)

    suspend fun delete(name: String)

    /**
     * Moves the blob [from] to [to], replacing what [to] held, and deletes [from]; true when [from] existed. Copied
     * through [read], [write] and [delete], so a storage whose writes are atomic leaves either both copies (a crash
     * in between: run it again) or the moved one, never a partial blob. Implementations may do better.
     */
    suspend fun rename(from: String, to: String): Boolean {
        val blob = read(from) ?: return false
        write(to, blob)
        delete(from)
        return true
    }
}

/**
 * A secret is unavailable: a stored one could not be unwrapped (corrupt blob, or the wrapping key is gone), or
 * none is stored and the caller did not allow creating one.
 */
class SecretUnavailableException(message: String, cause: Throwable? = null) : GeneralSecurityException(message, cause)

/**
 * [SecretStore] that keeps each secret wrapped by [wrapper] and persisted in [storage].
 *
 * Blob layout: `0x01 || wrapper output`. The wrapping is bound to the secret's name through associated data, so
 * swapping two blobs on disk makes both fail instead of handing one secret out under the other's name. Records
 * ([writeRecord]) are `0x02 || wrapper output`, bound to their name under a separate label. Every read, write, delete
 * and rename runs under one mutex, so a rename never races a [getOrCreate] that would mint a fresh secret.
 */
class WrappedSecretStore(
    private val wrapper: KeyWrapper,
    private val storage: BlobStorage,
    private val random: SecureRandom,
    private val dispatcher: CoroutineDispatcher,
) : SecretStore {
    private val mutex = Mutex()

    override suspend fun getOrCreate(name: String, createIfMissing: Boolean): SymmetricKey {
        require(isValidName(name)) { "Invalid secret name" }
        val associatedData = associatedDataFor(name)
        return mutex.withLock {
            val existing = storage.read(name)
            when {
                existing != null -> unwrap(existing, associatedData)
                createIfMissing -> create(name, associatedData)
                else -> throw SecretUnavailableException("Secret is missing and creating it is not allowed")
            }
        }
    }

    override suspend fun readRecord(name: String, boundTo: String): ByteArray? {
        require(isValidName(name) && isValidName(boundTo)) { "Invalid record name" }
        return mutex.withLock {
            val blob = storage.read(name) ?: return@withLock null
            if (blob.isEmpty() || blob[0] != RECORD_VERSION) throw SecretUnavailableException("Unknown record format")
            try {
                withContext(dispatcher) { wrapper.unwrap(blob.copyOfRange(1, blob.size), recordDataFor(boundTo)) }
            } catch (e: GeneralSecurityException) {
                throw SecretUnavailableException("Stored record could not be unwrapped", e)
            }
        }
    }

    override suspend fun writeRecord(name: String, record: ByteArray) {
        require(isValidName(name)) { "Invalid record name" }
        mutex.withLock {
            val wrapped = withContext(dispatcher) { wrapper.wrap(record, recordDataFor(name)) }
            storage.write(name, byteArrayOf(RECORD_VERSION) + wrapped)
        }
    }

    override suspend fun contains(name: String): Boolean {
        require(isValidName(name)) { "Invalid secret name" }
        return mutex.withLock { storage.read(name) != null }
    }

    override suspend fun delete(name: String) {
        require(isValidName(name)) { "Invalid secret name" }
        mutex.withLock { storage.delete(name) }
    }

    override suspend fun rename(from: String, to: String): Boolean {
        require(isValidName(from) && isValidName(to) && from != to) { "Invalid secret names" }
        return mutex.withLock { storage.rename(from, to) }
    }

    private suspend fun create(name: String, associatedData: ByteArray): SymmetricKey {
        val secret = ByteArray(SECRET_SIZE).also(random::nextBytes)
        val wrapped = withContext(dispatcher) { wrapper.wrap(secret, associatedData) }
        storage.write(name, byteArrayOf(BLOB_VERSION) + wrapped)
        return SymmetricKey.adopt(secret)
    }

    private suspend fun unwrap(blob: ByteArray, associatedData: ByteArray): SymmetricKey {
        if (blob.isEmpty() || blob[0] != BLOB_VERSION) throw SecretUnavailableException("Unknown secret blob format")
        val secret = try {
            withContext(dispatcher) { wrapper.unwrap(blob.copyOfRange(1, blob.size), associatedData) }
        } catch (e: GeneralSecurityException) {
            throw SecretUnavailableException("Stored secret could not be unwrapped", e)
        }
        return SymmetricKey.adopt(secret)
    }

    companion object {
        const val SECRET_SIZE = 32
        private const val BLOB_VERSION: Byte = 1

        /** A record's first byte: distinct from [BLOB_VERSION], so a record never opens as a secret or vice versa. */
        private const val RECORD_VERSION: Byte = 2
        private val NAME_PATTERN = Regex("[a-z0-9._-]{1,64}")
        private const val AAD_LABEL = "sprout/wrapped-secret/v1:"
        private const val RECORD_AAD_LABEL = "sprout/wrapped-record/v1:"

        /** Names double as file names, so they are restricted to a safe character set (no path separators). */
        fun isValidName(name: String): Boolean = NAME_PATTERN.matches(name) && name != "." && name != ".."

        internal fun associatedDataFor(name: String): ByteArray = (AAD_LABEL + name).encodeToByteArray()

        internal fun recordDataFor(name: String): ByteArray = (RECORD_AAD_LABEL + name).encodeToByteArray()
    }
}
