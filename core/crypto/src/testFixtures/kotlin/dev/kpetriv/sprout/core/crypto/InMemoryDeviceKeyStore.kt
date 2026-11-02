package dev.kpetriv.sprout.core.crypto

import dev.kpetriv.sprout.core.time.Clock
import kotlin.time.Instant

/**
 * Software [DeviceKeyStore] with an in-memory P-256 key. Test fixture only; production binds
 * [AndroidKeystoreDeviceKeyStore].
 */
class InMemoryDeviceKeyStore(private val clock: Clock) : DeviceKeyStore {
    private val key: SigningKeyHandle by lazy { generateSoftwareSigningKey() }
    private val createdAt: Instant by lazy { clock.now() }

    override suspend fun deviceSigningKey(): SigningKeyHandle = key

    override fun publicKey(): PublicKeyBytes = key.publicKey

    override fun keyProperties(): DeviceKeyProperties =
        DeviceKeyProperties(isHardwareBacked = false, isStrongBox = false, createdAt = createdAt)
}
