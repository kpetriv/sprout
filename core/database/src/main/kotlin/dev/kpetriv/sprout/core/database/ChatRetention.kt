package dev.kpetriv.sprout.core.database

import androidx.room.withTransaction
import dev.kpetriv.sprout.core.database.entity.AuditAction
import dev.kpetriv.sprout.core.database.entity.AuditLogEntity
import dev.kpetriv.sprout.core.domainledger.LedgerJson
import dev.kpetriv.sprout.core.domainledger.chat.BlobContext
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.time.Clock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * What one chat prune removed from this device (M7 §2): [messages] rows, cached [blobs] and [readMarks]; [keptQueued]
 * older messages were kept because this device has not uploaded them yet. [before] is the cut: everything dated
 * before it went. Counts only: no ids, no content.
 */
data class ChatPruneResult(
    val before: Instant,
    val messages: Int,
    val blobs: Int,
    val readMarks: Int,
    val keptQueued: Int,
) {
    /** Whether anything was removed. */
    val removedAny: Boolean get() = messages + blobs + readMarks > 0
}

/** What [PruneChatUseCase] did. */
sealed interface ChatPruneOutcome {
    /** The household keeps chat messages (no retention set) or has no policy yet: nothing was removed. */
    data object KeepAll : ChatPruneOutcome

    /** Messages dated before [result]'s cut were removed. */
    data class Pruned(val result: ChatPruneResult) : ChatPruneOutcome
}

/**
 * Schedules the chat retention (M7 §2, D-M7-8): the app runs [PruneChatUseCase] once a day (`PruneChatWorker`) and
 * after every successful sync, on every device. Implemented in `app` with WorkManager; this module only names the
 * contract.
 */
interface ChatPruneScheduler {
    /** Makes sure the daily prune is scheduled; idempotent (an existing schedule is kept). */
    fun scheduleDaily()

    /** Runs one prune soon: after a successful sync, or after the household's retention changed. */
    fun pruneSoon()
}

/**
 * The chat retention (M7 §2, ADR-0011): removes this device's chat messages older than the household's
 * `chatRetentionDays` at [invoke]'s `now`. Each device prunes its own copy; the server copy goes through the
 * `pruneMessages` callable, which a parent device calls with [ChatPruneResult.before] afterwards (D-M7-8).
 *
 * Per thread, in one transaction: every message of the thread dated before the cut ([ChatPruner]), the cached blobs
 * only those messages named, and the thread's read mark when it is older than the cut. `message_seq` and `thread_keys`
 * stay, so a pulled copy of a removed message is a duplicate (its `seq` is below the expected one) and is never stored
 * again, and newer messages of every epoch still decrypt. Idempotent: a second run at the same `now` removes nothing.
 */
class PruneChatUseCase @Inject constructor(
    private val database: SproutDatabase,
    private val cache: BlobCache,
    private val clock: Clock,
) {
    suspend operator fun invoke(now: Instant = clock.now()): ChatPruneOutcome {
        val days = database.householdPolicyDao().get()?.chatRetentionDays ?: return ChatPruneOutcome.KeepAll
        return ChatPruneOutcome.Pruned(ChatPruner(database, cache, clock).prune(now - days.days, PRUNE_REASON))
    }

    private companion object {
        const val PRUNE_REASON = "retention"
    }
}

/**
 * "Delete chat history now" on this device (M7 §2, D-M7-8): removes every chat message dated before [invoke]'s
 * `before` (at most now), as [PruneChatUseCase] does for the retention. A parent action: refused on a KID session
 * ([IllegalStateException]); the screen asks for parent authorisation and a typed confirmation first, and the app
 * then asks the server to purge the household's copy (`pruneMessages` with `purge = true`).
 */
class PurgeChatUseCase @Inject constructor(
    private val database: SproutDatabase,
    private val cache: BlobCache,
    private val sessions: SessionStore,
    private val clock: Clock,
) {
    suspend operator fun invoke(before: Instant = clock.now()): ChatPruneResult {
        val session = checkNotNull(sessions.get()) { "no session" }
        check(session.role == Role.PARENT) { "only a parent deletes the chat history" }
        val cut = minOf(before, clock.now())
        return ChatPruner(database, cache, clock).prune(cut, PURGE_REASON)
    }

    private companion object {
        const val PURGE_REASON = "purge"
    }
}

/** The prune both use cases share; see [PruneChatUseCase]. */
internal class ChatPruner(
    private val database: SproutDatabase,
    private val cache: BlobCache,
    private val clock: Clock,
) {
    private val messages = database.messageDao()

    suspend fun prune(before: Instant, reason: String): ChatPruneResult {
        val cut = before.toEpochMilliseconds()
        var total = ThreadPrune()
        for (threadId in messages.threadIds()) {
            val pruned = database.withTransaction { pruneThread(threadId, cut) }
            // The files go once their index rows are gone for good; a failure here leaves unreadable ciphertext only.
            cache.deleteFiles(pruned.files)
            total += pruned
        }
        val result = ChatPruneResult(before, total.messages, total.files.size, total.readMarks, total.keptQueued)
        if (result.removedAny) audit(result, reason)
        return result
    }

    private suspend fun pruneThread(threadId: String, cut: Long): ThreadPrune {
        val outbox = database.outboxDao()
        val rows = messages.prunable(threadId, cut)
        val ids = rows.map { it.messageId }
        val removed = ids.chunked(MAX_QUERY_IDS).sumOf { messages.deleteIds(it) }
        val files = rows.mapNotNull { it.blobId }.distinct().mapNotNull { blobId ->
            val row = database.blobDao().get(blobId)
            val removable = row != null && isThreadBlob(row.contextJson, threadId) &&
                messages.countNaming(blobId) == 0 && outbox.countBlobChunks(blobId) == 0
            if (removable) {
                database.blobDao().delete(blobId)
                requireNotNull(row).fileName
            } else {
                null
            }
        }
        val readMarks = messages.deleteReadMarkBefore(threadId, cut)
        // What is still older than the cut now is what was kept: this device's queued messages.
        return ThreadPrune(removed, files, readMarks, messages.countOlderThan(threadId, cut))
    }

    /** A cached blob may go only when it is a chat blob of [threadId] (never a chore photo another row names). */
    private fun isThreadBlob(contextJson: String, threadId: String): Boolean = try {
        LedgerJson.json.decodeFromString(BlobContext.serializer(), contextJson).threadId?.value == threadId
    } catch (expected: IllegalArgumentException) {
        false
    }

    private suspend fun audit(result: ChatPruneResult, reason: String) {
        val session = database.sessionDao().get() ?: return
        val details: JsonObject = buildJsonObject {
            put("reason", reason)
            put("before", result.before.toEpochMilliseconds())
            put("messages", result.messages)
            put("blobs", result.blobs)
            put("readMarks", result.readMarks)
            put("keptQueued", result.keptQueued)
        }
        database.auditLogDao().insert(
            AuditLogEntity(
                actorUid = session.uid,
                deviceId = session.deviceId,
                action = AuditAction.CHAT_PRUNED,
                target = session.householdId.value,
                detailsJson = LedgerJson.json.encodeToString(JsonObject.serializer(), details),
                atEpochMs = clock.now().toEpochMilliseconds(),
            ),
        )
    }

    private data class ThreadPrune(
        val messages: Int = 0,
        val files: List<String> = emptyList(),
        val readMarks: Int = 0,
        val keptQueued: Int = 0,
    ) {
        operator fun plus(other: ThreadPrune) = ThreadPrune(
            messages + other.messages,
            files + other.files,
            readMarks + other.readMarks,
            keptQueued + other.keptQueued,
        )
    }

    private companion object {
        /** Ids per `IN (...)` query, well below SQLite's bound on variables. */
        const val MAX_QUERY_IDS = 500
    }
}
