package dev.kpetriv.sprout.feature.parenthome

import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.Role
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

/**
 * What the dashboard shows about sync and the household (M3 §6). The app adapts it to the sync engine's reports, the
 * device registry mirror and the quarantined-event reader (`RoomLedgerRepository.observeQuarantined`); tests use a
 * fake. Nothing here carries an event's payload.
 */
fun interface HouseholdStatusSource {
    fun observe(): Flow<HouseholdStatus>
}

/**
 * @property trial the pre-linking trial household: the dashboard offers "Set up sync" and shows no sync line.
 * @property sync the sync line; null for the trial.
 * @property rejected events this device refused (quarantined), newest first.
 * @property pendingDevices parent phones waiting for this phone's approval.
 * @property signingKeyNotRegistered this phone's parent signing key works here but the household's server never
 *   recorded it (D-M3-39), so the other devices refuse its signatures; the card's "Retry" opens Settings → Security,
 *   which renews and registers the key. Sync cannot retry it: the server needs a fresh attestation challenge.
 */
data class HouseholdStatus(
    val trial: Boolean = false,
    val sync: SyncLine? = null,
    val rejected: List<RejectedEvent> = emptyList(),
    val pendingDevices: List<PendingDevice> = emptyList(),
    val signingKeyNotRegistered: Boolean = false,
)

/**
 * @property lastSyncedAt the last sync that finished without an error (this run of the app).
 * @property blockedCode the server's code for a ledger entry of this phone it refused for good: later entries of this
 *   phone wait behind it on the other phones.
 * @property failing the latest sync ended with an error.
 */
data class SyncLine(val lastSyncedAt: Instant?, val blockedCode: String?, val failing: Boolean)

/** Who wrote something: a role and, for a kid's phone, the kid's first name; null when the device is unknown. */
data class DeviceLabel(val role: Role?, val kidName: String?, val fingerprint: String) {
    override fun toString(): String = "DeviceLabel(role=$role, fingerprint=$fingerprint)"
}

/** One refused event: the verifier's reason code, its author device and when it arrived. No payload. */
data class RejectedEvent(val reason: String, val device: DeviceLabel, val receivedAt: Instant)

data class PendingDevice(val deviceId: DeviceId, val fingerprint: String)

/** A short, human reason for a quarantine code (M3 §2 `QuarantineReason`, plus anything else as [OTHER]). */
enum class RejectionReason {
    UNREADABLE,
    ALTERED,
    UNKNOWN_DEVICE,
    DEVICE_NOT_ACTIVE,
    WRONG_ACCOUNT,
    ROLE_NOT_ALLOWED,
    WRONG_KID,
    BAD_SIGNATURE,
    PARENT_SIGNATURE_MISSING,
    PARENT_SIGNATURE_INVALID,
    OUT_OF_ORDER,
    CONFLICTING_HISTORY,
    OTHER,
    ;

    companion object {
        fun of(code: String): RejectionReason = when (code) {
            "UNDECODABLE", "SCHEMA_UNSUPPORTED" -> UNREADABLE
            "HASH_MISMATCH" -> ALTERED
            "UNKNOWN_DEVICE" -> UNKNOWN_DEVICE
            "DEVICE_NOT_ACTIVE" -> DEVICE_NOT_ACTIVE
            "UID_MISMATCH" -> WRONG_ACCOUNT
            "ROLE_NOT_ALLOWED" -> ROLE_NOT_ALLOWED
            "KID_MISMATCH" -> WRONG_KID
            "BAD_DEVICE_SIGNATURE" -> BAD_SIGNATURE
            "PARENT_SIGNATURE_MISSING" -> PARENT_SIGNATURE_MISSING
            "PARENT_SIGNATURE_INVALID" -> PARENT_SIGNATURE_INVALID
            "LAMPORT_TOO_FAR_AHEAD" -> OUT_OF_ORDER
            "FORKED_CHAIN" -> CONFLICTING_HISTORY
            else -> OTHER
        }
    }
}
