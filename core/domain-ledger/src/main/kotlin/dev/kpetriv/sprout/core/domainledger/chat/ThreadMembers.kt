package dev.kpetriv.sprout.core.domainledger.chat

import dev.kpetriv.sprout.core.domainledger.DeviceStatus
import dev.kpetriv.sprout.core.domainledger.RegisteredDevice
import dev.kpetriv.sprout.core.domainledger.SignatureCheck
import dev.kpetriv.sprout.core.domainledger.toHex
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.model.ThreadId

/**
 * A device that may hold a thread's key: its [role] and its verified raw X25519 [encryptionKey] (32 bytes), which
 * is what the key is wrapped to.
 */
class ThreadMember(val deviceId: DeviceId, val role: Role, encryptionKey: ByteArray) {
    private val key: ByteArray = encryptionKey.copyOf()

    init {
        require(key.size == ENCRYPTION_KEY_SIZE) { "An X25519 public key is $ENCRYPTION_KEY_SIZE bytes" }
    }

    val encryptionKey: ByteArray get() = key.copyOf()

    override fun equals(other: Any?): Boolean =
        other is ThreadMember && other.deviceId == deviceId && other.role == role && other.key.contentEquals(key)

    override fun hashCode(): Int = 31 * (31 * deviceId.hashCode() + role.hashCode()) + key.contentHashCode()

    override fun toString(): String = "ThreadMember(deviceId=$deviceId, role=$role, encryptionKey=${key.toHex()})"

    private companion object {
        const val ENCRYPTION_KEY_SIZE = 32
    }
}

/**
 * Who belongs to a thread, as one device's registry mirror sees it.
 *
 * @property members the devices the thread key must be wrapped for, sorted by device id.
 * @property excluded known devices that must not hold the key: every REVOKED device, every KID device of another
 *   kid, and the thread's own KID devices when the kid is archived. A key whose wrap set contains one of them must be
 *   rotated ([ThreadKeyPolicy]). Devices that are only missing a verified encryption key, or PENDING, are neither
 *   members nor excluded: a registry mirror that lags must not trigger a rotation.
 */
class ThreadMembership(val threadId: ThreadId, val members: List<ThreadMember>, val excluded: Set<DeviceId>) {
    val memberIds: Set<DeviceId> get() = members.mapTo(LinkedHashSet()) { it.deviceId }

    fun member(deviceId: DeviceId): ThreadMember? = members.firstOrNull { it.deviceId == deviceId }

    override fun toString(): String =
        "ThreadMembership(threadId=$threadId, members=${members.map { it.deviceId }}, excluded=$excluded)"
}

/**
 * The thread membership rule (M4 §1.2): every ACTIVE PARENT device of the household, and every ACTIVE KID device whose
 * kid is the thread's kid (`threadId == kidId`), counting only devices whose encryption key binding verifies
 * ([RegisteredDevice.verifiedEncryptionKey]). Kid mode on a parent phone uses the parent device's keys (D-M3-12).
 */
object ThreadMembers {
    /**
     * @param devices every device in the registry mirror (any status).
     * @param kidArchived the thread's kid is archived: its KID devices leave the thread (a removal).
     */
    fun resolve(
        threadId: ThreadId,
        devices: Collection<RegisteredDevice>,
        check: SignatureCheck,
        kidArchived: Boolean = false,
    ): ThreadMembership {
        val members = devices
            .filter { isEligible(it, threadId, kidArchived) }
            .mapNotNull { device ->
                device.verifiedEncryptionKey(check)?.let { ThreadMember(device.deviceId, device.role, it) }
            }
            .sortedBy { it.deviceId.value }
        val excluded = devices.filter { isExcluded(it, threadId, kidArchived) }.mapTo(LinkedHashSet()) { it.deviceId }
        return ThreadMembership(threadId, members, excluded)
    }

    /** The role and status part of the rule (no key check). */
    fun isEligible(device: RegisteredDevice, threadId: ThreadId, kidArchived: Boolean = false): Boolean =
        device.status == DeviceStatus.Active && when (device.role) {
            Role.PARENT -> true
            Role.KID -> !kidArchived && device.kidId?.value == threadId.value
        }

    private fun isExcluded(device: RegisteredDevice, threadId: ThreadId, kidArchived: Boolean): Boolean =
        device.status is DeviceStatus.Revoked ||
            (device.role == Role.KID && (kidArchived || device.kidId?.value != threadId.value))
}
