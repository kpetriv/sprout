package dev.kpetriv.sprout.core.crypto

import dev.kpetriv.sprout.core.time.Clock
import kotlin.time.Instant

/**
 * Software [DeviceKeyStore] with an in-memory P-256 key. Test fixture only; production binds
 * [AndroidKeystoreDeviceKeyStore]. [regenerateForRegistration] swaps in a fresh software key and returns an empty
 * attestation chain (software keys cannot be attested).
 */
class InMemoryDeviceKeyStore(private val clock: Clock) : DeviceKeyStore {
    @Volatile
    private var key: SigningKeyHandle? = null
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
                key = it
                createdAt = clock.now()
                registrations++
            }
        }
        return DeviceRegistrationMaterial(fresh.publicKey, emptyList())
    }

    @Synchronized
    private fun current(): SigningKeyHandle = key ?: generateSoftwareSigningKey().also {
        key = it
        createdAt = clock.now()
    }
}
