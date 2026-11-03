package dev.kpetriv.sprout.core.crypto

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.GeneralSecurityException
import kotlin.time.Duration.Companion.milliseconds

/** Persistence for [PinLockoutState]. Used only by [PinLockoutPolicy], which serialises access. */
interface LockoutStateStore {
    /**
     * The stored state, or null when none was ever written. Throws [LockoutStateUnavailableException] when a
     * stored state exists but cannot be read (tampered, truncated, or its key is gone).
     */
    suspend fun read(): PinLockoutState?

    /** Replaces the stored state atomically. */
    suspend fun write(state: PinLockoutState)
}

/** A stored lockout state exists but cannot be read; [PinLockoutPolicy] then applies the cap. */
class LockoutStateUnavailableException(message: String, cause: Throwable? = null) :
    GeneralSecurityException(message, cause)

/**
 * [LockoutStateStore] that encrypts the state with [Aead] under the 32-byte secret [SECRET_NAME] from
 * [SecretStore] (itself wrapped by the Keystore wrap key) and keeps it in [storage] as [STATE_BLOB]. In
 * production [storage] is [FilesDirBlobStorage], so the file sits in `noBackupFilesDir` and is written
 * atomically. Encryption stops casual edits of the failure count or lockout start; anything that fails to
 * authenticate reads as [LockoutStateUnavailableException], never as "no lockout".
 *
 * A stored secret that can no longer be unwrapped (the Keystore wrap key is gone) makes [write] throw
 * [SecretUnavailableException]; the lock feature must treat any exception from [PinLockoutPolicy] as locked.
 */
class EncryptedLockoutStateStore(
    private val secretStore: SecretStore,
    private val storage: BlobStorage,
    private val aead: Aead,
) : LockoutStateStore {
    override suspend fun read(): PinLockoutState? {
        val blob = storage.read(STATE_BLOB) ?: return null
        return try {
            val plaintext = secretStore.getOrCreate(SECRET_NAME, createIfMissing = false).use { key ->
                aead.decrypt(key, blob, ASSOCIATED_DATA)
            }
            PinLockoutStateCodec.decode(plaintext)
        } catch (e: GeneralSecurityException) {
            // A missing or unwrappable secret, or a blob that does not authenticate.
            throw LockoutStateUnavailableException("Stored lockout state cannot be read", e)
        } catch (e: IllegalArgumentException) {
            throw LockoutStateUnavailableException("Stored lockout state is malformed", e)
        }
    }

    override suspend fun write(state: PinLockoutState) {
        val plaintext = PinLockoutStateCodec.encode(state)
        val blob = secretStore.getOrCreate(SECRET_NAME).use { key -> aead.encrypt(key, plaintext, ASSOCIATED_DATA) }
        storage.write(STATE_BLOB, blob)
    }

    companion object {
        /** [SecretStore] name of the state encryption key. */
        const val SECRET_NAME = "pin.lockout"

        /** [BlobStorage] name of the encrypted state; distinct from [SECRET_NAME], whose wrapped blob shares it. */
        const val STATE_BLOB = "pin.lockout.state"
        private val ASSOCIATED_DATA = "sprout/pin-lockout-state/v1".encodeToByteArray()
    }
}

/**
 * Binary form of [PinLockoutState]: `version(1) || failures(int32) || startedAtMillis(int64, -1 = none) ||
 * bootIdLength(uint16) || bootId(UTF-8)`, big-endian. Decoding is strict and throws [IllegalArgumentException]
 * on anything else, including trailing bytes.
 */
internal object PinLockoutStateCodec {
    private const val VERSION = 1
    private const val NO_LOCKOUT = -1L
    private const val MAX_BOOT_ID_BYTES = 1024

    fun encode(state: PinLockoutState): ByteArray {
        val bootId = (state.bootId ?: "").encodeToByteArray()
        require(bootId.size <= MAX_BOOT_ID_BYTES) { "Boot id too long" }
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeByte(VERSION)
            out.writeInt(state.consecutiveFailures)
            out.writeLong(state.lockoutStartedAt?.inWholeMilliseconds ?: NO_LOCKOUT)
            out.writeShort(bootId.size)
            out.write(bootId)
        }
        return bytes.toByteArray()
    }

    fun decode(bytes: ByteArray): PinLockoutState = try {
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readUnsignedByte() == VERSION) { "Unknown lockout state version" }
            val failures = input.readInt()
            val startedAtMillis = input.readLong()
            val bootIdSize = input.readUnsignedShort()
            require(bootIdSize <= MAX_BOOT_ID_BYTES) { "Boot id too long" }
            val bootId = ByteArray(bootIdSize).also(input::readFully).decodeToString()
            require(input.read() == -1) { "Trailing bytes in lockout state" }
            require(startedAtMillis >= NO_LOCKOUT) { "Negative lockout start" }
            val running = startedAtMillis != NO_LOCKOUT
            require(running == bootId.isNotEmpty()) { "Lockout start and boot id must go together" }
            PinLockoutState(
                consecutiveFailures = failures,
                lockoutStartedAt = if (running) startedAtMillis.milliseconds else null,
                bootId = if (running) bootId else null,
            )
        }
    } catch (e: IOException) {
        // EOFException when truncated.
        throw IllegalArgumentException("Truncated or unreadable lockout state", e)
    }
}
