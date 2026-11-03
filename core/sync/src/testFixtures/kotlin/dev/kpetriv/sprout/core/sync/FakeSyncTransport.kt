package dev.kpetriv.sprout.core.sync

import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.model.UserId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * One device's connection to a [FakeBackend], authenticated as [uid] with role claim [role] (what Firebase Auth
 * custom claims would carry). [changes] emits for every server write made by another device (or by the test) while
 * this device is not partitioned.
 */
class FakeSyncTransport(private val backend: FakeBackend, val deviceId: DeviceId, val uid: UserId, val role: Role) :
    SyncTransport {
    private val caller = FakeBackend.Caller(deviceId, uid, role)
    private val pushes = AtomicInteger()
    private val pulls = AtomicInteger()

    val pushCount: Int get() = pushes.get()
    val pullCount: Int get() = pulls.get()

    override suspend fun push(events: List<JsonObject>, documents: List<DocumentEnvelope>): PushResult {
        pushes.incrementAndGet()
        return backend.push(caller, events, documents)
    }

    override suspend fun pull(cursor: SyncCursor): PullPage {
        pulls.incrementAndGet()
        return backend.pull(deviceId, cursor)
    }

    override fun changes(): Flow<RemoteChange> = backend.writes
        .filter { it.author != deviceId && !backend.isPartitioned(deviceId) }
        .map { RemoteChange(it.collection) }

    override suspend fun isAvailable(): Boolean = backend.isAvailable(deviceId)
}
