package dev.kpetriv.sprout.core.model

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/** Longest identifier accepted. Firestore document ids and UUIDs fit comfortably. */
const val MAX_ID_LENGTH: Int = 128

/**
 * Every id is 1 to [MAX_ID_LENGTH] characters from `[A-Za-z0-9._-]`: ASCII only, so ordering ids by UTF-16 code
 * unit (the domain's tie-break, see `LedgerOrdering`) equals SQLite's BINARY collation and byte order on every
 * store, and an id can never smuggle whitespace, path separators, control characters or look-alike code points.
 */
val ID_PATTERN: Regex = Regex("[A-Za-z0-9._-]{1,$MAX_ID_LENGTH}")

private fun requireValidId(kind: String, value: String) {
    require(ID_PATTERN.matches(value)) { "$kind must be 1 to $MAX_ID_LENGTH characters from [A-Za-z0-9._-]" }
}

/** A household: the unit of sharing, sync and Firestore rules. */
@Serializable
@JvmInline
value class HouseholdId(val value: String) {
    init {
        requireValidId("HouseholdId", value)
    }

    override fun toString(): String = value
}

/** A kid profile within a household. */
@Serializable
@JvmInline
value class KidId(val value: String) {
    init {
        requireValidId("KidId", value)
    }

    override fun toString(): String = value
}

/** A ledger account (one per kid and currency, plus savings sub-accounts). */
@Serializable
@JvmInline
value class AccountId(val value: String) {
    init {
        requireValidId("AccountId", value)
    }

    override fun toString(): String = value
}

/** A registered device; ledger events are hash-chained and signed per device. */
@Serializable
@JvmInline
value class DeviceId(val value: String) {
    init {
        requireValidId("DeviceId", value)
    }

    override fun toString(): String = value
}

/** An authenticated user (the Firebase Auth uid for parents and kid devices alike). */
@Serializable
@JvmInline
value class UserId(val value: String) {
    init {
        requireValidId("UserId", value)
    }

    override fun toString(): String = value
}

/** A chore definition (recurring or one-off) assigned to one kid. */
@Serializable
@JvmInline
value class ChoreId(val value: String) {
    init {
        requireValidId("ChoreId", value)
    }

    override fun toString(): String = value
}

/** An item in the household's rewards catalogue. */
@Serializable
@JvmInline
value class RewardId(val value: String) {
    init {
        requireValidId("RewardId", value)
    }

    override fun toString(): String = value
}

/**
 * A kid's submission of one chore occurrence. Derived deterministically from the chore id and due date (see
 * `SubmissionIds` in core/domain-household), so submitting the same occurrence twice is idempotent.
 */
@Serializable
@JvmInline
value class SubmissionId(val value: String) {
    init {
        requireValidId("SubmissionId", value)
    }

    override fun toString(): String = value
}

/** A kid's savings goal. */
@Serializable
@JvmInline
value class GoalId(val value: String) {
    init {
        requireValidId("GoalId", value)
    }

    override fun toString(): String = value
}

/** An allowance schedule. */
@Serializable
@JvmInline
value class ScheduleId(val value: String) {
    init {
        requireValidId("ScheduleId", value)
    }

    override fun toString(): String = value
}

/** A chat message (a random UUID chosen by the author device); the idempotency key of the `messages` lane (M4). */
@Serializable
@JvmInline
value class MessageId(val value: String) {
    init {
        requireValidId("MessageId", value)
    }

    override fun toString(): String = value
}

/**
 * A chat thread (M4 §1.2). There is one thread per kid and its id is the kid's id, so [of] and [kidId] convert both
 * ways without a lookup.
 */
@Serializable
@JvmInline
value class ThreadId(val value: String) {
    init {
        requireValidId("ThreadId", value)
    }

    /** The kid this thread belongs to. */
    val kidId: KidId get() = KidId(value)

    override fun toString(): String = value

    companion object {
        /** The thread of [kidId]. */
        fun of(kidId: KidId): ThreadId = ThreadId(kidId.value)
    }
}

/** An encrypted blob (a chat photo or voice note, or a chore photo) stored as chunks in the `blobs` lane (M4 §2). */
@Serializable
@JvmInline
value class BlobId(val value: String) {
    init {
        requireValidId("BlobId", value)
    }

    override fun toString(): String = value
}
