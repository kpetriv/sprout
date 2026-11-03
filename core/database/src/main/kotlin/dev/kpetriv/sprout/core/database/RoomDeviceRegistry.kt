package dev.kpetriv.sprout.core.database

import dev.kpetriv.sprout.core.crypto.PublicKeyBytes
import dev.kpetriv.sprout.core.crypto.Verifier
import dev.kpetriv.sprout.core.database.entity.DeviceEntity
import dev.kpetriv.sprout.core.database.entity.ParentKeyEntity
import dev.kpetriv.sprout.core.database.entity.RegistrationStatus
import dev.kpetriv.sprout.core.domainledger.BoundEncryptionKey
import dev.kpetriv.sprout.core.domainledger.DeviceOwner
import dev.kpetriv.sprout.core.domainledger.DeviceRegistry
import dev.kpetriv.sprout.core.domainledger.DeviceStatus
import dev.kpetriv.sprout.core.domainledger.RegisteredDevice
import dev.kpetriv.sprout.core.domainledger.RegisteredParentKey
import dev.kpetriv.sprout.core.domainledger.SignatureCheck
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.Role
import javax.inject.Inject

/**
 * [DeviceRegistry] over the local registry mirror (`devices` and `parent_keys`), which only the sync applier writes
 * from pulled registry data (and `registerParentKey` for this device's own parent key). Safe to call inside a Room
 * transaction.
 */
class RoomDeviceRegistry @Inject constructor(private val database: SproutDatabase) : DeviceRegistry {
    override suspend fun device(deviceId: DeviceId): RegisteredDevice? =
        database.deviceDao().get(deviceId)?.toRegistered()

    override suspend fun parentKeys(): List<RegisteredParentKey> =
        database.parentKeyDao().all().map(ParentKeyEntity::toRegistered)

    /**
     * How projections resolve the role of a conflicting copy's author ([RoleLookup]): the registered roles while the
     * registry has entries; the envelopes' own roles only while `devices` is empty, which is the pre-linking local
     * household (every event was written on this device).
     */
    suspend fun roleLookup(): RoleLookup = RoleLookup(database.deviceDao().all().associate { it.deviceId to it.role })

    /**
     * Every device of the registry mirror (any status), with its encryption key when the registry listed one bound to
     * [householdId] (M4 §1.1): the input of `ThreadMembers.resolve`. The mirror only ever holds the session's
     * household, so this is empty for any other household (and without a session).
     */
    suspend fun devicesOf(householdId: HouseholdId): List<RegisteredDevice> {
        if (database.sessionDao().get()?.householdId != householdId) return emptyList()
        return database.deviceDao().all().map { it.toRegistered() }
    }
}

/**
 * Author-device roles for [dev.kpetriv.sprout.core.domainledger.Ledger.project]. With registered devices, [roleOf]
 * answers from the registry and [trustEnvelopeRoles] is false: a copy from an unregistered device has no role, so it
 * can never win a conflict. With no registered device at all (the pre-linking local household, where every event was
 * authored on this device) the projection falls back to the envelopes' self-declared roles.
 */
class RoleLookup(private val registered: Map<DeviceId, Role>) {
    val trustEnvelopeRoles: Boolean get() = registered.isEmpty()

    fun roleOf(deviceId: DeviceId): Role? = registered[deviceId]

    companion object {
        /** No registry: the local household's projections trust the envelopes. */
        val LOCAL: RoleLookup = RoleLookup(emptyMap())
    }
}

/** [SignatureCheck] over core/crypto's [Verifier] (JCE ECDSA P-256), for the ledger and document verifiers. */
class VerifierSignatureCheck @Inject constructor(private val verifier: Verifier) : SignatureCheck {
    override fun verify(publicKey: ByteArray, payload: ByteArray, signature: ByteArray): Boolean =
        verifier.verify(PublicKeyBytes(publicKey), payload, signature)
}

internal fun DeviceEntity.toRegistered(): RegisteredDevice = RegisteredDevice(
    deviceId = deviceId,
    owner = DeviceOwner(uid, role, kidId),
    publicKey = publicKey.copyOf(),
    status = when (status) {
        RegistrationStatus.PENDING -> DeviceStatus.Pending

        RegistrationStatus.ACTIVE -> DeviceStatus.Active

        // A revoked entry without a lamport accepts nothing.
        RegistrationStatus.REVOKED -> DeviceStatus.Revoked(revokedAtLamport ?: 0)
    },
    attestation = attestation,
    encryptionKey = encryptionKeyHouseholdId?.let { household ->
        val key = encryptionKey
        val signature = encryptionKeySignature
        if (key != null && signature != null) BoundEncryptionKey(household, key, signature) else null
    },
)

/**
 * The mirror row of this registry entry. Its encryption key is kept only when it is bound to [household] (the
 * session's): a key bound to another household could otherwise still verify, since the binding names its own
 * household. Whether the binding verifies is checked on every read, not here.
 */
internal fun RegisteredDevice.toEntity(registeredAtEpochMs: Long, household: HouseholdId? = null): DeviceEntity {
    val bound = encryptionKey?.takeIf { household != null && it.householdId == household }
    return DeviceEntity(
        deviceId = deviceId,
        uid = uid,
        role = role,
        kidId = kidId,
        publicKey = publicKey.copyOf(),
        status = when (status) {
            DeviceStatus.Pending -> RegistrationStatus.PENDING
            DeviceStatus.Active -> RegistrationStatus.ACTIVE
            is DeviceStatus.Revoked -> RegistrationStatus.REVOKED
        },
        revokedAtLamport = (status as? DeviceStatus.Revoked)?.atLamport,
        attestation = attestation,
        registeredAtEpochMs = registeredAtEpochMs,
        lamport = 0,
        encryptionKey = bound?.publicKey,
        encryptionKeySignature = bound?.signature,
        encryptionKeyHouseholdId = bound?.householdId,
    )
}

internal fun ParentKeyEntity.toRegistered(): RegisteredParentKey =
    RegisteredParentKey(fingerprint, publicKey.copyOf(), uid, deviceId, validFromLamport, revokedAtLamport)

internal fun RegisteredParentKey.toEntity(registeredAtEpochMs: Long): ParentKeyEntity = ParentKeyEntity(
    fingerprint,
    publicKey.copyOf(),
    uid,
    deviceId,
    validFromLamport,
    revokedAtLamport,
    registeredAtEpochMs,
)
