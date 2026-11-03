package dev.kpetriv.sprout.core.database.usecase

import dev.kpetriv.sprout.core.crypto.KeyAgreementException
import dev.kpetriv.sprout.core.crypto.X25519PrivateKey
import dev.kpetriv.sprout.core.crypto.chat.ThreadKey
import dev.kpetriv.sprout.core.database.ChatCrypto
import dev.kpetriv.sprout.core.database.RoomDeviceRegistry
import dev.kpetriv.sprout.core.database.SessionStore
import dev.kpetriv.sprout.core.database.SproutDatabase
import dev.kpetriv.sprout.core.database.ThreadKeyStore
import dev.kpetriv.sprout.core.database.ThreadKeyWrites
import dev.kpetriv.sprout.core.database.entity.KidEntity
import dev.kpetriv.sprout.core.database.entity.ThreadKeyEntity
import dev.kpetriv.sprout.core.domainledger.RegisteredDevice
import dev.kpetriv.sprout.core.domainledger.SignatureCheck
import dev.kpetriv.sprout.core.domainledger.chat.ThreadKeyDecision
import dev.kpetriv.sprout.core.domainledger.chat.ThreadKeyDocument
import dev.kpetriv.sprout.core.domainledger.chat.ThreadKeyPolicy
import dev.kpetriv.sprout.core.domainledger.chat.ThreadMember
import dev.kpetriv.sprout.core.domainledger.chat.ThreadMembers
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.model.ThreadId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.WeakHashMap
import javax.inject.Inject

/**
 * Keeps every kid's thread key in line with the registry mirror (M4 §1.2, D-M4-7), on PARENT devices: for each kid
 * thread, [ThreadMembers.resolve] the members (an archived kid's devices leave), then [ThreadKeyPolicy.decide] against
 * every `thread_key` document this device has (the latest epoch may have one per creator, D-M4-3):
 * - **first key** or **removal**: a fresh key for the next epoch ([dev.kpetriv.sprout.core.crypto.chat.ThreadKeyring
 *   .newKey]), wrapped for the current members only;
 * - **addition**: the latest epoch's key this device sends with (its own, or another creator's it holds), wrapped
 *   again for all members under this device's own document (same epoch);
 * - otherwise nothing.
 *
 * [plan] only builds the documents; [invoke] first settles this device's unconfirmed documents
 * ([ThreadKeyWrites.settle]), then plans and publishes, all under one lock per database (review H2: two runs, the
 * start-up one and an after-sync one, never plan from the same state and publish two keys for one epoch), planning
 * again inside the lock. The app runs [invoke] after each successful sync of a parent session. A thread whose decision
 * cannot be carried out (this device's registered encryption key is not the one it holds, more members than a
 * document can wrap, any unexpected failure) is skipped until the next run; the others still go ahead (audit L4).
 */
class ThreadKeyMaintenance @Inject constructor(
    private val database: SproutDatabase,
    private val sessions: SessionStore,
    private val crypto: ChatCrypto,
    private val check: SignatureCheck,
    private val writes: ThreadKeyWrites,
) {
    private val mutex: Mutex = synchronized(MUTEXES) { MUTEXES.getOrPut(database) { Mutex() } }

    /**
     * The documents [self] (holding [privateKey]) should publish now, at most one per thread. Empty without a PARENT
     * session of a real household.
     */
    suspend fun plan(self: DeviceId, privateKey: X25519PrivateKey): List<ThreadKeyDocument> {
        val session = sessions.get()
        if (session == null || session.role != Role.PARENT || session.isLocalTrial) return emptyList()
        val devices = RoomDeviceRegistry(database).devicesOf(session.householdId)
        return database.chatHouseholdDao().kids().mapNotNull { kid ->
            planThread(kid, session.householdId, self, privateKey, devices)
        }
    }

    /**
     * Settles, then [plan]s with this device's key pair and publishes every document, under the maintenance lock;
     * returns how many were published.
     */
    suspend operator fun invoke(): Int = mutex.withLock {
        val session = sessions.get()
        if (session == null || session.role != Role.PARENT || session.isLocalTrial) return@withLock 0
        writes.settle()
        val documents = crypto.keyPairOrNull()?.use { plan(session.deviceId, it.privateKey) }.orEmpty()
        documents.forEach { writes.publish(it) }
        documents.size
    }

    /** One thread's document, or null; every failure is this thread's alone (audit L4). */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // One broken thread must not stop the others.
    private suspend fun planThread(
        kid: KidEntity,
        householdId: HouseholdId,
        self: DeviceId,
        privateKey: X25519PrivateKey,
        devices: List<RegisteredDevice>,
    ): ThreadKeyDocument? = try {
        val threadId = ThreadId(kid.kidId.value)
        val rows = ThreadKeyStore(database).rows(threadId)
        // An archived kid without a key has nothing to rotate away from.
        if (kid.archived && rows.isEmpty()) {
            null
        } else {
            val membership = ThreadMembers.resolve(threadId, devices, check, kid.archived)
            val documents = ThreadKeyStore.documents(rows).map { it.second }
            val latest = ThreadKeyPolicy.latestEpoch(documents)
            val held = rows.filter { it.epoch == latest && it.key != null }
            val decision = ThreadKeyPolicy.decide(
                membership,
                self,
                documents,
                held.mapTo(HashSet()) {
                    it.createdByDeviceId
                },
            )
            carryOut(decision, threadId, held, Signer(householdId, self, privateKey))
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Exception) {
        null
    }

    /** Who signs a planned document's wraps. */
    private class Signer(val householdId: HouseholdId, val self: DeviceId, val privateKey: X25519PrivateKey)

    /** The document [decision] asks for; [held] are the latest epoch's rows whose key this device holds. */
    private fun carryOut(
        decision: ThreadKeyDecision,
        threadId: ThreadId,
        held: List<ThreadKeyEntity>,
        signer: Signer,
    ): ThreadKeyDocument? = when (decision) {
        ThreadKeyDecision.NoAction -> null

        is ThreadKeyDecision.ReWrapCurrent -> {
            val source = held.first { it.createdByDeviceId == decision.fromCreator }
            ThreadKey.fromBytes(threadId, decision.epoch, requireNotNull(source.key))
                .use { wrap(signer.householdId, it, signer.self, signer.privateKey, decision.members) }
        }

        is ThreadKeyDecision.Rotate -> crypto.keyring.newKey(threadId, decision.newEpoch)
            .use { wrap(signer.householdId, it, signer.self, signer.privateKey, decision.members) }
    }

    /** The document wrapping [key] for [members], or null when it cannot be built (see the class documentation). */
    private fun wrap(
        householdId: HouseholdId,
        key: ThreadKey,
        self: DeviceId,
        privateKey: X25519PrivateKey,
        members: List<ThreadMember>,
    ): ThreadKeyDocument? = try {
        crypto.keyring.wrapFor(householdId, key, self, privateKey, members)
    } catch (expected: IllegalArgumentException) {
        null
    } catch (expected: KeyAgreementException) {
        null
    }

    private companion object {
        /** One maintenance lock per database, process-wide (like the device clock's). */
        val MUTEXES = WeakHashMap<SproutDatabase, Mutex>()
    }
}
