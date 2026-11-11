package dev.kpetriv.sprout.core.sync

import dev.kpetriv.sprout.core.domainledger.RegisteredDevice
import dev.kpetriv.sprout.core.model.BlobId
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.model.UserId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import java.util.concurrent.atomic.AtomicInteger

/**
 * One device's connection to a [FakeBackend], authenticated as [uid] with role claim [role] (what Firebase Auth
 * custom claims would carry). [changes] emits for every server write made by another device (or by the test) while
 * this device is not partitioned, except blob chunks (never pulled, only fetched: [fetchBlob]).
 */
class FakeSyncTransport(private val backend: FakeBackend, val deviceId: DeviceId, val uid: UserId, val role: Role) :
    SyncTransport {
    private val caller = FakeBackend.Caller(deviceId, uid, role)
    private val pushes = AtomicInteger()
    private val pulls = AtomicInteger()

    val pushCount: Int get() = pushes.get()
    val pullCount: Int get() = pulls.get()

    private val fetches = AtomicInteger()

    val fetchCount: Int get() = fetches.get()

    override suspend fun push(items: List<PushItem>): PushResult {
        pushes.incrementAndGet()
        return backend.push(caller, items)
    }

    override suspend fun pull(cursor: SyncCursor): PullPage {
        pulls.incrementAndGet()
        return backend.pull(caller, cursor)
    }

    override suspend fun fetchBlob(blobId: BlobId): Result<List<BlobChunkJson>> {
        fetches.incrementAndGet()
        return backend.fetchBlob(caller, blobId)
    }

    override suspend fun registeredDevice(householdId: HouseholdId, deviceId: DeviceId): RegisteredDevice? =
        backend.registeredDevice(caller, householdId, deviceId)

    override fun changes(): Flow<RemoteChange> = backend.writes
        .filter { it.author != deviceId && it.collection != FakeBackend.BLOBS && !backend.isPartitioned(deviceId) }
        .map { RemoteChange(it.collection) }

    override suspend fun isAvailable(): Boolean = backend.isAvailable(deviceId)
}
