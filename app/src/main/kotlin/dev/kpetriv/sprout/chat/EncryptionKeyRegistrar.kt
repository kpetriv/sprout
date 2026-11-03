package dev.kpetriv.sprout.chat

import dev.kpetriv.sprout.core.crypto.EncryptionKeyStore
import dev.kpetriv.sprout.core.crypto.SecretUnavailableException
import dev.kpetriv.sprout.core.crypto.X25519PublicKey
import dev.kpetriv.sprout.core.crypto.chat.EncryptionKeyBinding
import dev.kpetriv.sprout.core.database.RoomDeviceRegistry
import dev.kpetriv.sprout.core.database.Session
import dev.kpetriv.sprout.core.database.SessionStore
import dev.kpetriv.sprout.core.database.entity.RegistrationStatus
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.syncfirebase.FunctionsError
import dev.kpetriv.sprout.core.syncfirebase.FunctionsException
import dev.kpetriv.sprout.household.HouseholdAdminFunctions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** What [EncryptionKeyRegistrar.ensureRegistered] found or did. */
enum class KeyRegistration {
    /** No real household's ACTIVE session: nothing to register. */
    NOT_NEEDED,

    /** The registry mirror already holds this device's key. */
    REGISTERED,

    /** The key was registered now: the next pull brings it into the mirror. */
    SENT,

    /** The server already had this key (an earlier attempt whose answer was lost); the mirror catches up by itself. */
    ALREADY_SENT,

    /**
     * The registry holds another key for this device, or this device lost its key (D-M4-14): only a new device
     * registration can change it. Chat waits for a parent device (threads never wrap for a key that is not here).
     */
    CONFLICT,

    /** The call failed (offline, server trouble); retried after the next successful sync. */
    FAILED,
}

/**
 * Registers this device's X25519 encryption key with the household (M4 §1.1, D-M4-2, D-M4-13). Households are created
 * and kid devices linked before the device knows the household id its key binding must name, so every device
 * registers its key after joining: at start-up (after the session check) and after every successful sync, whenever
 * the registry mirror shows no key for this device. The key is created on first use ([EncryptionKeyStore]), bound
 * with the active device key ([EncryptionKeyBinding.sign]) and sent through `registerEncryptionKey` (device proof,
 * [HouseholdAdminFunctions]). A second parent phone already sends its key with `registerParentDevice`. One attempt at
 * a time; nothing is logged.
 */
@Singleton
class EncryptionKeyRegistrar internal constructor(
    private val session: suspend () -> Session?,
    private val mirroredKey: suspend (DeviceId) -> ByteArray?,
    private val localKey: suspend (createIfMissing: Boolean) -> X25519PublicKey?,
    private val bind: suspend (HouseholdId, DeviceId, X25519PublicKey) -> ByteArray,
    private val register: suspend (publicKey: ByteArray, binding: ByteArray) -> Result<Boolean>,
) {
    @Inject
    constructor(
        sessions: SessionStore,
        registry: RoomDeviceRegistry,
        keys: EncryptionKeyStore,
        binding: EncryptionKeyBinding,
        admin: HouseholdAdminFunctions,
    ) : this(
        session = sessions::get,
        mirroredKey = { registry.device(it)?.encryptionKey?.publicKey },
        localKey = { create ->
            try {
                keys.keyPair(createIfMissing = create).use { it.publicKey }
            } catch (expected: SecretUnavailableException) {
                null
            }
        },
        bind = binding::sign,
        register = { key, signature -> admin.registerEncryptionKey(key, signature).map { it.created } },
    )

    private val attempts = Mutex()

    /** The server refused this device's key in this process (D-M4-14): not asked again until the next start. */
    @Volatile
    private var refused = false

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // Failures are an outcome, unlogged.
    suspend fun ensureRegistered(): KeyRegistration = attempts.withLock {
        val current = session()
        if (current == null || current.isLocalTrial || current.deviceStatus != RegistrationStatus.ACTIVE) {
            return KeyRegistration.NOT_NEEDED
        }
        try {
            val mirrored = mirroredKey(current.deviceId)
            when {
                mirrored != null -> compareWith(mirrored)
                refused -> KeyRegistration.CONFLICT
                else -> send(current)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            KeyRegistration.FAILED
        }
    }

    /** The registry already lists a key for this device: it must be the one held here. */
    private suspend fun compareWith(mirrored: ByteArray): KeyRegistration {
        val local = localKey(false)
        return if (local != null && local.bytes.contentEquals(mirrored)) {
            KeyRegistration.REGISTERED
        } else {
            KeyRegistration.CONFLICT
        }
    }

    private suspend fun send(current: Session): KeyRegistration {
        val key = localKey(true) ?: return KeyRegistration.FAILED
        val result = register(key.bytes, bind(current.householdId, current.deviceId, key))
        val error = (result.exceptionOrNull() as? FunctionsException)?.error
        return when {
            result.isSuccess -> if (result.getOrThrow()) KeyRegistration.SENT else KeyRegistration.ALREADY_SENT

            error == FunctionsError.FailedPrecondition -> {
                refused = true
                KeyRegistration.CONFLICT
            }

            else -> KeyRegistration.FAILED
        }
    }
}
