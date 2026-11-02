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
 * swapping two blobs on disk makes both fail instead of handing one secret out under the other's name.
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
        private val NAME_PATTERN = Regex("[a-z0-9._-]{1,64}")
        private const val AAD_LABEL = "sprout/wrapped-secret/v1:"

        /** Names double as file names, so they are restricted to a safe character set (no path separators). */
        fun isValidName(name: String): Boolean = NAME_PATTERN.matches(name) && name != "." && name != ".."

        internal fun associatedDataFor(name: String): ByteArray = (AAD_LABEL + name).encodeToByteArray()
    }
}
