package dev.kpetriv.sprout.core.domainledger

import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.model.UserId

/**
 * The household's device and parent-key registry as the receiver of remote data sees it (ADR-0004 §2, M3).
 *
 * Entries are mirrored from the backend, where only Cloud Functions write devices and only parent devices add
 * parent keys. A receiver never trusts a role, uid or key that is only claimed inside an event or document: it
 * looks the author's device up here and verifies signatures against the registered public key.
 */
interface DeviceRegistry {
    /** The registered device with [deviceId], or null when this household has never registered it. */
    suspend fun device(deviceId: DeviceId): RegisteredDevice?

    /** Every parent authorisation key ever registered for the household, including revoked ones. */
    suspend fun parentKeys(): List<RegisteredParentKey>
}

/** One registered device. [publicKey] is the X.509 SPKI DER of its P-256 signing key. */
class RegisteredDevice(
    val deviceId: DeviceId,
    val owner: DeviceOwner,
    val publicKey: ByteArray,
    val status: DeviceStatus,
    val attestation: AttestationLevel,
) {
    val uid: UserId get() = owner.uid
    val role: Role get() = owner.role
    val kidId: KidId? get() = owner.kidId

    /** Whether an event or document authored by this device at [lamport] may be applied. */
    fun acceptsLamport(lamport: Long): Boolean = when (val s = status) {
        DeviceStatus.Pending -> false
        DeviceStatus.Active -> true
        is DeviceStatus.Revoked -> lamport < s.atLamport
    }
}

/** The Firebase user a device belongs to and the role its claims carry; [kidId] is set for KID devices only. */
data class DeviceOwner(val uid: UserId, val role: Role, val kidId: KidId? = null)

sealed interface DeviceStatus {
    /** Registered but not yet approved by an existing parent device (second parent device, ADR-0004). */
    data object Pending : DeviceStatus

    data object Active : DeviceStatus

    /**
     * Revoked: events and documents with a lamport below [atLamport] were authored before the revocation and stay
     * valid; anything at or above it is refused.
     */
    data class Revoked(val atLamport: Long) : DeviceStatus
}

/** What the backend verified about the key's protection when the device registered (never self-reported). */
enum class AttestationLevel { NONE, SOFTWARE, TEE, STRONGBOX }

/**
 * One parent authorisation key (ADR-0004 §1). A parent signature on an event with lamport `l` is valid against this
 * key when `validFromLamport <= l` and (`revokedAtLamport == null || l < revokedAtLamport`).
 */
class RegisteredParentKey(
    /** Lower-case hex SHA-256 of [publicKey]. */
    val fingerprint: String,
    /** X.509 SPKI DER. */
    val publicKey: ByteArray,
    val uid: UserId,
    val deviceId: DeviceId,
    val validFromLamport: Long,
    val revokedAtLamport: Long?,
) {
    fun coversLamport(lamport: Long): Boolean =
        validFromLamport <= lamport && (revokedAtLamport == null || lamport < revokedAtLamport)
}

/**
 * Signature verification as a port, so the pure ledger module never depends on a crypto provider. [publicKey] is
 * SPKI DER; implementations must return false (never throw) on malformed input.
 */
fun interface SignatureCheck {
    fun verify(publicKey: ByteArray, payload: ByteArray, signature: ByteArray): Boolean
}
