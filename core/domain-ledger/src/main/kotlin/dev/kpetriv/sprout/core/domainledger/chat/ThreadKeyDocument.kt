package dev.kpetriv.sprout.core.domainledger.chat

import dev.kpetriv.sprout.core.domainledger.CanonicalEncoder
import dev.kpetriv.sprout.core.domainledger.Hasher
import dev.kpetriv.sprout.core.domainledger.JvmSha256Hasher
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.ID_PATTERN
import dev.kpetriv.sprout.core.model.ThreadId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64

/**
 * The payload of a `thread_key` document (M4 §1.2, D-M4-3): the thread key of ([threadId], [epoch]) wrapped once per
 * member device by [createdByDeviceId], a PARENT device, which also wraps for itself. Document id: [docId],
 * `"<kidId>.<epoch>.<createdByDeviceId>"` (D-M4-3 as amended in M4 fix pass 1: one document per creator, so two
 * parents rotating at once publish two documents, and two keys, for one epoch instead of a last-writer-wins loser).
 * A creator re-wraps its own key for a new member under the same id (a superset of recipients); the rules let only
 * the creator rewrite its document and never accept a tombstone.
 *
 * Payload JSON (closed key sets at both levels):
 * `{"threadId": "...", "epoch": n, "createdByDeviceId": "...", "wraps": {"<deviceId>": {"wrapped": "<base64>"}}}`.
 * `wrapped` is the AEAD output described in [ThreadKeyWrapFormat], standard base64 with padding.
 *
 * Receivers must also check that the document envelope's author device is [createdByDeviceId] and that the document
 * id matches ([matchesDocId]); a wrap only unwraps with the creator's registered encryption key anyway.
 */
data class ThreadKeyDocument(
    val threadId: ThreadId,
    val epoch: Long,
    val createdByDeviceId: DeviceId,
    val wraps: Map<DeviceId, String>,
) {
    init {
        require(epoch >= 0) { "epoch must not be negative" }
        require(wraps.size in 1..ChatLimits.MAX_THREAD_KEY_WRAPS) {
            "a thread key document wraps for 1 to ${ChatLimits.MAX_THREAD_KEY_WRAPS} devices"
        }
        require(createdByDeviceId in wraps) { "the creator wraps the key for itself too" }
        require(wraps.values.all(::isValidWrapped)) {
            "wrapped values are base64 of at most ${ChatLimits.MAX_WRAPPED_LENGTH} characters"
        }
        require(ID_PATTERN.matches(docId)) { "threadId, epoch and creator must form a valid document id" }
    }

    /** The document id, `"<threadId>.<epoch>.<createdByDeviceId>"` (the thread id is the kid id). */
    val docId: String get() = docIdFor(threadId, epoch, createdByDeviceId)

    /** The devices this document wraps the key for. */
    val recipients: Set<DeviceId> get() = wraps.keys

    /** The decoded wrap for [deviceId], or null when this document has none for it. */
    fun wrappedFor(deviceId: DeviceId): ByteArray? = wraps[deviceId]?.let(Base64.Default::decode)

    /** Whether [docId] is the id this payload must be stored under. */
    fun matchesDocId(docId: String): Boolean = docId == this.docId

    /** The payload JSON tree (put it in the document envelope's `payload`). */
    fun toPayload(): JsonObject = buildJsonObject {
        put(THREAD_ID, threadId.value)
        put(EPOCH, epoch)
        put(CREATED_BY, createdByDeviceId.value)
        put(
            WRAPS,
            buildJsonObject {
                wraps.toSortedMap(compareBy { it.value }).forEach { (device, wrapped) ->
                    put(device.value, buildJsonObject { put(WRAPPED, wrapped) })
                }
            },
        )
    }

    /** [CanonicalEncoder.encodeTree] of [toPayload], for golden tests and size budgets. */
    fun canonicalPayload(): ByteArray = CanonicalEncoder.encodeTree(toPayload())

    override fun toString(): String =
        "ThreadKeyDocument(threadId=$threadId, epoch=$epoch, createdByDeviceId=$createdByDeviceId, " +
            "recipients=${wraps.keys.sortedBy { it.value }})"

    companion object {
        private const val THREAD_ID = "threadId"
        private const val EPOCH = "epoch"
        private const val CREATED_BY = "createdByDeviceId"
        private const val WRAPS = "wraps"
        private const val WRAPPED = "wrapped"
        private val PAYLOAD_KEYS = setOf(THREAD_ID, EPOCH, CREATED_BY, WRAPS)

        /** `"<threadId>.<epoch>.<createdByDeviceId>"`, as the rules and `FakeBackend` require. */
        fun docIdFor(threadId: ThreadId, epoch: Long, createdByDeviceId: DeviceId): String =
            "${threadId.value}.$epoch.${createdByDeviceId.value}"

        /**
         * Parses a document payload; null when the key sets are not exactly the documented ones, a type is wrong, or
         * a value fails validation. Never throws.
         */
        fun fromPayload(payload: JsonObject): ThreadKeyDocument? = try {
            parse(payload)
        } catch (expected: IllegalArgumentException) {
            null
        }

        /** Throws [IllegalArgumentException] (caught by [fromPayload]) for anything but the documented shape. */
        private fun parse(payload: JsonObject): ThreadKeyDocument {
            require(payload.keys == PAYLOAD_KEYS) { "unexpected payload keys" }
            val epoch = (payload[EPOCH] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
            val wrapsTree = payload[WRAPS] as? JsonObject
            val wraps = requireNotNull(wrapsTree) { "wraps must be an object" }.entries.associate { (device, value) ->
                val wrap = (value as? JsonObject)?.takeIf { it.keys == setOf(WRAPPED) }
                DeviceId(device) to requireNotNull(wrap?.string(WRAPPED)) { "a wrap is {wrapped: string}" }
            }
            return ThreadKeyDocument(
                threadId = ThreadId(requireNotNull(payload.string(THREAD_ID)) { "threadId must be a string" }),
                epoch = requireNotNull(epoch) { "epoch must be an integer" },
                createdByDeviceId = DeviceId(requireNotNull(payload.string(CREATED_BY)) { "creator must be a string" }),
                wraps = wraps,
            )
        }

        private fun isValidWrapped(wrapped: String): Boolean = wrapped.length <= ChatLimits.MAX_WRAPPED_LENGTH &&
            try {
                Base64.Default.decode(wrapped).isNotEmpty()
            } catch (expected: IllegalArgumentException) {
                false
            }

        private fun JsonObject.string(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}

/**
 * How a thread key is wrapped for one recipient device (M4 §1.2). With `sender` the document's creator:
 *
 * - shared secret: `X25519(sender private, recipient public)` (the recipient computes `X25519(recipient private,
 *   sender public)`); static-static, so no forward secrecy (D-M4-4);
 * - `wrapKey = HouseholdKeyDerivation.deriveThreadKey(shared, salt = SHA-256(UTF-8(householdId)), info = [info])`,
 *   whose HKDF info is therefore `UTF-8("sprout/household-thread-key/v1") || 0x00 || [info]`;
 * - `wrapped = Aead.encrypt(wrapKey, threadKey(32 bytes), associatedData = [associatedData])`.
 *
 * Epochs are written as ASCII decimal; ids never contain 0x00, so the 0x00-separated layouts are unambiguous.
 */
object ThreadKeyWrapFormat {
    const val DOMAIN: String = "sprout/thread-key-wrap/v1"

    private const val SEPARATOR: Byte = 0

    /** HKDF salt: `SHA-256(UTF-8(householdId))`. */
    fun salt(householdId: HouseholdId, hasher: Hasher = JvmSha256Hasher): ByteArray =
        hasher.sha256(householdId.value.encodeToByteArray())

    /** HKDF info (before [HouseholdKeyDerivation]'s own label): `DOMAIN || 0x00 || threadId || 0x00 || epoch`. */
    fun info(threadId: ThreadId, epoch: Long): ByteArray = join(DOMAIN, threadId.value, epoch.toString())

    /**
     * AEAD associated data: `DOMAIN || 0x00 || householdId || 0x00 || threadId || 0x00 || epoch || 0x00 ||
     * senderDeviceId || 0x00 || recipientDeviceId`.
     */
    fun associatedData(
        householdId: HouseholdId,
        threadId: ThreadId,
        epoch: Long,
        sender: DeviceId,
        recipient: DeviceId,
    ): ByteArray = join(DOMAIN, householdId.value, threadId.value, epoch.toString(), sender.value, recipient.value)

    private fun join(vararg parts: String): ByteArray {
        val out = ArrayList<Byte>()
        parts.forEachIndexed { index, part ->
            if (index > 0) out += SEPARATOR
            out.addAll(part.encodeToByteArray().toList())
        }
        return out.toByteArray()
    }
}
