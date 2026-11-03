package dev.kpetriv.sprout.feature.settings

import dev.kpetriv.sprout.core.database.entity.RegistrationStatus
import dev.kpetriv.sprout.core.domainledger.AttestationLevel
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.Role
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

    override suspend fun export(): Result<JsonObject> = bundle

    override suspend fun deleteHousehold(): Result<Unit> {
        deletes += 1
        return deleteResult
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
