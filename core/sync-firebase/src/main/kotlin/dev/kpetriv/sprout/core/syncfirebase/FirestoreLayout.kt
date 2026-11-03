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
 *   kids/{docId}, accounts/…   one collection per document type (see [collectionOf])
 * ```
 */
object FirestoreLayout {
    const val HOUSEHOLDS: String = "households"
    const val DEVICES: String = "devices"
    const val PARENT_KEYS: String = "parentKeys"
    const val EVENTS: String = "events"

    /** Separator between `eventId` and `hash` in the id of a conflicting event copy. */
    const val CONFLICT_SEPARATOR: String = "__"

    private val collectionByDocType: Map<String, String> = mapOf(
        DocTypes.KID to "kids",
        DocTypes.ACCOUNT to "accounts",
        DocTypes.HOUSEHOLD_POLICY to "householdPolicy",
        DocTypes.PARENT_KEY to PARENT_KEYS,
        DocTypes.CHORE to "chores",
        DocTypes.CHORE_SUBMISSION to "choreSubmissions",
        DocTypes.REWARD to "rewards",
        DocTypes.ALLOWANCE_SCHEDULE to "allowanceSchedules",
        DocTypes.SAVINGS_GOAL to "savingsGoals",
        DocTypes.INTEREST_POLICY to "interestPolicy",
    )

    init {
        check(collectionByDocType.keys == DocTypes.all.toSet()) { "every document type needs a collection" }
    }

    /** The collection of [docType], or null for an unknown type. */
    fun collectionOf(docType: String): String? = collectionByDocType[docType]

    /**
     * The plain document collections (every type except [DocTypes.PARENT_KEY]: parent keys are registry entries in
     * [PARENT_KEYS], never synced as documents) in [DocTypes.applyOrder], paired with their type.
     */
    val documentCollections: List<Pair<String, String>> = DocTypes.all
        .filter { it != DocTypes.PARENT_KEY }
        .sortedBy(DocTypes::applyOrder)
        .map { docType -> docType to collectionByDocType.getValue(docType) }

    fun household(hid: HouseholdId): String = "$HOUSEHOLDS/${hid.value}"

    fun collection(hid: HouseholdId, collection: String): String = "${household(hid)}/$collection"

    fun document(hid: HouseholdId, collection: String, id: String): String = "${collection(hid, collection)}/$id"

    fun event(hid: HouseholdId, eventId: String): String = document(hid, EVENTS, eventId)

    fun conflictingEvent(hid: HouseholdId, eventId: String, hash: String): String =
        document(hid, EVENTS, "$eventId$CONFLICT_SEPARATOR$hash")
}
