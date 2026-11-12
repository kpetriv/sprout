package dev.kpetriv.sprout.settings

import android.content.Context
import android.net.Uri
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.kpetriv.sprout.core.common.Dispatcher
import dev.kpetriv.sprout.core.common.SproutDispatchers
import dev.kpetriv.sprout.core.database.KidRepository
import dev.kpetriv.sprout.core.database.SessionStore
import dev.kpetriv.sprout.core.database.SproutDatabase
import dev.kpetriv.sprout.core.database.entity.DeviceEntity
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.syncfirebase.AccountInfoReader
import dev.kpetriv.sprout.core.syncfirebase.AuthError
import dev.kpetriv.sprout.core.syncfirebase.AuthException
import dev.kpetriv.sprout.core.syncfirebase.AuthState
import dev.kpetriv.sprout.core.syncfirebase.FunctionsError
import dev.kpetriv.sprout.core.syncfirebase.FunctionsException
import dev.kpetriv.sprout.feature.settings.AccountControl
import dev.kpetriv.sprout.feature.settings.AccountInfo
import dev.kpetriv.sprout.feature.settings.AdminException
import dev.kpetriv.sprout.feature.settings.AdminFailure
import dev.kpetriv.sprout.feature.settings.DeviceAdmin
import dev.kpetriv.sprout.feature.settings.ExportWriter
import dev.kpetriv.sprout.feature.settings.HouseholdData
import dev.kpetriv.sprout.feature.settings.HouseholdDevice
import dev.kpetriv.sprout.feature.settings.IssuedLinkCode
import dev.kpetriv.sprout.feature.settings.KidLinking
import dev.kpetriv.sprout.household.HouseholdAdminFunctions
import dev.kpetriv.sprout.sync.GateAuth
import dev.kpetriv.sprout.sync.HouseholdRemovals
import dev.kpetriv.sprout.sync.RemovalReason
import dev.kpetriv.sprout.sync.SyncController
import dev.kpetriv.sprout.sync.SyncPause
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import javax.inject.Inject
import kotlin.time.Instant

/**
 * Settings → Devices over the local registry mirror (`devices`, written only by sync), the kids' names and the
 * session. Approving and revoking carry this device's proof ([HouseholdAdminFunctions]). Either then syncs, so the
 * list shows the new status.
 */
class RegistryDeviceAdmin @Inject constructor(
    private val database: SproutDatabase,
    private val kids: KidRepository,
    private val sessions: SessionStore,
    private val admin: HouseholdAdminFunctions,
    private val sync: SyncController,
) : DeviceAdmin {
    override fun observeDevices(): Flow<List<HouseholdDevice>> = combine(
        database.deviceDao().observeAll(),
        kids.observeKids(),
        sessions.observe(),
    ) { devices, kidList, session ->
        val names = kidList.associate { it.id to it.firstName }
        devices.map { it.toHouseholdDevice(names, session?.deviceId) }
    }

    override suspend fun approve(deviceId: DeviceId): Result<Unit> =
        admin.approveDevice(deviceId).toAdmin().also { sync.requestSync() }

    override suspend fun revoke(deviceId: DeviceId): Result<Unit> =
        admin.revokeDevice(deviceId).map { }.toAdmin().also { sync.requestSync() }

    private fun DeviceEntity.toHouseholdDevice(names: Map<KidId, String>, own: DeviceId?) = HouseholdDevice(
        deviceId = deviceId,
        role = role,
        kidName = kidId?.let(names::get),
        attestation = attestation,
        status = status,
        isThisDevice = deviceId == own,
        registeredAt = Instant.fromEpochMilliseconds(registeredAtEpochMs),
    )
}

/** "Link a device" over `createLinkCode` (with this device's proof). The code is never logged. */
class FunctionsKidLinking @Inject constructor(private val admin: HouseholdAdminFunctions) : KidLinking {
    override suspend fun createLinkCode(kidId: KidId): Result<IssuedLinkCode> = admin.createLinkCode(kidId)
        .map { IssuedLinkCode(it.code, Instant.fromEpochMilliseconds(it.expiresAt)) }
        .toAdmin()
}

/**
 * Settings → Account over the session. The parent's e-mail address is read on demand, each time the session is
 * observed, from [AccountInfoReader] (`FirebaseAuthSession.accountInfo`): core/sync-firebase's `AuthState` carries no
 * e-mail on purpose. It is personal data, so it only flows to the Account screen's state: never stored, never logged
 * ([AccountInfo.toString] hides it). A kid session and the trial household show none.
 *
 * Signing out is a removal ([RemovalReason.SIGNED_OUT], D-M3-47): stored at once, then [HouseholdRemovals]' owner
 * stops sync, erases this phone's copy and signs out of Firebase; the shell shows the erase screen and restarts.
 * Before that, one sync is tried for at most [FLUSH_TIMEOUT_MS] (the dialog shows that it is signing out): changes
 * still in the outbox are lost by the erase, and a chat message among them would leave a gap in this device's `seq`
 * that the kept counters jump over after a restore (D-REL-25, D-REL-35). Offline, it fails fast or times out; the
 * sign-out goes on either way.
 */
class SessionAccountControl internal constructor(
    private val sessions: SessionStore,
    private val removals: HouseholdRemovals,
    private val accounts: AccountInfoReader,
    private val flushOutbox: suspend () -> Unit = {},
    private val flushTimeoutMs: Long = FLUSH_TIMEOUT_MS,
) : AccountControl {
    @Inject
    constructor(
        sessions: SessionStore,
        removals: HouseholdRemovals,
        accounts: AccountInfoReader,
        sync: Lazy<SyncController>,
    ) : this(sessions, removals, accounts, flushOutbox = { sync.get().syncNow() })

    override fun observe(): Flow<AccountInfo> = sessions.observe().map { session ->
        val parent = session?.takeIf { !it.isLocalTrial && it.role == Role.PARENT }
        AccountInfo(
            email = parent?.let { accounts.accountInfo()?.email },
            householdId = session?.householdId?.takeUnless { session.isLocalTrial },
            trial = session?.isLocalTrial ?: false,
        )
    }

    override suspend fun signOut() {
        val session = sessions.get()
        if (session == null || session.isLocalTrial) return
        flushBestEffort()
        removals.remove(RemovalReason.SIGNED_OUT)
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // Dropped unlogged: the sign-out goes on.
    private suspend fun flushBestEffort() {
        try {
            withTimeoutOrNull(flushTimeoutMs) { flushOutbox() }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            // Nothing to show or log.
        }
    }

    companion object {
        /** How long a sign-out waits for its last sync. */
        const val FLUSH_TIMEOUT_MS: Long = 5_000
    }
}

/**
 * Settings → Household data over `exportHousehold` / `deleteHousehold` (with this device's proof), the "sign in again
 * to confirm" step and the wipe. The server answers `failed-precondition` to a deletion without a sign-in in the last
 * 5 minutes (D-M3-34): that is [AdminFailure.REAUTH_REQUIRED]. Confirming re-authenticates the session's own account
 * ([RecentSignIn]); it never replaces it. After a deletion the wipe is the removal owner's ([HouseholdRemovals]): it
 * runs on the app scope, so the settings screen leaving composition when the session goes cannot cut it short, and
 * the erase screen then restarts the app.
 */
class FunctionsHouseholdData @Inject constructor(
    private val admin: HouseholdAdminFunctions,
    private val removals: HouseholdRemovals,
    private val reauth: RecentSignIn,
) : HouseholdData {
    override suspend fun export(): Result<JsonObject> =
        admin.exportHousehold().toAdmin(exhausted = AdminFailure.TOO_LARGE)

    override suspend fun deleteHousehold(): Result<Unit> =
        admin.deleteHousehold().toAdmin(precondition = AdminFailure.REAUTH_REQUIRED)

    override val signInUsesPassword: Boolean get() = reauth.usesPassword

    override suspend fun signInAgain(activityContext: Context, password: String?): Result<Unit> =
        reauth.signInAgain(activityContext, password)

    override suspend fun eraseAndRestart() = removals.remove(RemovalReason.HOUSEHOLD_GONE)
}

/**
 * A fresh sign-in of the session's own parent account for `deleteHousehold`'s 5-minute rule (audit M-1): the signed-in
 * user is **re-authenticated** (`FirebaseUser.reauthenticate`, [GateAuth.reauthenticateWithGoogle] in release, the
 * debug e-mail/password path against the Auth emulator with the current account's address and the typed password),
 * so it is never replaced; another account fails with [AdminFailure.WRONG_ACCOUNT] and changes nothing. It runs under
 * the [SyncPause], so no sync overlaps it.
 */
class RecentSignIn @Inject constructor(
    private val sessions: SessionStore,
    private val auth: GateAuth,
    private val accounts: AccountInfoReader,
    private val pause: SyncPause,
) {
    val usesPassword: Boolean get() = auth.usesEmailPassword

    @Suppress("ReturnCount") // One early return per way the confirmation can fail.
    suspend fun signInAgain(activityContext: Context, password: String?): Result<Unit> {
        val session = sessions.get()?.takeUnless { it.isLocalTrial }
            ?: return Result.failure(AdminException(AdminFailure.NOT_ALLOWED))
        if ((auth.current() as? AuthState.SignedIn)?.uid != session.uid) {
            // Not the session's account (or nobody): nothing to re-authenticate; the gate handles a lost sign-in.
            return Result.failure(AdminException(AdminFailure.WRONG_ACCOUNT))
        }
        val email = if (auth.usesEmailPassword) accounts.accountInfo()?.email else null
        if (auth.usesEmailPassword && (email == null || password.isNullOrEmpty())) {
            return Result.failure(AdminException(AdminFailure.SIGN_IN_FAILED))
        }
        val reauthenticated = pause.pausing {
            if (email != null && password != null) {
                auth.reauthenticateWithEmailPassword(email, password)
            } else {
                auth.reauthenticateWithGoogle(activityContext)
            }
        }
        reauthenticated.exceptionOrNull()?.let { failure ->
            val code = when ((failure as? AuthException)?.error) {
                AuthError.UserMismatch -> AdminFailure.WRONG_ACCOUNT
                AuthError.Network -> AdminFailure.NETWORK
                else -> AdminFailure.SIGN_IN_FAILED
            }
            return Result.failure(AdminException(code))
        }
        return Result.success(Unit)
    }
}

/** Writes the export to the document the parent picked, through the content resolver. */
class ContentResolverExportWriter @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:Dispatcher(SproutDispatchers.IO) private val dispatcher: CoroutineDispatcher,
) : ExportWriter {
    override suspend fun write(destination: String, bytes: ByteArray) = withContext(dispatcher) {
        val stream = context.contentResolver.openOutputStream(Uri.parse(destination), "wt")
            ?: throw IOException("no output stream")
        stream.use { it.write(bytes) }
    }
}

/**
 * Maps a callable failure to the settings' coarse [AdminFailure]: [exhausted] for `resource-exhausted`,
 * [precondition] for `failed-precondition` (a deletion's "sign in again").
 */
internal fun <T> Result<T>.toAdmin(
    exhausted: AdminFailure = AdminFailure.RATE_LIMITED,
    precondition: AdminFailure = AdminFailure.NOT_ALLOWED,
): Result<T> = recoverCatching { failure ->
    if (failure is AdminException) throw failure
    throw AdminException(
        when ((failure as? FunctionsException)?.error) {
            FunctionsError.Unavailable -> AdminFailure.NETWORK
            FunctionsError.ResourceExhausted -> exhausted
            FunctionsError.FailedPrecondition -> precondition
            FunctionsError.PermissionDenied, FunctionsError.Unauthenticated -> AdminFailure.NOT_ALLOWED
            else -> AdminFailure.FAILED
        },
    )
}
