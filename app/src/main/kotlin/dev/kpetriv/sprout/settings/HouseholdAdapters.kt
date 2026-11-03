package dev.kpetriv.sprout.settings

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.kpetriv.sprout.core.common.Dispatcher
import dev.kpetriv.sprout.core.common.SproutDispatchers
import dev.kpetriv.sprout.core.crypto.DeviceSigner
import dev.kpetriv.sprout.core.database.KidRepository
import dev.kpetriv.sprout.core.database.SessionStore
import dev.kpetriv.sprout.core.database.SproutDatabase
import dev.kpetriv.sprout.core.database.entity.DeviceEntity
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.syncfirebase.AccountInfoReader
import dev.kpetriv.sprout.core.syncfirebase.FunctionsError
import dev.kpetriv.sprout.core.syncfirebase.FunctionsException
import dev.kpetriv.sprout.core.syncfirebase.HouseholdFunctions
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
import dev.kpetriv.sprout.sync.AppRestarter
import dev.kpetriv.sprout.sync.LocalDataEraser
import dev.kpetriv.sprout.sync.SyncController
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import javax.inject.Inject
import kotlin.time.Instant

/**
 * Settings → Devices over the local registry mirror (`devices`, written only by sync), the kids' names and the
 * session. Approving signs `"sprout/approve-device/v1" || 0x00 || deviceId` with this device's key; revoking carries
 * this device's proof ([HouseholdAdminFunctions]). Either then syncs, so the list shows the new status.
 */
@Suppress("LongParameterList") // Registry, names, session, signer, the two callable clients and sync.
class RegistryDeviceAdmin @Inject constructor(
    private val database: SproutDatabase,
    private val kids: KidRepository,
    private val sessions: SessionStore,
    private val signer: DeviceSigner,
    private val functions: HouseholdFunctions,
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

    override suspend fun approve(deviceId: DeviceId): Result<Unit> {
        val session = sessions.get() ?: return Result.failure(AdminException(AdminFailure.NOT_ALLOWED))
        val signature = signer.sign(HouseholdFunctions.approvalPayload(deviceId))
        return functions.approveDevice(deviceId, session.deviceId, signature).toAdmin().also { sync.requestSync() }
    }

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
 */
class SessionAccountControl @Inject constructor(
    private val sessions: SessionStore,
    private val auth: dev.kpetriv.sprout.core.syncfirebase.FirebaseAuthSession,
    private val accounts: AccountInfoReader,
) : AccountControl {
    override fun observe(): Flow<AccountInfo> = sessions.observe().map { session ->
        val parent = session?.takeIf { !it.isLocalTrial && it.role == Role.PARENT }
        AccountInfo(
            email = parent?.let { accounts.accountInfo()?.email },
            householdId = session?.householdId?.takeUnless { session.isLocalTrial },
            trial = session?.isLocalTrial ?: false,
        )
    }

    override suspend fun signOut() {
        auth.signOut()
        sessions.clear()
    }
}

/** Settings → Household data over `exportHousehold` / `deleteHousehold` (with this device's proof) and the wipe. */
class FunctionsHouseholdData @Inject constructor(
    private val admin: HouseholdAdminFunctions,
    private val eraser: LocalDataEraser,
    private val restarter: AppRestarter,
) : HouseholdData {
    override suspend fun export(): Result<JsonObject> =
        admin.exportHousehold().toAdmin(exhausted = AdminFailure.TOO_LARGE)

    override suspend fun deleteHousehold(): Result<Unit> = admin.deleteHousehold().toAdmin()

    override suspend fun eraseAndRestart() {
        eraser.erase()
        restarter.restart()
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

/** Maps a callable failure to the settings' coarse [AdminFailure]. */
internal fun <T> Result<T>.toAdmin(exhausted: AdminFailure = AdminFailure.RATE_LIMITED): Result<T> =
    recoverCatching { failure ->
        if (failure is AdminException) throw failure
        throw AdminException(
            when ((failure as? FunctionsException)?.error) {
                FunctionsError.Unavailable -> AdminFailure.NETWORK

                FunctionsError.ResourceExhausted -> exhausted

                FunctionsError.PermissionDenied, FunctionsError.Unauthenticated, FunctionsError.FailedPrecondition ->
                    AdminFailure.NOT_ALLOWED

                else -> AdminFailure.FAILED
            },
        )
    }
