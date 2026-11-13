package dev.kpetriv.sprout.feature.lock

import dev.kpetriv.sprout.core.crypto.Aead
import dev.kpetriv.sprout.core.crypto.BlobStorage
import dev.kpetriv.sprout.core.crypto.PinHash
import dev.kpetriv.sprout.core.crypto.SecretStore
import java.security.GeneralSecurityException

/**
 * Where the parent PIN hash lives. Holds only the encoded [dev.kpetriv.sprout.core.crypto.PinHasher] output, never
 * the PIN. Production binds [EncryptedParentPinStore].
 */
interface ParentPinStore {
    /**
     * True when a PIN hash is stored, readable or not. A stored hash that cannot be decrypted still counts, so
     * corrupt storage never reads as "first run" and never lets anyone set a new PIN without the old one.
     */
    suspend fun hasPin(): Boolean

    /**
     * The encoded hash, or null when none is stored. Throws [ParentPinUnavailableException] when one is stored but
     * cannot be read (tampered, truncated, or its key is gone).
     */
    suspend fun storedHash(): String?

    /** Replaces the stored hash atomically. */
    suspend fun save(hash: String)

    /** Deletes the stored hash (a phone about to register as a new PENDING parent device, ADR-0013 §1). */
    suspend fun clear()
}

/** A PIN hash is stored but cannot be read. The PIN path stays closed; it never falls back to "no PIN". */
class ParentPinUnavailableException(message: String, cause: Throwable? = null) :
    GeneralSecurityException(message, cause)

/**
 * [ParentPinStore] that encrypts the encoded hash with [Aead] under the 32-byte secret [SECRET_NAME] from
 * [SecretStore] (itself wrapped by the Keystore wrap key) and keeps it in [storage] as [HASH_BLOB]; mirrors
 * `EncryptedLockoutStateStore`. In production [storage] is `FilesDirBlobStorage` (`noBackupFilesDir`, atomic
 * writes). The hash is already peppered with a Keystore key (ADR-0004 section 3); the encryption adds integrity, so
 * an edited file reads as [ParentPinUnavailableException] rather than as a different PIN.
 */
class EncryptedParentPinStore(
    private val secretStore: SecretStore,
    private val storage: BlobStorage,
    private val aead: Aead,
) : ParentPinStore {
    override suspend fun hasPin(): Boolean = storage.read(HASH_BLOB) != null

    override suspend fun storedHash(): String? {
        val blob = storage.read(HASH_BLOB) ?: return null
        val plaintext = try {
            secretStore.getOrCreate(SECRET_NAME, createIfMissing = false).use { key ->
                aead.decrypt(key, blob, ASSOCIATED_DATA)
            }
        } catch (e: GeneralSecurityException) {
            throw ParentPinUnavailableException("Stored PIN hash cannot be read", e)
        }
        val encoded = try {
            plaintext.decodeToString(throwOnInvalidSequence = true)
        } catch (e: CharacterCodingException) {
            throw ParentPinUnavailableException("Stored PIN hash is malformed", e)
        } finally {
            plaintext.fill(0)
        }
        if (PinHash.parse(encoded) == null) throw ParentPinUnavailableException("Stored PIN hash is malformed")
        return encoded
    }

    override suspend fun save(hash: String) {
        require(PinHash.parse(hash) != null) { "Not an encoded PIN hash" }
        val plaintext = hash.encodeToByteArray()
        try {
            val blob = secretStore.getOrCreate(SECRET_NAME).use { key ->
                aead.encrypt(key, plaintext, ASSOCIATED_DATA)
            }
            storage.write(HASH_BLOB, blob)
        } finally {
            plaintext.fill(0)
        }
    }

    override suspend fun clear() {
        storage.delete(HASH_BLOB)
    }

    companion object {
        /** [SecretStore] name of the hash encryption key. */
        const val SECRET_NAME = "pin.hash"

        /** [BlobStorage] name of the encrypted hash; distinct from [SECRET_NAME], whose wrapped blob shares it. */
        const val HASH_BLOB = "pin.hash.value"
        private val ASSOCIATED_DATA = "sprout/parent-pin-hash/v1".encodeToByteArray()
    }
}
