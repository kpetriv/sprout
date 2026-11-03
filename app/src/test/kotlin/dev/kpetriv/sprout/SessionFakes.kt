package dev.kpetriv.sprout

import dev.kpetriv.sprout.core.database.Session
import dev.kpetriv.sprout.core.database.SessionStore
import dev.kpetriv.sprout.core.database.entity.RegistrationStatus
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.model.UserId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.time.Instant

val THIS_DEVICE = DeviceId("d".repeat(64))
val REAL_HOUSEHOLD = HouseholdId("hh-real")
val PARENT = UserId("parent-uid")

fun parentSession(status: RegistrationStatus = RegistrationStatus.ACTIVE, household: HouseholdId = REAL_HOUSEHOLD) =
    Session(PARENT, household, Role.PARENT, null, THIS_DEVICE, status, Instant.fromEpochSeconds(0))

fun kidSession(kidId: KidId = KidId("kid-leo")) = Session(
    UserId("kid-uid"),
    REAL_HOUSEHOLD,
    Role.KID,
    kidId,
    THIS_DEVICE,
    RegistrationStatus.ACTIVE,
    Instant.fromEpochSeconds(0),
)

fun trialSession() = Session.localTrial(THIS_DEVICE, Instant.fromEpochSeconds(0))

/** In-memory [SessionStore]; [clears] counts [clear] calls. */
class FakeSessionStore(initial: Session? = null) : SessionStore {
    val session = MutableStateFlow(initial)
    var clears = 0

    override fun observe(): Flow<Session?> = session

    override suspend fun get(): Session? = session.value

    override suspend fun save(session: Session) {
        this.session.value = session
    }

    override suspend fun clear() {
        clears += 1
        session.value = null
    }

    override suspend fun ensureLocalTrialSession(deviceId: DeviceId): Session =
        session.value ?: Session.localTrial(deviceId, Instant.fromEpochSeconds(0)).also { session.value = it }
}
