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

    /**
     * The latest document of the thread: the highest epoch, and among two creators of that epoch (a rotation race)
     * the higher lamport, then the higher creator id. Null without any.
     */
    suspend fun latest(threadId: ThreadId): ThreadKeyEntity? = latestOf(rows(threadId))

    /** The epochs whose key this device holds. */
    suspend fun heldEpochs(threadId: ThreadId): Set<Long> =
        rows(threadId).filter { it.key != null }.mapTo(HashSet()) { it.epoch }

    /** Every key this device holds for ([threadId], [epoch]), [preferred]'s first. */
    suspend fun keysFor(threadId: ThreadId, epoch: Long, preferred: ThreadKeyEntity? = null): List<ThreadKey> =
        rows(threadId)
            .filter { it.epoch == epoch && it.key != null }
            .sortedByDescending { preferred != null && it.createdByDeviceId == preferred.createdByDeviceId }
            .map { ThreadKey.fromBytes(threadId, epoch, requireNotNull(it.key)) }

    companion object {
        fun latestOf(rows: List<ThreadKeyEntity>): ThreadKeyEntity? = rows.maxWithOrNull(
            compareBy<ThreadKeyEntity>({ it.epoch }, { it.lamport }, { it.createdByDeviceId.value }),
        )

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
 * The epoch this device may send with in [threadId], and its key, or null when it must wait for a parent device
 * ([ThreadKeyPolicy.sendingEpoch] over the latest document, the epochs this device holds and the devices that must
 * not hold the key). The caller closes the key.
 */
@Suppress("ReturnCount") // One early return per missing ingredient.
internal suspend fun ThreadKeyStore.sendingKey(
    threadId: ThreadId,
    devices: List<RegisteredDevice>,
    check: SignatureCheck,
    kidArchived: Boolean,
): ThreadKey? {
    val rows = rows(threadId)
    val latestRow = ThreadKeyStore.latestOf(rows) ?: return null
    val latest = ThreadKeyStore.documentOf(latestRow) ?: return null
    val membership = ThreadMembers.resolve(threadId, devices, check, kidArchived)
    val held = rows.filter { it.key != null }.mapTo(HashSet()) { it.epoch }
    val epoch = ThreadKeyPolicy.sendingEpoch(latest, held, membership.excluded) ?: return null
    val candidates = keysFor(threadId, epoch, latestRow)
    candidates.drop(1).forEach { it.close() }
    return candidates.firstOrNull()
}

/** Whether the kid of [threadId] is archived (its KID devices then leave the thread). */
internal suspend fun SproutDatabase.isArchivedKid(threadId: ThreadId): Boolean =
    kidDao().get(KidId(threadId.value))?.archived == true
