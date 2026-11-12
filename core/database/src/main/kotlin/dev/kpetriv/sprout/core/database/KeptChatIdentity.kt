package dev.kpetriv.sprout.core.database

import androidx.room.withTransaction
import dev.kpetriv.sprout.core.crypto.EncryptionKeyStore
import dev.kpetriv.sprout.core.crypto.SecretStore
import dev.kpetriv.sprout.core.crypto.SecretUnavailableException
import dev.kpetriv.sprout.core.database.entity.SyncCursorEntity
import dev.kpetriv.sprout.core.domainledger.LedgerJson
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.model.ThreadId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.IOException
import javax.inject.Inject

/**
 * This device's own chat counters in one thread when it signed out (D-REL-30): the `seq` its next message there takes
 * and the lamport of its last one. Receivers expect the `seq` to go on and the lamport to rise, so a restored phone
 * must never reuse values it already sent, even when the server no longer has those messages (deleted chat history,
 * a retention prune) and the pull cannot rebuild them. [lastLamport] is a floor for the device clock next to
 * [KeptChatIdentity.deviceLamport] ([ChatIdentityKeeper.seed] takes the higher of them).
 */
data class OwnThreadMark(val threadId: ThreadId, val nextSeq: Long, val lastLamport: Long)

/**
 * What a sign-out keeps with this phone's chat X25519 key (`chat.x25519.v1`), as one wrapped `SecretStore` record
 * ([ChatIdentityKeeper.RECORD], D-REL-30, D-REL-33): the household and the device id the key is registered for, the
 * device's Lamport clock and its [OwnThreadMark]s. [householdId] is null only for a stash tag written for a key that
 * was never kept by a sign-out (a phone that registers again without signing out).
 */
data class KeptChatIdentity(
    val householdId: HouseholdId?,
    val deviceId: DeviceId,
    val deviceLamport: Long = 0,
    val marks: List<OwnThreadMark> = emptyList(),
) {
    internal fun encode(): ByteArray = LedgerJson.json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put(VERSION, CURRENT_VERSION)
            householdId?.let { put(HOUSEHOLD, it.value) }
            put(DEVICE, deviceId.value)
            put(LAMPORT, deviceLamport)
            put(
                MARKS,
                buildJsonArray {
                    marks.forEach { mark ->
                        add(
                            buildJsonObject {
                                put(THREAD, mark.threadId.value)
                                put(NEXT_SEQ, mark.nextSeq)
                                put(LAST_LAMPORT, mark.lastLamport)
                            },
                        )
                    }
                },
            )
        },
    ).encodeToByteArray()

    internal companion object {
        private const val VERSION = "v"
        private const val CURRENT_VERSION = 1
        private const val HOUSEHOLD = "householdId"
        private const val DEVICE = "deviceId"
        private const val LAMPORT = "deviceLamport"
        private const val MARKS = "marks"
        private const val THREAD = "threadId"
        private const val NEXT_SEQ = "nextSeq"
        private const val LAST_LAMPORT = "lastLamport"

        /** The record [bytes] decoded, or null when they are not one (a stale or foreign format is never trusted). */
        @Suppress("SwallowedException", "ReturnCount") // A malformed record is no record.
        fun decodeOrNull(bytes: ByteArray): KeptChatIdentity? {
            val tree = try {
                LedgerJson.json.parseToJsonElement(bytes.decodeToString()) as? JsonObject
            } catch (malformed: SerializationException) {
                null
            } ?: return null
            if (tree.long(VERSION) != CURRENT_VERSION.toLong()) return null
            val device = tree.string(DEVICE)?.let(::DeviceId) ?: return null
            val marks = (tree[MARKS] as? JsonArray)?.map { element ->
                val mark = element as? JsonObject ?: return null
                OwnThreadMark(
                    ThreadId(mark.string(THREAD) ?: return null),
                    mark.long(NEXT_SEQ)?.takeIf { it >= 1 } ?: return null,
                    mark.long(LAST_LAMPORT)?.takeIf { it >= 0 } ?: return null,
                )
            } ?: return null
            return KeptChatIdentity(
                tree.string(HOUSEHOLD)?.let(::HouseholdId),
                device,
                tree.long(LAMPORT)?.takeIf { it >= 0 } ?: return null,
                marks,
            )
        }

        private fun JsonObject.string(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

        private fun JsonObject.long(key: String): Long? =
            (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
    }
}

/**
 * The lifecycle of this phone's chat X25519 key across a sign-out (ADR-0008 §1, D-REL-6, D-REL-7, D-REL-29 to
 * D-REL-33, D-REL-35 to D-REL-38), every step through the wrapped [SecretStore]:
 * - [keepForSignOut]: a sign-out wipe of a PARENT session writes [RECORD] (household, device id, Lamport clock and
 *   this device's [OwnThreadMark]s) before the database goes; the wipe then leaves the key and the record in place.
 * - [seed]: before the same parent's session for the same device id is saved, the record's counters are put into the
 *   new database (the device clock, and a per-thread `seq` floor that [MessageWrites] honours); [discardRecord] deletes
 *   the record once the session is saved, and [resumeSeed] does both again at a start that finds a session and a record
 *   of its own device (a process death in between).
 * - [stash]: before this phone registers as a new device, the live key is moved to [STASH_KEY], tagged
 *   ([STASH_RECORD]) with the device id it belongs to. Under its stash name the key does not open (its wrapping stays
 *   bound to `chat.x25519.v1`), so no new registration ever starts while the old key is live and nothing else can use
 *   it.
 * - [dropStash]: a registration the server accepted (the new key was promoted) deletes the stash for good.
 * - [unstash]: restoring the stash's own device id moves the stash back over whatever key a failed registration made.
 * - [forget], [forgetIf], [dropStashIf]: a kept key or stash whose device is gone from the household (D-REL-33) is
 *   deleted, the `If` forms only while it is still the one that was checked.
 *
 * Every step that changes these names holds one lock for the whole process ([LOCK]; the keeper itself is not a
 * singleton), so a slow start-up check never deletes a key that onboarding moved or made meanwhile (D-REL-35).
 *
 * Crash safety (D-REL-36): the stash exists exactly when [STASH_KEY] is stored. Its tag is written first (a fresh
 * record bound to [STASH_RECORD]), then the key moves, then [RECORD] goes; [unstash] writes [RECORD] from the tag
 * first, then moves the key back (the move replaces any live key), then deletes the tag. A tag without its key is
 * no stash and is deleted wherever it is found; a stashed key without a readable tag can never be identified, so it
 * is never restored and is deleted too. A process death between any two steps therefore leaves either the live key or a
 * complete stash, and running the same step again finishes it.
 */
@Suppress("TooManyFunctions") // One function per step of the key's lifecycle, each under the same lock.
class ChatIdentityKeeper @Inject constructor(private val database: SproutDatabase, private val secrets: SecretStore) {
    /** The record a sign-out kept, or null (none, or unreadable). */
    suspend fun kept(): KeptChatIdentity? = read(RECORD)

    /** The stash's tag, or null when there is no stash or its tag cannot be read ([hasStash] tells them apart). */
    suspend fun stashed(): KeptChatIdentity? = if (secrets.contains(STASH_KEY)) read(STASH_RECORD) else null

    /** True when a stashed key is stored, tagged or not. */
    suspend fun hasStash(): Boolean = secrets.contains(STASH_KEY)

    /** True when a chat key is stored under its live name. */
    suspend fun hasKey(): Boolean = secrets.contains(KEY)

    /**
     * Writes [RECORD] for the session about to be wiped by a sign-out: true when it was written, false when there is
     * nothing to keep (no session, a KID or trial session, no key). Throws when it cannot be written; the caller then
     * erases the key too.
     */
    suspend fun keepForSignOut(): Boolean {
        val session = database.sessionDao().get()?.toDomain()
            ?.takeIf { !it.isLocalTrial && it.role == Role.PARENT && hasKey() }
            ?: return false
        val identity = database.withTransaction {
            val seqs = database.messageDao().allSeqs().filter { it.authorDeviceId == session.deviceId }
            val floors = database.ownSeqFloors(session.householdId)
            val threads = (seqs.map { it.threadId } + floors.keys).toSortedSet()
            val marks = threads.map { thread ->
                val seq = seqs.firstOrNull { it.threadId == thread }
                OwnThreadMark(
                    ThreadId(thread),
                    maxOf(seq?.nextSeq ?: 1, floors[thread] ?: 1),
                    seq?.lastLamport ?: 0,
                )
            }
            KeptChatIdentity(session.householdId, session.deviceId, DeviceClock.of(database).current(), marks)
        }
        LOCK.withLock { secrets.writeRecord(RECORD, identity.encode()) }
        return true
    }

    /**
     * Puts the kept counters into this database for [session] when the record is a sign-out's of the same device of
     * the same household and [session] is that parent's: the device clock moves up to the kept one (and to every
     * thread's last lamport), and each thread gets a `seq` floor ([ownSeqFloor]). Idempotent; the record stays
     * ([discardRecord] deletes it once the session is saved). True when it seeded; anything else is refused.
     */
    suspend fun seed(session: Session): Boolean = LOCK.withLock { seedLocked(session) }

    /** Deletes [RECORD] (the session that now owns this phone is saved; a record of another device has no use). */
    suspend fun discardRecord() {
        LOCK.withLock { secrets.delete(RECORD) }
    }

    /**
     * At a start with [session]: a record of its own device means a process death between saving the session and
     * deleting the record, so [seed] runs again and the record goes; true when it did. Any other record is left alone.
     */
    suspend fun resumeSeed(session: Session): Boolean = LOCK.withLock {
        seedLocked(session).also { seeded -> if (seeded) secrets.delete(RECORD) }
    }

    /**
     * Moves the live key away before this phone registers as a new device ([currentDeviceId] is the device the key
     * belongs to, the current signing key's). A stash of [currentDeviceId] already there is kept and the live key
     * (one a failed registration made) deleted; any other stash is stale or unidentifiable and goes. Throws
     * [IOException] when a key is still stored under the live name afterwards: nothing may be registered then.
     */
    suspend fun stash(currentDeviceId: DeviceId) {
        LOCK.withLock {
            val own = secrets.contains(STASH_KEY) && read(STASH_RECORD)?.deviceId == currentDeviceId
            if (own) {
                // A crash after the key moved (or an earlier attempt) left the stash complete: finish it.
                secrets.delete(KEY)
            } else {
                dropStashLocked()
                if (secrets.contains(KEY)) {
                    val live = read(RECORD)?.takeIf { it.deviceId == currentDeviceId }
                        ?: KeptChatIdentity(null, currentDeviceId)
                    secrets.writeRecord(STASH_RECORD, live.encode())
                    secrets.rename(KEY, STASH_KEY)
                }
            }
            secrets.delete(RECORD)
            if (secrets.contains(KEY)) throw IOException("The chat key could not be moved away")
        }
    }

    /**
     * Moves the stash back when it belongs to [deviceId] (the device being restored), replacing any live key; true
     * when it did. A stash of another device, or one without a readable tag, is deleted.
     */
    suspend fun unstash(deviceId: DeviceId): Boolean = LOCK.withLock {
        if (!secrets.contains(STASH_KEY)) {
            // No stash; a tag left by a cut-short unstash is no use.
            secrets.delete(STASH_RECORD)
            return@withLock false
        }
        val tag = read(STASH_RECORD)
        if (tag == null || tag.deviceId != deviceId) {
            dropStashLocked()
            return@withLock false
        }
        // A tag without a household was never a sign-out's record: it has nothing to seed.
        if (tag.householdId != null) secrets.writeRecord(RECORD, tag.encode()) else secrets.delete(RECORD)
        secrets.rename(STASH_KEY, KEY)
        secrets.delete(STASH_RECORD)
        true
    }

    /** Deletes the stash (its key, then its tag). */
    suspend fun dropStash() {
        LOCK.withLock { dropStashLocked() }
    }

    /** Deletes the live key and its record. */
    suspend fun forget() {
        LOCK.withLock { forgetLocked() }
    }

    /**
     * [forget], only while [RECORD] still holds [checked]: a key stashed, restored or replaced since it was checked is
     * left alone (D-REL-35). True when it deleted.
     */
    suspend fun forgetIf(checked: KeptChatIdentity): Boolean = LOCK.withLock {
        (read(RECORD) == checked).also { same -> if (same) forgetLocked() }
    }

    /**
     * [dropStash], only while the stash is still the one checked: tagged [checked], or untagged when [checked] is null.
     * True when it deleted.
     */
    suspend fun dropStashIf(checked: KeptChatIdentity?): Boolean = LOCK.withLock {
        (secrets.contains(STASH_KEY) && read(STASH_RECORD) == checked).also { same -> if (same) dropStashLocked() }
    }

    private suspend fun seedLocked(session: Session): Boolean {
        val identity = kept()?.takeIf { it.isSignOutOf(session) } ?: return false
        val lamport = maxOf(identity.deviceLamport, identity.marks.maxOfOrNull { it.lastLamport } ?: 0)
        DeviceClock.of(database).withLock {
            database.withTransaction {
                database.advanceDeviceLamport(session.deviceId, lamport)
                database.syncCursorDao().upsert(
                    identity.marks.map {
                        SyncCursorEntity(session.householdId, floorKey(it.threadId), "${it.nextSeq}")
                    },
                )
            }
        }
        return true
    }

    private fun KeptChatIdentity.isSignOutOf(session: Session): Boolean = !session.isLocalTrial &&
        session.role == Role.PARENT &&
        deviceId == session.deviceId &&
        householdId == session.householdId

    private suspend fun forgetLocked() {
        secrets.delete(KEY)
        secrets.delete(RECORD)
    }

    private suspend fun dropStashLocked() {
        secrets.delete(STASH_KEY)
        secrets.delete(STASH_RECORD)
    }

    @Suppress("SwallowedException") // An unreadable record is no record; it is replaced or deleted later.
    private suspend fun read(name: String): KeptChatIdentity? = try {
        secrets.readRecord(name)?.let(KeptChatIdentity::decodeOrNull)
    } catch (unreadable: SecretUnavailableException) {
        null
    }

    companion object {
        /** The chat X25519 private key (`EncryptionKeyStore.SECRET_NAME`). */
        const val KEY: String = EncryptionKeyStore.SECRET_NAME

        /** The [KeptChatIdentity] record kept with [KEY]. */
        const val RECORD: String = "chat.identity.v1"

        /** [KEY] moved away before a new registration, still wrapped for its own name. */
        const val STASH_KEY: String = "chat.x25519.v1.stash"

        /** The stash's tag: a [KeptChatIdentity] written fresh under this name, naming the device [STASH_KEY] is of. */
        const val STASH_RECORD: String = "chat.identity.v1.stash"

        /** Every `SecretStore` name of the chat key's lifecycle. */
        val NAMES: List<String> = listOf(KEY, RECORD, STASH_KEY, STASH_RECORD)

        /** One lock for every keeper in the process: they all share the same names in the same store. */
        private val LOCK = Mutex()

        /** The `sync_cursor` key of this device's `seq` floor in [threadId] (no collection starts with `#`). */
        internal fun floorKey(threadId: ThreadId): String = "$FLOOR_PREFIX${threadId.value}"

        internal const val FLOOR_PREFIX: String = "#ownSeqFloor/"
    }
}

/**
 * The `seq` this device's next message in [threadId] takes at the least, seeded from a sign-out's record after a
 * restore ([ChatIdentityKeeper.seed]); null when none. Kept device-local in `sync_cursor` under a key no collection
 * has, like `ChatApplier.CHAT_PRUNED_BEFORE`, so no schema change.
 */
internal suspend fun SproutDatabase.ownSeqFloor(householdId: HouseholdId, threadId: ThreadId): Long? =
    ownSeqFloors(householdId)[threadId.value]

/** Every floor of [ownSeqFloor] in [householdId], by thread. */
internal suspend fun SproutDatabase.ownSeqFloors(householdId: HouseholdId): Map<String, Long> =
    syncCursorDao().all(householdId)
        .filter { it.collection.startsWith(ChatIdentityKeeper.FLOOR_PREFIX) }
        .mapNotNull { row ->
            row.cursor.toLongOrNull()?.let { row.collection.removePrefix(ChatIdentityKeeper.FLOOR_PREFIX) to it }
        }
        .toMap()
