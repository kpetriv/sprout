package dev.kpetriv.sprout.core.sync

import dev.kpetriv.sprout.core.domainhousehold.SubmissionStatusPrecedence
import dev.kpetriv.sprout.core.model.ChoreSubmission
import dev.kpetriv.sprout.core.model.DeviceId
import kotlinx.serialization.json.JsonObject

/** What a receiver holds for one document: the fields conflict resolution needs from its stored envelope. */
data class StoredDocument(
    val lamport: Long,
    val authorDeviceId: DeviceId,
    val deleted: Boolean,
    val payload: JsonObject,
) {
    override fun toString(): String =
        "StoredDocument(lamport=$lamport, authorDeviceId=$authorDeviceId, deleted=$deleted)"

    companion object {
        fun of(envelope: DocumentEnvelope): StoredDocument =
            StoredDocument(envelope.lamport, envelope.authorDeviceId, envelope.deleted, envelope.payload)
    }
}

/**
 * Decides whether a verified remote copy of a document replaces the local one (M3 §3, D-M3-13).
 *
 * The decision is "apply iff the remote copy is strictly greater than the local one" under one total order on the
 * copies of a document, compared field by field:
 * 1. **Tombstones first.** A tombstone beats every live copy, whatever the lamports: once deleted, a document is
 *    only ever replaced by another tombstone with a greater `(lamport, authorDeviceId)`, so a late or concurrent
 *    update can never resurrect it. (Deletion is final and ids are never reused.)
 * 2. **Submission status rank.** For two live `chore_submission` copies, the higher
 *    [SubmissionStatusPrecedence.rank] wins (`APPROVED` > `REJECTED` > `SUBMITTED`), before lamports: a decision is
 *    never undone by a stale or later `SUBMITTED` copy.
 * 3. **Last writer wins** on `(lamport, authorDeviceId.value)`.
 *
 * The backend's document update rule (`firestore/firestore.rules`, mirrored by the `FakeBackend` test fixture) is the
 * same relation, so a write the rules refuse as stale is exactly one this resolver ignores.
 *
 * Because every step is a total preorder and the last one is a total order on distinct writes, the resulting state
 * is the maximum of the copies a replica has seen: **commutative** (arrival order does not matter), **associative**
 * and **idempotent** (re-applying a copy is [Decision.Ignore]), so replicas that see the same copies converge.
 *
 * Two guards keep malformed input from breaking that: a remote submission whose payload does not decode is ignored
 * (a local one that does not decode loses to any decodable copy), and a remote submission whose identity
 * (`id`, `choreId`, `dueOn`, `kidId`) differs from the local copy's is ignored, since those fields never change.
 */
object ConflictResolver {
    enum class Decision { Apply, Ignore }

    fun resolve(local: StoredDocument?, remote: DocumentEnvelope): Decision {
        if (local == null) return Decision.Apply
        val greater = when {
            local.deleted != remote.deleted -> remote.deleted

            !local.deleted && remote.docType == DocTypes.CHORE_SUBMISSION ->
                submissionRank(local, remote) ?: lastWriterWins(local, remote)

            else -> lastWriterWins(local, remote)
        }
        return if (greater) Decision.Apply else Decision.Ignore
    }

    /** True if remote wins, false if local wins, null if both have the same status (fall back to LWW). */
    private fun submissionRank(local: StoredDocument, remote: DocumentEnvelope): Boolean? {
        val remoteCopy = decodeSubmission(remote.payload)
        val localCopy = decodeSubmission(local.payload)
        return when {
            remoteCopy == null -> false

            localCopy == null -> true

            !sameIdentity(localCopy, remoteCopy) -> false

            else -> {
                val remoteRank = SubmissionStatusPrecedence.rank(remoteCopy.status)
                val localRank = SubmissionStatusPrecedence.rank(localCopy.status)
                if (remoteRank == localRank) null else remoteRank > localRank
            }
        }
    }

    private fun lastWriterWins(local: StoredDocument, remote: DocumentEnvelope): Boolean = when {
        remote.lamport != local.lamport -> remote.lamport > local.lamport
        else -> remote.authorDeviceId.value > local.authorDeviceId.value
    }

    private fun sameIdentity(a: ChoreSubmission, b: ChoreSubmission): Boolean =
        a.id == b.id && a.choreId == b.choreId && a.dueOn == b.dueOn && a.kidId == b.kidId

    private fun decodeSubmission(payload: JsonObject): ChoreSubmission? = try {
        DocumentJsonCodec.json.decodeFromJsonElement(ChoreSubmission.serializer(), payload)
    } catch (expected: IllegalArgumentException) {
        null
    }
}
