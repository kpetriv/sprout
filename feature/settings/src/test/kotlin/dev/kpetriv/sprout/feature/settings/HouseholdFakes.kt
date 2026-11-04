package dev.kpetriv.sprout.feature.settings

import dev.kpetriv.sprout.core.database.entity.RegistrationStatus
import dev.kpetriv.sprout.core.domainledger.AttestationLevel
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.model.ThreadId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.serialization.json.JsonObject
import kotlin.time.Instant

fun deviceId(prefix: String): DeviceId = DeviceId(prefix.padEnd(64, '0'))

val THIS_PHONE = deviceId("aaaa1111bbbb2222")
val SECOND_PHONE = deviceId("3fa9c01d77e2b4a8")
val LEO_PHONE = deviceId("c0ffee00d00dfeed")
val OLD_PHONE = deviceId("dead0000beef1111")

fun householdDevices(): List<HouseholdDevice> = listOf(
    HouseholdDevice(
        THIS_PHONE,
        Role.PARENT,
        null,
        AttestationLevel.STRONGBOX,
        RegistrationStatus.ACTIVE,
        true,
        Instant.fromEpochSeconds(100),
    ),
    HouseholdDevice(
        OLD_PHONE,
        Role.KID,
        "Mia",
        AttestationLevel.TEE,
        RegistrationStatus.REVOKED,
        false,
        Instant.fromEpochSeconds(150),
    ),
    HouseholdDevice(
        LEO_PHONE,
        Role.KID,
        "Leo",
        AttestationLevel.TEE,
        RegistrationStatus.ACTIVE,
        false,
        Instant.fromEpochSeconds(200),
    ),
    HouseholdDevice(
        SECOND_PHONE,
        Role.PARENT,
        null,
        AttestationLevel.NONE,
        RegistrationStatus.PENDING,
        false,
        Instant.fromEpochSeconds(300),
    ),
)

class FakeDeviceAdmin(initial: List<HouseholdDevice>? = householdDevices()) : DeviceAdmin {
    val devices = MutableStateFlow(initial)
    val approved = mutableListOf<DeviceId>()
    val revoked = mutableListOf<DeviceId>()
    var result: Result<Unit> = Result.success(Unit)

    override fun observeDevices(): Flow<List<HouseholdDevice>> = devices.filterNotNull()

    override suspend fun approve(deviceId: DeviceId): Result<Unit> {
        approved += deviceId
        return result
    }

    override suspend fun revoke(deviceId: DeviceId): Result<Unit> {
        revoked += deviceId
        return result
    }
}

class FakeKidLinking(var expiresAt: Instant) : KidLinking {
    val requests = mutableListOf<KidId>()
    var failure: AdminFailure? = null
    var codes = ArrayDeque(listOf("K7QM2X9P", "ZZZZ2222"))

    override suspend fun createLinkCode(kidId: KidId): Result<IssuedLinkCode> {
        requests += kidId
        failure?.let { return Result.failure(AdminException(it)) }
        return Result.success(IssuedLinkCode(codes.removeFirst(), expiresAt))
    }
}

class FakeAccountControl(
    info: AccountInfo = AccountInfo(email = null, householdId = HouseholdId("hh7f3k2q9xabc"), trial = false),
) : AccountControl {
    val info = MutableStateFlow(info)
    var signOuts = 0

    override fun observe(): Flow<AccountInfo> = info

    override suspend fun signOut() {
        signOuts += 1
    }
}

class FakeHouseholdData(var bundle: Result<JsonObject>) : HouseholdData {
    var deletes = 0
    var erased = 0
    var deleteResult: Result<Unit> = Result.success(Unit)

    /** Answers to the next deletions, in order; [deleteResult] once used up. */
    val deleteResults = ArrayDeque<Result<Unit>>()
    var signInResult: Result<Unit> = Result.success(Unit)
    val passwords = mutableListOf<String?>()

    var exports = 0

    override suspend fun export(): Result<JsonObject> {
        exports += 1
        return bundle
    }

    override suspend fun deleteHousehold(): Result<Unit> {
        deletes += 1
        return deleteResults.removeFirstOrNull() ?: deleteResult
    }

    override val signInUsesPassword: Boolean = true

    override suspend fun signInAgain(activityContext: android.content.Context, password: String?): Result<Unit> {
        passwords += password
        return signInResult
    }

    override suspend fun eraseAndRestart() {
        erased += 1
    }
}

class FakeExportWriter : ExportWriter {
    val written = mutableMapOf<String, ByteArray>()

    override suspend fun write(destination: String, bytes: ByteArray) {
        written[destination] = bytes
    }
}

/** The household policy's retention and game cap; [failWrites] makes every write throw. */
class FakeHouseholdRules(initial: HouseholdRulesState = HouseholdRulesState(null, null)) : HouseholdRules {
    val state = MutableStateFlow(initial)
    val retentionWrites = mutableListOf<Int?>()
    val capWrites = mutableListOf<Long?>()
    var failWrites = false

    override fun observe(): Flow<HouseholdRulesState> = state

    override suspend fun setChatRetentionDays(days: Int?) {
        check(!failWrites) { "write failed" }
        retentionWrites += days
        state.value = state.value.copy(chatRetentionDays = days)
    }

    override suspend fun setDailyGameCap(minorUnits: Long?) {
        check(!failWrites) { "write failed" }
        capWrites += minorUnits
        state.value = state.value.copy(dailyGameCapMinor = minorUnits)
    }
}

class FakeChatHistoryPurger(var outcome: ChatPurgeOutcome = ChatPurgeOutcome.PURGED) : ChatHistoryPurger {
    var purges = 0

    override suspend fun purgeAll(): ChatPurgeOutcome {
        purges += 1
        return outcome
    }
}

/** Answers every confirmation with [outcome] and records what was asked. */
class FakeParentConfirmation(var outcome: ConfirmationOutcome = ConfirmationOutcome.CONFIRMED) : ParentConfirmation {
    val asked = mutableListOf<ConfirmationPurpose>()

    override suspend fun confirm(purpose: ConfirmationPurpose): ConfirmationOutcome {
        asked += purpose
        return outcome
    }
}

/** [threads] and the bytes of each media file by name; a file missing from [media] is reported NOT_AVAILABLE. */
class FakeChatExportSource(
    var threads: List<ChatExportThread> = emptyList(),
    val media: MutableMap<String, ByteArray> = mutableMapOf(),
) : ChatExportSource {
    var failThreads = false
    val mediaRequests = mutableListOf<ThreadId>()

    override suspend fun threads(): List<ChatExportThread> {
        check(!failThreads) { "cannot read chat" }
        return threads
    }

    override suspend fun media(thread: ChatExportThread, sink: ChatMediaSink): List<ChatMissingMedia> {
        mediaRequests += thread.threadId
        val missing = mutableListOf<ChatMissingMedia>()
        thread.messages.mapNotNull { it.media }.distinct().forEach { name ->
            val bytes = media[name]
            if (bytes == null) {
                missing += ChatMissingMedia(name, "NOT_AVAILABLE")
            } else {
                val buffer = bytes.copyOf()
                sink.write(name, buffer)
                buffer.fill(0) // As the real reader does: the sink must have copied.
            }
        }
        return missing
    }
}
