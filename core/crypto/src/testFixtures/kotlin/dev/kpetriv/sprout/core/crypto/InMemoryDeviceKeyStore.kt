package dev.kpetriv.sprout.core.crypto

import dev.kpetriv.sprout.core.time.Clock
import kotlin.time.Instant

/**
 * Software [DeviceKeyStore] with in-memory P-256 keys. Test fixture only; production binds
 * [AndroidKeystoreDeviceKeyStore]. [regenerateForRegistration] creates a fresh pending software key with an empty
 * attestation chain (software keys cannot be attested); [promotePendingKey] makes it the active key.
 */
class InMemoryDeviceKeyStore(private val clock: Clock) : DeviceKeyStore {
    @Volatile
    private var key: SigningKeyHandle? = null

    @Volatile
    private var pending: SigningKeyHandle? = null
    private var createdAt: Instant? = null

    /** How many times [regenerateForRegistration] ran. */
    @Volatile
    var registrations: Int = 0
        private set

    override suspend fun deviceSigningKey(): SigningKeyHandle = current()

    override fun publicKey(): PublicKeyBytes = current().publicKey

    override fun keyProperties(): DeviceKeyProperties {
        current()
        return DeviceKeyProperties(isHardwareBacked = false, isStrongBox = false, createdAt = createdAt)
    }

    override suspend fun regenerateForRegistration(challenge: ByteArray): DeviceRegistrationMaterial {
        require(challenge.isNotEmpty()) { "the attestation challenge must not be empty" }
        val fresh = synchronized(this) {
            generateSoftwareSigningKey().also {
                pending = it
                registrations++
            }
        }
        return DeviceRegistrationMaterial(fresh.publicKey, emptyList())
    }

    override suspend fun pendingSigningKey(): SigningKeyHandle? = pending

    override suspend fun promotePendingKey(): PublicKeyBytes = synchronized(this) {
        val promoted = checkNotNull(pending) { "no pending device key" }
        key = promoted
        pending = null
        createdAt = clock.now()
        promoted.publicKey
    }

    override suspend fun discardPendingKey() {
        pending = null
    }

    /** Registers and promotes in one go, as a device that finished linking. */
    suspend fun registerAndPromote(challenge: ByteArray = byteArrayOf(1)): PublicKeyBytes {
        regenerateForRegistration(challenge)
        return promotePendingKey()
    }

    @Synchronized
    private fun current(): SigningKeyHandle = key ?: generateSoftwareSigningKey().also {
        key = it
        createdAt = clock.now()
    }
}
