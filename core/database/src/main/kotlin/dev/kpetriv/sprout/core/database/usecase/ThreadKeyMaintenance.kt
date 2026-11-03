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
import javax.inject.Inject

/**
 * Keeps every kid's thread key in line with the registry mirror (M4 §1.2, D-M4-7), on PARENT devices: for each kid
 * thread, [ThreadMembers.resolve] the members (an archived kid's devices leave), then [ThreadKeyPolicy.decide] against
 * the latest `thread_key` document this device has:
 * - **first key** or **removal**: a fresh key for the next epoch ([dev.kpetriv.sprout.core.crypto.chat.ThreadKeyring
 *   .newKey]), wrapped for the current members only;
 * - **addition**: the current epoch's key this device holds, wrapped again for all members (same epoch);
 * - otherwise nothing.
 *
 * [plan] only builds the documents; [invoke] also publishes them through [ThreadKeyWrites] (which stores the new keys).
 * The app runs [invoke] after each successful pull of a parent session. A thread whose decision cannot be carried out
 * (this device's registered encryption key is not the one it holds, more members than a document can wrap, a member
 * key that is a low-order point) is skipped until the registry changes.
 */
class ThreadKeyMaintenance @Inject constructor(
    private val database: SproutDatabase,
    private val sessions: SessionStore,
    private val crypto: ChatCrypto,
    private val check: SignatureCheck,
    private val writes: ThreadKeyWrites,
) {
    /**
     * The documents [self] (holding [privateKey]) should publish now, at most one per thread. Empty without a PARENT
     * session of a real household.
     */
    suspend fun plan(self: DeviceId, privateKey: X25519PrivateKey): List<ThreadKeyDocument> {
        val session = sessions.get()
        if (session == null || session.role != Role.PARENT || session.isLocalTrial) return emptyList()
        val devices = RoomDeviceRegistry(database).devicesOf(session.householdId)
        val keyStore = ThreadKeyStore(database)
        return database.chatHouseholdDao().kids().mapNotNull { kid ->
            val threadId = ThreadId(kid.kidId.value)
            val rows = keyStore.rows(threadId)
            // An archived kid without a key has nothing to rotate away from.
            if (kid.archived && rows.isEmpty()) return@mapNotNull null
            val membership = ThreadMembers.resolve(threadId, devices, check, kid.archived)
            val latestRow = ThreadKeyStore.latestOf(rows)
            val latest = latestRow?.let(ThreadKeyStore::documentOf)
            val heldKey = latestRow?.key?.takeIf { latest != null }
            when (val decision = ThreadKeyPolicy.decide(membership, self, latest, heldKey != null)) {
                ThreadKeyDecision.NoAction -> null

                is ThreadKeyDecision.ReWrapCurrent ->
                    ThreadKey.fromBytes(threadId, decision.epoch, requireNotNull(heldKey))
                        .use { wrap(session.householdId, it, self, privateKey, decision.members) }

                is ThreadKeyDecision.Rotate -> crypto.keyring.newKey(threadId, decision.newEpoch)
                    .use { wrap(session.householdId, it, self, privateKey, decision.members) }
            }
        }
    }

    /** [plan] with this device's key pair, then publishes every document; returns how many were published. */
    suspend operator fun invoke(): Int {
        val session = sessions.get()
        if (session == null || session.role != Role.PARENT || session.isLocalTrial) return 0
        val documents = crypto.keyPairOrNull()?.use { plan(session.deviceId, it.privateKey) }.orEmpty()
        documents.forEach { writes.publish(it) }
        return documents.size
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
}
