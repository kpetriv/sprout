package dev.kpetriv.sprout.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.kpetriv.sprout.core.domainledger.chat.MessageEnvelope
import dev.kpetriv.sprout.core.domainledger.chat.MessageKind
import dev.kpetriv.sprout.core.model.BlobId
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.MessageId
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.model.ThreadId
import dev.kpetriv.sprout.core.model.UserId
import kotlin.time.Instant

/** Where a stored chat message stands on this device (M4 §4). */
enum class MessageStatus {
    /** Verified and decrypted: [MessageEntity.plaintext] is set. */
    RECEIVED,

    /** Verified, but no key of its epoch decrypts it yet; retried when a thread key arrives (M4 §1.3). */
    LOCKED,

    /**
     * Authenticated but not decidable yet (a gap in the author's `seq`, an unknown or pending author device, a lamport
     * too far ahead): kept with its ciphertext and verified again later. Never shown.
     */
    HELD,

    /** Refused for good. Kept for the audit view with ids and the reason only: no ciphertext, no plaintext. */
    QUARANTINED,

    /** Written on this device: [MessageEntity.plaintext] is what was sent. */
    SENT_LOCAL,
}

/**
 * One chat message (M4 §1.3, §4): the signed [MessageEnvelope] as columns, plus what this device knows about it.
 * Written only by `MessageWrites` (local sends) and the sync applier (pulled messages); never updated except for
 * decryption ([status] LOCKED to RECEIVED) and the release of a held message.
 *
 * Message content is personal data: [plaintext] (the canonical `MessagePlaintextCodec` bytes, set once decrypted) and
 * [ciphertext] never appear in `toString`, logs or audit rows. The database file is SQLCipher-encrypted at rest.
 *
 * @property ciphertext the envelope's base64 ciphertext; empty for a [MessageStatus.QUARANTINED] row.
 * @property reason the hold or quarantine reason (`QuarantineReason` name); null otherwise.
 * @property receivedAtEpochMs when the row became visible on this device (local wall clock): the unread count's key.
 * @property displayedAtEpochMs the time the screens show (audit M4): the signed `createdAt`, raised to the previous
 *   message's of the same author in the thread when it is earlier, so an author cannot backdate a message.
 * @property late accepted for a `seq` this device had already skipped (audit L3): the gap timed out before it came.
 */
@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["threadId", "lamport"]),
        Index(value = ["authorDeviceId", "threadId", "seq"]),
        Index(value = ["status"]),
    ],
)
@Suppress("LongParameterList") // One column per envelope field.
class MessageEntity(
    @PrimaryKey val messageId: String,
    val householdId: HouseholdId,
    val threadId: String,
    val authorUid: UserId,
    val authorDeviceId: DeviceId,
    val authorRole: Role,
    val seq: Long,
    val lamport: Long,
    val createdAtEpochMs: Long,
    val epoch: Long,
    val kind: MessageKind,
    val ciphertext: String,
    val blobId: String?,
    val signature: String?,
    val schemaVersion: Int,
    val plaintext: ByteArray?,
    val status: MessageStatus,
    val reason: String?,
    val receivedAtEpochMs: Long,
    val displayedAtEpochMs: Long = createdAtEpochMs,
    val late: Boolean = false,
) {
    /** The envelope this row holds, or null for a quarantined row (or one that no longer passes its own checks). */
    fun toEnvelope(): MessageEnvelope? = if (status == MessageStatus.QUARANTINED) {
        null
    } else {
        try {
            MessageEnvelope(
                schemaVersion = schemaVersion,
                householdId = householdId,
                threadId = ThreadId(threadId),
                messageId = MessageId(messageId),
                authorUid = authorUid,
                authorDeviceId = authorDeviceId,
                authorRole = authorRole,
                seq = seq,
                lamport = lamport,
                createdAt = Instant.fromEpochMilliseconds(createdAtEpochMs),
                epoch = epoch,
                kind = kind,
                ciphertext = ciphertext,
                blobId = blobId?.let(::BlobId),
                signature = signature,
            )
        } catch (expected: IllegalArgumentException) {
            null
        }
    }

    @Suppress("LongParameterList") // Copy of the mutable part only.
    fun copy(
        plaintext: ByteArray? = this.plaintext,
        status: MessageStatus = this.status,
        reason: String? = this.reason,
        receivedAtEpochMs: Long = this.receivedAtEpochMs,
        ciphertext: String = this.ciphertext,
        signature: String? = this.signature,
    ): MessageEntity = MessageEntity(
        messageId, householdId, threadId, authorUid, authorDeviceId, authorRole, seq, lamport, createdAtEpochMs,
        epoch, kind, ciphertext, blobId, signature, schemaVersion, plaintext, status, reason, receivedAtEpochMs,
        displayedAtEpochMs, late,
    )

    override fun equals(other: Any?): Boolean = other is MessageEntity && fields() == other.fields() &&
        (plaintext?.contentEquals(other.plaintext) ?: (other.plaintext == null))

    /** Every column but [plaintext], for [equals]. */
    private fun fields(): List<Any?> = listOf(
        messageId, householdId, threadId, authorUid, authorDeviceId, authorRole, seq, lamport, createdAtEpochMs,
        epoch, kind, ciphertext, blobId, signature, schemaVersion, status, reason, receivedAtEpochMs,
        displayedAtEpochMs, late,
    )

    override fun hashCode(): Int = messageId.hashCode()

    override fun toString(): String =
        "MessageEntity(messageId=$messageId, threadId=$threadId, authorDeviceId=$authorDeviceId, seq=$seq, " +
            "lamport=$lamport, epoch=$epoch, kind=$kind, status=$status)"

    companion object {
        /** A row for [envelope] in [status]; [plaintext] only for RECEIVED and SENT_LOCAL. */
        @Suppress("LongParameterList") // The envelope plus what this device knows about it.
        fun of(
            envelope: MessageEnvelope,
            status: MessageStatus,
            plaintext: ByteArray?,
            reason: String?,
            receivedAtEpochMs: Long,
            displayedAtEpochMs: Long = envelope.createdAt.toEpochMilliseconds(),
            late: Boolean = false,
        ): MessageEntity = MessageEntity(
            messageId = envelope.messageId.value,
            householdId = envelope.householdId,
            threadId = envelope.threadId.value,
            authorUid = envelope.authorUid,
            authorDeviceId = envelope.authorDeviceId,
            authorRole = envelope.authorRole,
            seq = envelope.seq,
            lamport = envelope.lamport,
            createdAtEpochMs = envelope.createdAt.toEpochMilliseconds(),
            epoch = envelope.epoch,
            kind = envelope.kind,
            ciphertext = if (status == MessageStatus.QUARANTINED) "" else envelope.ciphertext,
            blobId = envelope.blobId?.value,
            signature = if (status == MessageStatus.QUARANTINED) null else envelope.signature,
            schemaVersion = envelope.schemaVersion,
            plaintext = plaintext,
            status = status,
            reason = reason,
            receivedAtEpochMs = receivedAtEpochMs,
            displayedAtEpochMs = displayedAtEpochMs,
            late = late,
        )
    }
}

/**
 * The next `seq` per (thread, author device) (M4 §1.3): for this device's own sends, the value the next message takes;
 * for every other author, the value [dev.kpetriv.sprout.core.domainledger.chat.MessageVerifier] expects next. A
 * missing row means 1.
 *
 * @property lastLamport the lamport of the last message accepted in `seq` order (audit M4): the next one must be
 *   higher, or it is quarantined `OUT_OF_ORDER`.
 * @property lastDisplayedAtEpochMs the displayed time of that message: later messages are shown no earlier.
 * @property skippedRanges the `seq` ranges skipped after a gap timed out (audit L3), as `from-to` (inclusive) pairs
 *   separated by commas, oldest first; a late message for one of them is still accepted (marked late).
 */
@Entity(tableName = "message_seq", primaryKeys = ["threadId", "authorDeviceId"])
data class MessageSeqEntity(
    val threadId: String,
    val authorDeviceId: DeviceId,
    val nextSeq: Long,
    val lastLamport: Long = 0,
    val lastDisplayedAtEpochMs: Long = 0,
    val skippedRanges: String = "",
)

/**
 * One thread key document this device verified (M4 §1.2), per (thread, epoch, creator): the key it unwraps to, when
 * this device could unwrap it. Two parents rotating to the same epoch at once create two keys; both are kept, so a
 * message of that epoch decrypts with whichever its sender used. The latest document of a creator for an epoch (a
 * re-wrap for a new member) replaces the earlier one by last-writer-wins and keeps the key.
 *
 * The key bytes are secret: they never appear in `toString` or logs. The database file is SQLCipher-encrypted.
 *
 * @property key the 32-byte thread key, or null while this device has no wrap it can open (no wrap for it, the
 *   creator's encryption key is not in the registry mirror yet, or this device has no encryption key).
 * @property olderKeys earlier keys of the same creator and epoch (32 bytes each, joined) that a later document of
 *   that creator replaced with a different key (review H2): kept so messages sent under them stay readable.
 * @property confirmed the server has the document: always for a pulled one; for one this device published, once its
 *   own copy came back in a pull or its outbox row was acked without a refusal ([dev.kpetriv.sprout.core.database
 *   .ThreadKeyWrites.settle]). This device never sends under its own unconfirmed key.
 * @property sourceDocId the document id, `"<kidId>.<epoch>.<createdByDeviceId>"`.
 * @property wrapSetJson the document's `wraps` object (device id to `{wrapped}`), so the document can be rebuilt.
 * @property lamport the document envelope's lamport (last-writer-wins key with [createdByDeviceId]).
 */
@Entity(tableName = "thread_keys", primaryKeys = ["threadId", "epoch", "createdByDeviceId"])
@Suppress("LongParameterList") // One column per document field.
class ThreadKeyEntity(
    val threadId: String,
    val epoch: Long,
    val createdByDeviceId: DeviceId,
    val key: ByteArray?,
    val sourceDocId: String,
    val wrapSetJson: String,
    val lamport: Long,
    val authorUid: UserId,
    val signature: String?,
    val receivedAtEpochMs: Long,
    val confirmed: Boolean = true,
    val olderKeys: ByteArray? = null,
) {
    fun withKey(key: ByteArray?): ThreadKeyEntity = ThreadKeyEntity(
        threadId, epoch, createdByDeviceId, key, sourceDocId, wrapSetJson, lamport, authorUid, signature,
        receivedAtEpochMs, confirmed, olderKeys,
    )

    fun withConfirmed(confirmed: Boolean): ThreadKeyEntity = ThreadKeyEntity(
        threadId, epoch, createdByDeviceId, key, sourceDocId, wrapSetJson, lamport, authorUid, signature,
        receivedAtEpochMs, confirmed, olderKeys,
    )

    /** Every key of this row: [key] first, then [olderKeys] (each 32 bytes). */
    fun allKeys(): List<ByteArray> = listOfNotNull(key) +
        (
            olderKeys?.let { joined -> (joined.indices step KEY_SIZE).map { joined.copyOfRange(it, it + KEY_SIZE) } }
                ?: emptyList()
            )

    @Suppress("CyclomaticComplexMethod") // One comparison per column.
    override fun equals(other: Any?): Boolean = other is ThreadKeyEntity && threadId == other.threadId &&
        epoch == other.epoch && createdByDeviceId == other.createdByDeviceId &&
        (key?.contentEquals(other.key) ?: (other.key == null)) && sourceDocId == other.sourceDocId &&
        wrapSetJson == other.wrapSetJson && lamport == other.lamport && authorUid == other.authorUid &&
        signature == other.signature && receivedAtEpochMs == other.receivedAtEpochMs &&
        confirmed == other.confirmed && (olderKeys?.contentEquals(other.olderKeys) ?: (other.olderKeys == null))

    override fun hashCode(): Int = 31 * (31 * threadId.hashCode() + epoch.hashCode()) + createdByDeviceId.hashCode()

    override fun toString(): String =
        "ThreadKeyEntity(threadId=$threadId, epoch=$epoch, createdByDeviceId=$createdByDeviceId, " +
            "hasKey=${key != null}, lamport=$lamport, confirmed=$confirmed)"

    companion object {
        /** Bytes of one thread key. */
        const val KEY_SIZE: Int = 32
    }
}

/**
 * The local cache index of one blob (M4 §2): the blob's E2EE ciphertext (all chunks joined) is kept in
 * `noBackupFilesDir/blobs/<fileName>`, encrypted once more with the per-install `blobs.key` (defence in depth).
 * Evicted least recently used beyond the cache budget.
 *
 * @property sha256 lower-case hex SHA-256 of the E2EE ciphertext (the chunk headers' `sha256`).
 * @property size the E2EE ciphertext's size in bytes.
 * @property contextJson the blob's AEAD context (`BlobContext`), to decrypt it with its thread key.
 */
@Entity(tableName = "blobs", indices = [Index(value = ["lastUsedEpochMs"])])
data class BlobEntity(
    @PrimaryKey val blobId: String,
    val fileName: String,
    val sha256: String,
    val size: Long,
    val contextJson: String,
    val lastUsedEpochMs: Long,
)

/** The newest message of a thread this device has shown (local unread counts only; no read receipts). */
@Entity(tableName = "read_marks")
data class ReadMarkEntity(@PrimaryKey val threadId: String, val lastReadAtEpochMs: Long)
