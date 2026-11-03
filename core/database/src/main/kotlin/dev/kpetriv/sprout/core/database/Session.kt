package dev.kpetriv.sprout.core.database

import androidx.room.withTransaction
import dev.kpetriv.sprout.core.database.entity.AuditAction
import dev.kpetriv.sprout.core.database.entity.AuditLogEntity
import dev.kpetriv.sprout.core.database.entity.RegistrationStatus
import dev.kpetriv.sprout.core.database.entity.SessionEntity
import dev.kpetriv.sprout.core.domainledger.EventAuthor
import dev.kpetriv.sprout.core.domainledger.LedgerJson
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.model.UserId
import dev.kpetriv.sprout.core.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import kotlin.time.Instant

/**
 * Who is signed in on this device (M3 §1): the Firebase user, its household and role (from the custom claims), the
 * kid a KID device is linked to, and this device's registry entry.
 *
 * @property deviceId this device's id: the hex SHA-256 fingerprint of its signing key.
 * @property deviceStatus this device's registry status as last seen (a second parent device starts PENDING).
 */
data class Session(
    val uid: UserId,
    val householdId: HouseholdId,
    val role: Role,
    val kidId: KidId?,
    val deviceId: DeviceId,
    val deviceStatus: RegistrationStatus,
    val signedInAt: Instant,
) {
    init {
        require(role != Role.KID || kidId != null) { "a KID session names its kid" }
    }

    /** True for the pre-linking trial session ([LocalTrial]), which the purge removes. */
    val isLocalTrial: Boolean get() = householdId == LocalTrial.HOUSEHOLD

    /** The author of this session's own writes. */
    fun author(): EventAuthor = EventAuthor(householdId, uid, role, deviceId)

    companion object {
        /** The trial session of [deviceId], see [SessionStore.ensureLocalTrialSession]. */
        fun localTrial(deviceId: DeviceId, signedInAt: Instant): Session = Session(
            uid = LocalTrial.PARENT,
            householdId = LocalTrial.HOUSEHOLD,
            role = Role.PARENT,
            kidId = null,
            deviceId = deviceId,
            deviceStatus = RegistrationStatus.ACTIVE,
            signedInAt = signedInAt,
        )
    }
}

/**
 * The pre-linking local household (M1/M2 flows and the debug demo before onboarding exists): a single parent in
 * household `local`. Its data stays on this device and is deleted by `PurgeLocalHouseholdUseCase` when the device
 * joins a real household (ADR-0004 §2).
 */
object LocalTrial {
    val HOUSEHOLD: HouseholdId = HouseholdId("local")
    val PARENT: UserId = UserId("local-parent")
}

/** A write needs an author and nobody is signed in on this device. */
class NoSessionException : IllegalStateException("no session on this device")

/** The signed-in session, stored in the `session` table. */
interface SessionStore {
    fun observe(): Flow<Session?>

    suspend fun get(): Session?

    /** Replaces the session with [session] (sign-in, claims refresh, device status change); audited when it starts. */
    suspend fun save(session: Session)

    /** Signs this device out locally (no-op without a session). Audited. */
    suspend fun clear()

    /**
     * The session, creating the **trial session** (`Session.localTrial`: uid `local-parent`, household `local`, role
     * PARENT, [deviceId], ACTIVE) when there is none. The trial session is how the pre-linking local household
     * authors its writes; `PurgeLocalHouseholdUseCase` deletes it with the trial data when the device joins a real
     * household. An existing session (trial or real) is returned unchanged.
     */
    suspend fun ensureLocalTrialSession(deviceId: DeviceId): Session
}

/** [SessionStore] on Room. Session changes are audited with ids only. */
class RoomSessionStore @Inject constructor(private val database: SproutDatabase, private val clock: Clock) :
    SessionStore {
    private val sessions = database.sessionDao()

    override fun observe(): Flow<Session?> = sessions.observe().map { it?.toDomain() }.distinctUntilChanged()

    override suspend fun get(): Session? = sessions.get()?.toDomain()

    override suspend fun save(session: Session) {
        database.withTransaction {
            val current = sessions.get()?.toDomain()
            sessions.upsert(session.toEntity())
            if (current?.uid != session.uid || current.householdId != session.householdId) {
                audit(session, AuditAction.SESSION_STARTED)
            }
        }
    }

    override suspend fun clear() {
        database.withTransaction {
            val current = sessions.get()?.toDomain() ?: return@withTransaction
            sessions.deleteAll()
            audit(current, AuditAction.SESSION_CLEARED)
        }
    }

    override suspend fun ensureLocalTrialSession(deviceId: DeviceId): Session = database.withTransaction {
        sessions.get()?.toDomain() ?: Session.localTrial(deviceId, clock.now()).also { trial ->
            sessions.upsert(trial.toEntity())
            audit(trial, AuditAction.SESSION_STARTED)
        }
    }

    private suspend fun audit(session: Session, action: AuditAction) {
        val details = buildJsonObject {
            put("role", session.role.name)
            session.kidId?.let { put("kidId", it.value) }
            put("deviceStatus", session.deviceStatus.name)
        }
        database.auditLogDao().insert(
            AuditLogEntity(
                atEpochMs = clock.now().toEpochMilliseconds(),
                actorUid = session.uid,
                deviceId = session.deviceId,
                action = action,
                target = session.householdId.value,
                detailsJson = LedgerJson.json.encodeToString(JsonObject.serializer(), details),
            ),
        )
    }
}

internal fun SessionEntity.toDomain(): Session = Session(
    uid = uid,
    householdId = householdId,
    role = role,
    kidId = kidId,
    deviceId = deviceId,
    deviceStatus = deviceStatus,
    signedInAt = Instant.fromEpochMilliseconds(signedInAtEpochMs),
)

internal fun Session.toEntity(): SessionEntity = SessionEntity(
    uid = uid,
    householdId = householdId,
    role = role,
    kidId = kidId,
    deviceId = deviceId,
    deviceStatus = deviceStatus,
    signedInAtEpochMs = signedInAt.toEpochMilliseconds(),
)
