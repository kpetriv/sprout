package dev.kpetriv.sprout.core.domainledger.chat

import dev.kpetriv.sprout.core.domainledger.AttestationLevel
import dev.kpetriv.sprout.core.domainledger.BoundEncryptionKey
import dev.kpetriv.sprout.core.domainledger.DeviceOwner
import dev.kpetriv.sprout.core.domainledger.DeviceStatus
import dev.kpetriv.sprout.core.domainledger.Fixtures
import dev.kpetriv.sprout.core.domainledger.RegisteredDevice
import dev.kpetriv.sprout.core.domainledger.SoftwareKey
import dev.kpetriv.sprout.core.domainledger.bindEncryptionKey
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.model.ThreadId
import dev.kpetriv.sprout.core.model.UserId
import kotlin.io.encoding.Base64
import kotlin.random.Random

internal val household = Fixtures.household
internal val thread: ThreadId = ThreadId.of(Fixtures.kid)
internal val otherKid = KidId("kid-2")

/** A test device: a software P-256 device key and a stand-in 32-byte X25519 public key bound to it. */
internal class ChatDevice(
    id: String,
    val role: Role,
    val kidId: KidId? = null,
    uid: String = if (role == Role.PARENT) "parent-uid" else "kid-uid-${kidId?.value}",
) {
    val deviceId = DeviceId(id)
    val uid = UserId(uid)
    val signingKey = SoftwareKey()
    val encryptionKey: ByteArray = Random(id.hashCode()).nextBytes(32)

    fun bound(): BoundEncryptionKey = signingKey.bindEncryptionKey(household, deviceId, encryptionKey)

    fun registered(
        status: DeviceStatus = DeviceStatus.Active,
        encryptionKey: BoundEncryptionKey? = bound(),
    ): RegisteredDevice = RegisteredDevice(
        deviceId = deviceId,
        owner = DeviceOwner(uid, role, kidId),
        publicKey = signingKey.publicKey,
        status = status,
        attestation = AttestationLevel.TEE,
        encryptionKey = encryptionKey,
    )

    fun member(): ThreadMember = ThreadMember(deviceId, role, encryptionKey)
}

/** A stand-in wrap: base64 of 61 bytes, the size of a real wrapped key. */
internal val sampleWrap: String = Base64.Default.encode(ByteArray(61) { it.toByte() })

internal fun wrapsFor(vararg devices: ChatDevice): Map<DeviceId, String> = devices.associate {
    it.deviceId to sampleWrap
}

internal fun keyDoc(epoch: Long, creator: ChatDevice, vararg recipients: ChatDevice): ThreadKeyDocument =
    ThreadKeyDocument(thread, epoch, creator.deviceId, wrapsFor(creator, *recipients))
