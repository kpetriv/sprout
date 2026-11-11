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
 * This device's own chat counters in one thread when it signed out (D-REL-25): the `seq` its next message there takes
 * and the lamport of its last one. Receivers expect the `seq` to go on and the lamport to rise, so a restored phone
 * must never reuse values it already sent, even when the server no longer has those messages (deleted chat history,
 * a retention prune) and the pull cannot rebuild them.
 */
data class OwnThreadMark(val threadId: ThreadId, val nextSeq: Long, val lastLamport: Long)

/**
 * What a sign-out keeps with this phone's chat X25519 key (`chat.x25519.v1`), as one wrapped `SecretStore` record
 * ([ChatIdentityKeeper.RECORD], D-REL-25, D-REL-28): the household and the device id the key is registered for, the
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
 * The lifecycle of this phone's chat X25519 key across a sign-out (ADR-0008 §1, D-REL-6, D-REL-7, D-REL-24 to
 * D-REL-28), every step through the wrapped [SecretStore] and under its lock:
 * - [keepForSignOut]: a sign-out wipe of a PARENT session writes [RECORD] (household, device id, Lamport clock and
 *   this device's [OwnThreadMark]s) before the database goes; the wipe then leaves the key and the record in place.
 * - [seed]: once the same parent restores the same device id, the record's counters are put back into the new
 *   database (the device clock, and a per-thread `seq` floor that [MessageWrites] honours), and the record goes.
 * - [stash]: before this phone registers as a new device, the live key is moved to [STASH_KEY] with [STASH_RECORD],
 *   tagged with the device id it belongs to. Under its stash name the key does not open (its wrapping stays bound to
 *   `chat.x25519.v1`), so no new registration ever starts while the old key is live and nothing else can use it.
 * - [dropStash]: a registration the server accepted (the new key was promoted) deletes the stash for good.
 * - [unstash]: restoring the stash's own device id moves the stash back over whatever key a failed registration made.
 * - [forget]: a kept key whose device is gone from the household (D-REL-28) is deleted with its record.
 */
class ChatIdentityKeeper @Inject constructor(private val database: SproutDatabase, private val secrets: SecretStore) {
    /** The record a sign-out kept, or null (none, or unreadable). */
    suspend fun kept(): KeptChatIdentity? = read(RECORD, RECORD)

    /** The stash's tag, or null when there is no readable stash. */
    suspend fun stashed(): KeptChatIdentity? = read(STASH_RECORD, RECORD)

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
        secrets.writeRecord(RECORD, identity.encode())
        return true
    }

    /**
     * Puts the kept counters back for [session], the same device of the same household restored by its parent: the
     * device clock moves up to the kept one and each thread gets a `seq` floor ([ownSeqFloor]). The record is
     * deleted afterwards whatever it held (a real session now owns this phone). No record, nothing to do.
     */
    suspend fun seed(session: Session) {
        if (session.isLocalTrial) return
        val identity = kept()
        val sameDevice = identity?.let { it.deviceId == session.deviceId && it.householdId == session.householdId }
        if (identity != null && sameDevice == true && session.role == Role.PARENT) {
            DeviceClock.of(database).withLock {
                database.withTransaction {
                    database.advanceDeviceLamport(session.deviceId, identity.deviceLamport)
                    database.syncCursorDao().upsert(
                        identity.marks.map {
                            SyncCursorEntity(session.householdId, floorKey(it.threadId), "${it.nextSeq}")
                        },
                    )
                }
            }
        }
        secrets.delete(RECORD)
    }

    /**
     * Moves the live key away before this phone registers as a new device ([currentDeviceId] is the device the key
     * belongs to, the current signing key's). A stash of [currentDeviceId] already there is kept and the live key
     * (one a failed registration made) deleted; a stash of another device is stale and goes. Throws [IOException]
     * when a key is still stored under the live name afterwards: nothing may be registered then.
     */
    suspend fun stash(currentDeviceId: DeviceId) {
        val stashed = stashed()
        if (stashed != null && stashed.deviceId == currentDeviceId) {
            secrets.delete(KEY)
            secrets.delete(RECORD)
        } else {
            dropStash()
            val live = kept()?.takeIf { it.deviceId == currentDeviceId } ?: KeptChatIdentity(null, currentDeviceId)
            secrets.writeRecord(RECORD, live.encode())
            if (secrets.rename(KEY, STASH_KEY)) secrets.rename(RECORD, STASH_RECORD) else secrets.delete(RECORD)
        }
        if (secrets.contains(KEY)) throw IOException("The chat key could not be moved away")
    }

    /**
     * Moves the stash back when it belongs to [deviceId] (the device being restored), replacing any live key; true
     * when it did. A stash of another device is stale and deleted.
     */
    suspend fun unstash(deviceId: DeviceId): Boolean {
        val stashed = stashed()
        val own = stashed != null && stashed.deviceId == deviceId
        if (own) {
            secrets.delete(KEY)
            secrets.rename(STASH_KEY, KEY)
            secrets.rename(STASH_RECORD, RECORD)
            // A tag without a household was never a sign-out's record: it has nothing to seed.
            if (stashed?.householdId == null) secrets.delete(RECORD)
        } else if (stashed != null) {
            dropStash()
        }
        return own
    }

    /** Deletes the stash (its key and its tag). */
    suspend fun dropStash() {
        secrets.delete(STASH_KEY)
        secrets.delete(STASH_RECORD)
    }

    /** Deletes the live key and its record. */
    suspend fun forget() {
        secrets.delete(KEY)
        secrets.delete(RECORD)
    }

    @Suppress("SwallowedException") // An unreadable record is no record; it is replaced or deleted later.
    private suspend fun read(name: String, boundTo: String): KeptChatIdentity? = try {
        secrets.readRecord(name, boundTo)?.let(KeptChatIdentity::decodeOrNull)
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

        /** [RECORD] moved away with [STASH_KEY]: the device id the stash belongs to. */
        const val STASH_RECORD: String = "chat.identity.v1.stash"

        /** Every `SecretStore` name of the chat key's lifecycle. */
        val NAMES: List<String> = listOf(KEY, RECORD, STASH_KEY, STASH_RECORD)

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
