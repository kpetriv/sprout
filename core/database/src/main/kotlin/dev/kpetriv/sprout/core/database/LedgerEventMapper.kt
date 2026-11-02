package dev.kpetriv.sprout.core.database

import dev.kpetriv.sprout.core.database.entity.EventOrigin
import dev.kpetriv.sprout.core.database.entity.LedgerEventEntity
import dev.kpetriv.sprout.core.domainledger.HashChain
import dev.kpetriv.sprout.core.domainledger.LedgerEvent
import dev.kpetriv.sprout.core.domainledger.LedgerJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Converts between [LedgerEvent] and its row. The row's `payloadJson` is the event in [LedgerJson] form and the
 * source of truth.
 *
 * Reading never throws: [verify] parses the stored document, decodes it with [LedgerJson.decodeOrNull], and checks
 * the stored hash against [HashChain.hashOf] of the stored JSON **tree** (ADR-0002 amendments: fields this client
 * does not know stay covered) and against the row's indexed columns. A row that fails is skipped by reads and
 * quarantined by `RoomLedgerRepository`'s maintenance step.
 */
internal object LedgerEventMapper {
    private const val TYPE_KEY = "type"
    private val chain = HashChain()

    /** Outcome of [verify]. */
    sealed interface Read {
        /** The stored document verified. [tree] is the parsed document, for signature checks over its own bytes. */
        data class Verified(val event: LedgerEvent, val tree: JsonObject) : Read

        /** The row cannot be trusted; [reason] is stored as its quarantine reason (no content, ids only). */
        data class Failed(val reason: String) : Read
    }

    fun toEntity(
        event: LedgerEvent,
        origin: EventOrigin,
        receivedAtEpochMs: Long,
        quarantineReason: String? = null,
    ): LedgerEventEntity {
        val tree = LedgerJson.encodeToJsonObject(event)
        val envelope = event.envelope
        return LedgerEventEntity(
            eventId = envelope.eventId,
            householdId = envelope.householdId,
            kidId = envelope.kidId,
            accountId = envelope.accountId,
            authorUid = envelope.authorUid,
            authorDeviceId = envelope.authorDeviceId,
            authorRole = envelope.authorRole,
            lamport = envelope.lamport,
            createdAtEpochMs = envelope.createdAt.toEpochMilliseconds(),
            type = tree.getValue(TYPE_KEY).jsonPrimitive.content,
            currency = event.currency,
            schemaVersion = envelope.schemaVersion,
            prevHash = envelope.prevHash,
            hash = envelope.hash,
            signature = envelope.signature,
            payloadJson = LedgerJson.json.encodeToString(JsonElement.serializer(), tree),
            receivedAtEpochMs = receivedAtEpochMs,
            origin = origin,
            quarantined = quarantineReason != null,
            quarantineReason = quarantineReason,
        )
    }

    /** The verified event, or null if the row is undecodable or its hash does not match. Never throws. */
    fun toDomain(entity: LedgerEventEntity): LedgerEvent? = (verify(entity) as? Read.Verified)?.event

    /** Decodes and hash-checks [entity]. Never throws. */
    fun verify(entity: LedgerEventEntity): Read {
        val tree = parseOrNull(entity.payloadJson)
        val event = tree?.let(LedgerJson::decodeOrNull)
        val computed = if (tree != null && event != null) hashOrNull(tree, event.envelope.prevHash) else null
        return when {
            tree == null || event == null || computed == null -> Read.Failed(UNDECODABLE)
            computed != event.envelope.hash -> Read.Failed(HASH_MISMATCH)
            !entity.matches(event) -> Read.Failed(COLUMN_MISMATCH)
            else -> Read.Verified(event, tree)
        }
    }

    private fun parseOrNull(text: String): JsonObject? = try {
        LedgerJson.json.parseToJsonElement(text) as? JsonObject
    } catch (expected: IllegalArgumentException) {
        // SerializationException (malformed JSON) is an IllegalArgumentException.
        null
    }

    private fun hashOrNull(tree: JsonObject, prevHash: String?): String? = try {
        chain.hashOf(tree, prevHash)
    } catch (expected: IllegalArgumentException) {
        null
    } catch (expected: CharacterCodingException) {
        null
    }

    /** The indexed columns agree with the payload, so queries on them see the same event the payload holds. */
    private fun LedgerEventEntity.matches(event: LedgerEvent): Boolean = with(event.envelope) {
        this@matches.eventId == eventId && this@matches.hash == hash && this@matches.authorDeviceId == authorDeviceId &&
            this@matches.kidId == kidId && this@matches.lamport == lamport
    }

    /** Quarantine reasons. Stored on the row; never include event content. */
    const val UNDECODABLE: String = "undecodable payload"
    const val HASH_MISMATCH: String = "hash mismatch"
    const val COLUMN_MISMATCH: String = "row columns do not match payload"
    const val SIGNATURE_INVALID: String = "signature invalid"
}
