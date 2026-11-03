package dev.kpetriv.sprout.feature.settings

import dev.kpetriv.sprout.core.database.entity.RegistrationStatus
import dev.kpetriv.sprout.core.domainledger.AttestationLevel
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.Role
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject
import kotlin.time.Instant

/*
 * The synced household's settings (M3 §6): devices, kid linking, the account, export and deletion. The app adapts
 * these to core/sync-firebase (the callables, each with this device's proof), core/database (the registry mirror,
 * the session, the wipe) and the sync engine; tests use fakes. Failures are [AdminException] codes only.
 */

/** Why a household action failed, coarse enough to show. */
enum class AdminFailure {
    /** Offline or server trouble: try again. */
    NETWORK,

    /** Only an ACTIVE parent device may do this (or this device is not one any more). */
    NOT_ALLOWED,

    /** Too many link codes or attempts: wait a while. */
    RATE_LIMITED,

    /** The household is too large to export in one piece (D-M3-7). */
    TOO_LARGE,

    FAILED,
}

/** The failure of a port call. The message is the [failure]'s name; no cause is attached. */
class AdminException(val failure: AdminFailure) : Exception(failure.name)

/**
 * One registered device of the household (the local mirror of `devices/{deviceId}`).
 *
 * @property kidName the linked kid's first name for a KID device; null otherwise or when unknown.
 * @property isThisDevice the device this app runs on (it cannot revoke itself).
 */
data class HouseholdDevice(
    val deviceId: DeviceId,
    val role: Role,
    val kidName: String?,
    val attestation: AttestationLevel,
    val status: RegistrationStatus,
    val isThisDevice: Boolean,
    val registeredAt: Instant,
) {
    override fun toString(): String = "HouseholdDevice(deviceId=$deviceId, role=$role, status=$status)"
}

/** The household's devices, approvals and revocations. */
interface DeviceAdmin {
    fun observeDevices(): Flow<List<HouseholdDevice>>

    /** Approves a PENDING parent device, signed by this device's key over the approval payload. */
    suspend fun approve(deviceId: DeviceId): Result<Unit>

    /** Revokes a device: its later writes are refused everywhere and a kid device wipes itself (T7). */
    suspend fun revoke(deviceId: DeviceId): Result<Unit>
}

/** A single-use code that links a kid's phone. Never logged: [toString] hides [code] (T6). */
class IssuedLinkCode(val code: String, val expiresAt: Instant) {
    override fun toString(): String = "IssuedLinkCode(expiresAt=$expiresAt)"
}

fun interface KidLinking {
    suspend fun createLinkCode(kidId: KidId): Result<IssuedLinkCode>
}

/**
 * Who is signed in. [email] is the parent's address when known (null until the auth session exposes it, and for the
 * trial household); [trial] for the pre-linking trial household, which has no account.
 */
data class AccountInfo(val email: String?, val householdId: HouseholdId?, val trial: Boolean) {
    override fun toString(): String = "AccountInfo(householdId=$householdId, trial=$trial)"
}

interface AccountControl {
    fun observe(): Flow<AccountInfo>

    /** Signs out of Firebase and clears the session; the data on this phone stays. */
    suspend fun signOut()
}

/** The household's data as a whole: export and deletion (PRIVACY.md, D-M3-7). */
interface HouseholdData {
    /** The `exportHousehold` bundle. */
    suspend fun export(): Result<JsonObject>

    /** Deletes the household and every member's account on the server. */
    suspend fun deleteHousehold(): Result<Unit>

    /** Erases this phone's household data and restarts the app (after a deletion). */
    suspend fun eraseAndRestart()
}

/** Writes an exported file to the document the parent picked (`ACTION_CREATE_DOCUMENT`); [destination] is its URI. */
fun interface ExportWriter {
    suspend fun write(destination: String, bytes: ByteArray)
}

/** What both phones show for a device: its id's first 16 hex characters in groups of four. */
internal fun displayFingerprint(deviceId: DeviceId): String =
    deviceId.value.take(FINGERPRINT_CHARS).chunked(FINGERPRINT_GROUP).joinToString(" ")

private const val FINGERPRINT_CHARS = 16
private const val FINGERPRINT_GROUP = 4
