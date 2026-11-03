package dev.kpetriv.sprout.core.sync

import dev.kpetriv.sprout.core.domainledger.RegisteredDevice
import dev.kpetriv.sprout.core.model.Role
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The synced document collections ([DocumentEnvelope.docType]) and who may write them (M3 §3). Every type here is one a
 * client writes; parent keys, devices and members are registry entries the server writes (D-M3-34) and reach devices
 * through the registry pull, never as documents, so there is no `parent_key` type.
 */
object DocTypes {
    const val KID: String = "kid"
    const val ACCOUNT: String = "account"
    const val HOUSEHOLD_POLICY: String = "household_policy"
    const val CHORE: String = "chore"
    const val CHORE_SUBMISSION: String = "chore_submission"
    const val REWARD: String = "reward"
    const val ALLOWANCE_SCHEDULE: String = "allowance_schedule"
    const val SAVINGS_GOAL: String = "savings_goal"
    const val INTEREST_POLICY: String = "interest_policy"

    /**
     * Every known type, in the order a pulled page is applied: the household's structure (kids, accounts, policy)
     * first, so documents and events that refer to it find it.
     */
    val all: List<String> = listOf(
        KID,
        ACCOUNT,
        HOUSEHOLD_POLICY,
        CHORE,
        CHORE_SUBMISSION,
        REWARD,
        ALLOWANCE_SCHEDULE,
        SAVINGS_GOAL,
        INTEREST_POLICY,
    )

    private val applyRank: Map<String, Int> = all.withIndex().associate { (index, type) -> type to index }

    fun isKnown(docType: String): Boolean = docType in applyRank

    /** Position of [docType] in [all]; unknown types sort last. */
    fun applyOrder(docType: String): Int = applyRank[docType] ?: all.size

    /**
     * The least privileged role that may write [docType]. A PARENT device may write every type; a KID device only
     * [CHORE_SUBMISSION], and then only what [kidMayWrite] allows.
     *
     * @throws IllegalArgumentException for an unknown type (check [isKnown] first).
     */
    fun writerRole(docType: String): Role {
        require(isKnown(docType)) { "unknown document type" }
        return if (docType == CHORE_SUBMISSION) Role.KID else Role.PARENT
    }

    /**
     * Whether a KID [device] may write this document: only its own kid's [CHORE_SUBMISSION] (`payload.kidId` equals
     * the device's registered kid), only in status `SUBMITTED` (the decision is the parent's), and only under its
     * own id (`payload.id` equals [docId], so a kid cannot overwrite another submission's document).
     */
    fun kidMayWrite(docType: String, docId: String, payload: JsonObject, device: RegisteredDevice): Boolean {
        val kidId = device.kidId
        return docType == CHORE_SUBMISSION &&
            device.role == Role.KID &&
            kidId != null &&
            payload.string("kidId") == kidId.value &&
            payload.string("status") == SUBMITTED &&
            payload.string("id") == docId
    }

    /**
     * [kidMayWrite] for an envelope, which additionally must not be a tombstone: a tombstone beats every other copy
     * ([ConflictResolver]), so a kid deleting a submission could erase the parent's decision on it.
     */
    fun kidMayWrite(envelope: DocumentEnvelope, device: RegisteredDevice): Boolean =
        !envelope.deleted && kidMayWrite(envelope.docType, envelope.docId, envelope.payload, device)

    private const val SUBMITTED = "SUBMITTED"

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
