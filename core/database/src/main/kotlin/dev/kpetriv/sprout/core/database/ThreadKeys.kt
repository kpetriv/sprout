package dev.kpetriv.sprout.core.database

import dev.kpetriv.sprout.core.crypto.EncryptionKeyStore
import dev.kpetriv.sprout.core.crypto.X25519KeyPair
import dev.kpetriv.sprout.core.crypto.chat.BlobCipher
import dev.kpetriv.sprout.core.crypto.chat.MessageCipher
import dev.kpetriv.sprout.core.crypto.chat.ThreadKey
import dev.kpetriv.sprout.core.crypto.chat.ThreadKeyring
import dev.kpetriv.sprout.core.database.entity.ThreadKeyEntity
import dev.kpetriv.sprout.core.domainledger.LedgerJson
import dev.kpetriv.sprout.core.domainledger.RegisteredDevice
import dev.kpetriv.sprout.core.domainledger.SignatureCheck
import dev.kpetriv.sprout.core.domainledger.chat.ThreadKeyDocument
import dev.kpetriv.sprout.core.domainledger.chat.ThreadKeyPolicy
import dev.kpetriv.sprout.core.domainledger.chat.ThreadMembers
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.ThreadId
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.GeneralSecurityException
import javax.inject.Inject

/**
 * The chat crypto the data layer uses (core/crypto, M4 §1): wrapping and unwrapping thread keys, message and blob
 * AEAD, and this device's X25519 key pair (`chat.x25519.v1`).
 */
class ChatCrypto @Inject constructor(
    val keyring: ThreadKeyring,
    val messages: MessageCipher,
    val blobs: BlobCipher,
    val encryptionKeys: EncryptionKeyStore,
) {
    /**
     * This device's key pair, or null when it has none (never created, or its secret cannot be unwrapped). Never
     * creates one: a key that is not registered could not be unwrapped to anyway. The caller closes it.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // A broken key store means "no key", never a crash.
    suspend fun keyPairOrNull(): X25519KeyPair? = try {
        encryptionKeys.keyPair(createIfMissing = false)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (expected: GeneralSecurityException) {
        null
    } catch (expected: RuntimeException) {
        null
    }
}

/** No thread key this device may send with in [threadId] (M4 §1.2): "waiting for a parent device". */
class ThreadKeyUnavailableException(val threadId: ThreadId) :
    IllegalStateException("No usable thread key for thread $threadId")

/**
 * Loads this device's key pair once, on first use, for one batch (one transaction), and wipes it afterwards. Used
 * where unwrapping may or may not be needed, so batches without thread keys never touch the key store.
 */
internal class LazyKeyPair(private val crypto: ChatCrypto) : AutoCloseable {
    private var loaded = false
    private var pair: X25519KeyPair? = null

    suspend fun get(): X25519KeyPair? {
        if (!loaded) {
            loaded = true
            pair = crypto.keyPairOrNull()
        }
        return pair
    }

    override fun close() {
        pair?.close()
        pair = null
    }
}

/**
 * Reads of `thread_keys` (M4 §1.2). Callers close every [ThreadKey] they get. Inside or outside a transaction.
 */
internal class ThreadKeyStore(private val database: SproutDatabase) {
    private val keys = database.threadKeyDao()

    /** The documents of [threadId], one per (epoch, creator). */
    suspend fun rows(threadId: ThreadId): List<ThreadKeyEntity> = keys.forThread(threadId.value)

    /** Every key this device holds for ([threadId], [epoch]), older keys of a creator included, [preferred]'s first. */
    suspend fun keysFor(threadId: ThreadId, epoch: Long, preferred: DeviceId? = null): List<ThreadKey> = rows(threadId)
        .filter { it.epoch == epoch }
        .sortedByDescending { preferred != null && it.createdByDeviceId == preferred }
        .flatMap { it.allKeys() }
        .distinctBy { it.toList() }
        .map { ThreadKey.fromBytes(threadId, epoch, it) }

    companion object {
        /** The rows whose document still parses, with it. */
        fun documents(rows: List<ThreadKeyEntity>): List<Pair<ThreadKeyEntity, ThreadKeyDocument>> =
            rows.mapNotNull { row -> documentOf(row)?.let { row to it } }

        /**
         * The row whose key [self] sends with (M4 §1.2, review H2): among the latest epoch's rows whose key this device
         * holds, its own when the server has confirmed it, otherwise the newest (lamport, then creator id) of another
         * creator. Its own **unconfirmed** row is never used: a document the server refused would leave every
         * message under it unreadable. Null when there is none.
         */
        fun sendingRow(rows: List<ThreadKeyEntity>, self: DeviceId): ThreadKeyEntity? {
            val latest = ThreadKeyPolicy.latestEpoch(documents(rows).map { it.second }) ?: return null
            val usable = rows.filter {
                it.epoch == latest && it.key != null && (it.createdByDeviceId != self || it.confirmed)
            }
            return usable.firstOrNull { it.createdByDeviceId == self }
                ?: usable.maxWithOrNull(compareBy<ThreadKeyEntity>({ it.lamport }, { it.createdByDeviceId.value }))
        }

        /**
         * Whether [self] may send in a thread with these [rows] and this [membership]'s excluded devices
         * ([ThreadKeyPolicy.sendingEpoch] over every document, with [sendingRow] as the usable key); returns the row.
         */
        fun sendable(rows: List<ThreadKeyEntity>, self: DeviceId, excluded: Set<DeviceId>): ThreadKeyEntity? {
            val row = sendingRow(rows, self)
            val documents = documents(rows).map { it.second }
            return row?.takeIf { ThreadKeyPolicy.sendingEpoch(documents, holdsUsableKey = true, excluded) != null }
        }

        /** The `wraps` object of [document], as stored in [ThreadKeyEntity.wrapSetJson]. */
        fun wrapSetJson(document: ThreadKeyDocument): String =
            LedgerJson.json.encodeToString(JsonObject.serializer(), document.toPayload().getValue(WRAPS) as JsonObject)

        /** The document [row] was stored from, or null when it no longer parses. */
        fun documentOf(row: ThreadKeyEntity): ThreadKeyDocument? {
            val wraps = try {
                LedgerJson.json.parseToJsonElement(row.wrapSetJson) as? JsonObject
            } catch (expected: IllegalArgumentException) {
                null
            } ?: return null
            return ThreadKeyDocument.fromPayload(
                buildJsonObject {
                    put(THREAD_ID, row.threadId)
                    put(EPOCH, JsonPrimitive(row.epoch))
                    put(CREATED_BY, row.createdByDeviceId.value)
                    put(WRAPS, wraps)
                },
            )
        }

        private const val THREAD_ID = "threadId"
        private const val EPOCH = "epoch"
        private const val CREATED_BY = "createdByDeviceId"
        private const val WRAPS = "wraps"
    }
}

/**
 * The key [self] may send with in [threadId], or null when it must wait for a parent device: [ThreadKeyStore.sendable]
 * over the stored documents and the devices that must not hold the key. The caller closes the key.
 */
internal suspend fun ThreadKeyStore.sendingKey(
    threadId: ThreadId,
    self: DeviceId,
    devices: List<RegisteredDevice>,
    check: SignatureCheck,
    kidArchived: Boolean,
): ThreadKey? {
    val membership = ThreadMembers.resolve(threadId, devices, check, kidArchived)
    val row = ThreadKeyStore.sendable(rows(threadId), self, membership.excluded) ?: return null
    return ThreadKey.fromBytes(threadId, row.epoch, requireNotNull(row.key))
}

/** Whether the kid of [threadId] is archived (its KID devices then leave the thread). */
internal suspend fun SproutDatabase.isArchivedKid(threadId: ThreadId): Boolean =
    kidDao().get(KidId(threadId.value))?.archived == true

/**
 * [existing]'s older keys with its current key appended (oldest first): the `olderKeys` of a row whose creator replaced
 * the key of one epoch (review H2). Null when there is nothing to keep.
 */
internal fun olderKeysWith(existing: ThreadKeyEntity?): ByteArray? {
    val current = existing?.key ?: return existing?.olderKeys
    return (existing.olderKeys ?: ByteArray(0)) + current
}
