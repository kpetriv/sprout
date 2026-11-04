package dev.kpetriv.sprout.core.syncfirebase

import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.sync.DocTypes

/**
 * Where synced data lives in Firestore (M3 §5, `firestore/firestore.rules`):
 *
 * ```
 * households/{hid}
 *   devices/{deviceId}         registry, written by Cloud Functions
 *   parentKeys/{fingerprint}   registry, written by the server only (registerParentKey, D-M3-34)
 *   events/{eventId}           ledger events, create-only; a conflicting copy goes to events/{eventId}__{hash}
 *   kids/{docId}, accounts/…   one collection per document type (see [collectionOf]); thread keys in threadKeys
 *   messages/{messageId}       chat messages (M4 §1.3), create-only, KID members read their own thread only
 *   blobs/{blobId}.{chunk}     encrypted blob chunks (M4 §2), create-only, fetched by id, never pulled
 *   quotas/{yyyymmdd}          the household's blob chunk counter for one UTC day ({chunks, last}), audit H2
 * ```
 */
object FirestoreLayout {
    const val HOUSEHOLDS: String = "households"
    const val DEVICES: String = "devices"
    const val PARENT_KEYS: String = "parentKeys"
    const val EVENTS: String = "events"
    const val MESSAGES: String = "messages"
    const val BLOBS: String = "blobs"
    const val THREAD_KEYS: String = "threadKeys"
    const val QUOTAS: String = "quotas"

    /**
     * The chat prune watermark on the household root document (D-M7-53): a map the server's `pruneMessages` writes
     * (`prunedBefore`, `prunedAt`, `purgedAt` timestamps); members read it, nobody else writes it.
     */
    const val CHAT_PRUNE: String = "chatPrune"
    const val PRUNED_BEFORE: String = "prunedBefore"

    /** Separator between `eventId` and `hash` in the id of a conflicting event copy. */
    const val CONFLICT_SEPARATOR: String = "__"

    private val collectionByDocType: Map<String, String> = mapOf(
        DocTypes.KID to "kids",
        DocTypes.ACCOUNT to "accounts",
        DocTypes.HOUSEHOLD_POLICY to "householdPolicy",
        DocTypes.CHORE to "chores",
        DocTypes.CHORE_SUBMISSION to "choreSubmissions",
        DocTypes.REWARD to "rewards",
        DocTypes.ALLOWANCE_SCHEDULE to "allowanceSchedules",
        DocTypes.SAVINGS_GOAL to "savingsGoals",
        DocTypes.INTEREST_POLICY to "interestPolicy",
        DocTypes.THREAD_KEY to THREAD_KEYS,
    )

    init {
        check(collectionByDocType.keys == DocTypes.all.toSet()) { "every document type needs a collection" }
    }

    /** The collection of [docType], or null for an unknown type. */
    fun collectionOf(docType: String): String? = collectionByDocType[docType]

    /**
     * The document collections in [DocTypes.applyOrder], paired with their type. Parent keys are registry entries in
     * [PARENT_KEYS], written by the server and never synced as documents.
     */
    val documentCollections: List<Pair<String, String>> = DocTypes.all
        .sortedBy(DocTypes::applyOrder)
        .map { docType -> docType to collectionByDocType.getValue(docType) }

    fun household(hid: HouseholdId): String = "$HOUSEHOLDS/${hid.value}"

    fun collection(hid: HouseholdId, collection: String): String = "${household(hid)}/$collection"

    fun document(hid: HouseholdId, collection: String, id: String): String = "${collection(hid, collection)}/$id"

    fun event(hid: HouseholdId, eventId: String): String = document(hid, EVENTS, eventId)

    fun conflictingEvent(hid: HouseholdId, eventId: String, hash: String): String =
        document(hid, EVENTS, "$eventId$CONFLICT_SEPARATOR$hash")

    fun message(hid: HouseholdId, messageId: String): String = document(hid, MESSAGES, messageId)

    /** `quotas/{yyyymmdd}`: the blob chunk counter of one UTC day. */
    fun quota(hid: HouseholdId, day: String): String = document(hid, QUOTAS, day)

    /** `blobs/{blobId}.{chunk}`. */
    fun blobChunk(hid: HouseholdId, blobId: String, chunk: Int): String = document(hid, BLOBS, "$blobId.$chunk")
}
