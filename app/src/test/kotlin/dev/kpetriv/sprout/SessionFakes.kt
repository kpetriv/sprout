package dev.kpetriv.sprout

import dev.kpetriv.sprout.core.database.Session
import dev.kpetriv.sprout.core.database.SessionStore
import dev.kpetriv.sprout.core.database.entity.RegistrationStatus
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.model.UserId
import dev.kpetriv.sprout.sync.SyncPause
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

/** In-memory [ShellPreferences]. */
class FakeShellPreferences : dev.kpetriv.sprout.sync.ShellPreferences {
    var linked = false
    var linkedDigest: String? = null
    val firstPulls = mutableSetOf<HouseholdId>()
    var parentSetup: String? = null
    var storedRemoval: dev.kpetriv.sprout.sync.Removal? = null

    override fun wasLinked(): Boolean = linked

    override fun markLinked() {
        linked = true
    }

    override fun linkedAccount(): String? = linkedDigest

    override fun setLinkedAccount(digest: String?) {
        linkedDigest = digest
    }

    override fun firstPullDone(householdId: HouseholdId): Boolean = householdId in firstPulls

    override fun recordFirstPull(householdId: HouseholdId) {
        firstPulls += householdId
    }

    override fun parentSetupPending(): String? = parentSetup

    override fun setParentSetupPending(digest: String?) {
        parentSetup = digest
    }

    var setupCurrency: String? = null

    override fun parentSetupCurrency(): String? = setupCurrency

    override fun setParentSetupCurrency(code: String?) {
        setupCurrency = code
    }

    override fun removal(): dev.kpetriv.sprout.sync.Removal? = storedRemoval

    override fun setRemoval(removal: dev.kpetriv.sprout.sync.Removal?) {
        storedRemoval = removal
    }

    /** The digest key; [wipe] replaces it. */
    var digestKey = ByteArray(dev.kpetriv.sprout.sync.ShellPreferences.DIGEST_KEY_BYTES) { 1 }
    var wipes = 0

    override fun digestOf(householdId: HouseholdId, uid: UserId?): String =
        dev.kpetriv.sprout.sync.ShellPreferences.hmacDigest(digestKey, householdId, uid)

    override fun wipe() {
        wipes += 1
        linkedDigest = null
        parentSetup = null
        setupCurrency = null
        firstPulls.clear()
        digestKey = ByteArray(dev.kpetriv.sprout.sync.ShellPreferences.DIGEST_KEY_BYTES) { (wipes + 1).toByte() }
    }
}

/** A [SyncPause] whose exclusion runs the block at once (no engine), with the pause flag as in the app. */
fun testPause(onExclusive: () -> Unit = {}): SyncPause = SyncPause(
    object : SyncPause.Exclusion {
        override suspend fun <T> exclusive(block: suspend () -> T): T {
            onExclusive()
            return block()
        }
    },
)
