package dev.kpetriv.sprout.core.database

import dev.kpetriv.sprout.core.crypto.DeviceSigner
import dev.kpetriv.sprout.core.domainledger.EventAuthor
import dev.kpetriv.sprout.core.domainledger.toHex
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.model.UserId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** Who is writing right now: household, user, role and this device. */
interface SessionAuthorProvider {
    suspend fun currentAuthor(): EventAuthor
}

/**
 * TEMPORARY (M1 only, replaced by the signed-in session in M3): every local write is authored by a single local
 * parent in household [LOCAL_HOUSEHOLD]. The device id is the hex SHA-256 fingerprint of this device's signing key,
 * which is what M3 registers, so the hash chain written now keeps its author id.
 */
@Singleton
class LocalOnlyAuthorProvider @Inject constructor(private val signer: DeviceSigner) : SessionAuthorProvider {
    private val mutex = Mutex()
    private var author: EventAuthor? = null

    override suspend fun currentAuthor(): EventAuthor = mutex.withLock {
        author ?: EventAuthor(
            householdId = LOCAL_HOUSEHOLD,
            uid = LOCAL_PARENT,
            role = Role.PARENT,
            deviceId = DeviceId(signer.publicKey().fingerprint().toHex()),
        ).also { author = it }
    }

    companion object {
        val LOCAL_HOUSEHOLD: HouseholdId = HouseholdId("local")
        val LOCAL_PARENT: UserId = UserId("local-parent")
    }
}
