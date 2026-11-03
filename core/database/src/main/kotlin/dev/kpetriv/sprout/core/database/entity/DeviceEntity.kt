package dev.kpetriv.sprout.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import dev.kpetriv.sprout.core.domainledger.AttestationLevel
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.model.UserId

/** A device's registration status, as the registry and the session store it (by name). */
enum class RegistrationStatus {
    /** Registered, waiting for an existing parent device to approve it. */
    PENDING,
    ACTIVE,
    REVOKED,
}

/**
 * The local mirror of one `households/{hid}/devices/{deviceId}` registry entry (M3 §1), written only by the sync
 * applier from pulled registry data. Never written from local input: it is what remote events and documents are
 * verified against.
 *
 * @property publicKey X.509 SPKI DER of the device's signing key.
 * @property revokedAtLamport set when [status] is REVOKED: the device's writes below it stay valid.
 * @property registeredAtEpochMs when this device first saw the entry (local wall clock; display only).
 * @property lamport the registry entry's lamport when the transport supplies one; 0 otherwise.
 */
@Entity(tableName = "devices")
data class DeviceEntity(
    @PrimaryKey val deviceId: DeviceId,
    val uid: UserId,
    val role: Role,
    val kidId: KidId?,
    val publicKey: ByteArray,
    val status: RegistrationStatus,
    val revokedAtLamport: Long?,
    val attestation: AttestationLevel,
    val registeredAtEpochMs: Long,
    val lamport: Long,
) {
    override fun equals(other: Any?): Boolean = other is DeviceEntity && deviceId == other.deviceId &&
        uid == other.uid && role == other.role && kidId == other.kidId && publicKey.contentEquals(other.publicKey) &&
        status == other.status && revokedAtLamport == other.revokedAtLamport && attestation == other.attestation &&
        registeredAtEpochMs == other.registeredAtEpochMs && lamport == other.lamport

    override fun hashCode(): Int = deviceId.hashCode()

    override fun toString(): String = "DeviceEntity(deviceId=$deviceId, role=$role, status=$status)"
}

/**
 * The local mirror of one registered parent authorisation key (`parentKeys/{fingerprint}`, ADR-0004 §1): pulled from
 * the registry or applied from a verified `parent_key` document. A parent signature on an event at lamport `l` is
 * valid against it when `validFromLamport <= l` and (`revokedAtLamport == null || l < revokedAtLamport`).
 *
 * @property fingerprint lower-case hex SHA-256 of [publicKey].
 * @property deviceId the parent device that registered it.
 */
@Entity(tableName = "parent_keys")
data class ParentKeyEntity(
    @PrimaryKey val fingerprint: String,
    val publicKey: ByteArray,
    val uid: UserId,
    val deviceId: DeviceId,
    val validFromLamport: Long,
    val revokedAtLamport: Long?,
    val registeredAtEpochMs: Long,
) {
    override fun equals(other: Any?): Boolean = other is ParentKeyEntity && fingerprint == other.fingerprint &&
        publicKey.contentEquals(other.publicKey) && uid == other.uid && deviceId == other.deviceId &&
        validFromLamport == other.validFromLamport && revokedAtLamport == other.revokedAtLamport &&
        registeredAtEpochMs == other.registeredAtEpochMs

    override fun hashCode(): Int = fingerprint.hashCode()
}
