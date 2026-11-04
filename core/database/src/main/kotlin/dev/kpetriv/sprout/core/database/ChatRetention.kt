package dev.kpetriv.sprout.core.database

import androidx.room.withTransaction
import dev.kpetriv.sprout.core.database.entity.AuditAction
import dev.kpetriv.sprout.core.database.entity.AuditLogEntity
import dev.kpetriv.sprout.core.database.entity.OutboxKind
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
 * older messages were kept because this device has not uploaded them yet (a retention prune); [droppedQueued] of
 * this device's own older messages were dropped before they uploaded (a purge, D-M7-59; also counted in [messages]).
 * [before] is the cut: everything dated before it went. Counts only: no ids, no content.
 */
data class ChatPruneResult(
    val before: Instant,
    val messages: Int,
    val blobs: Int,
    val readMarks: Int,
    val keptQueued: Int,
    val droppedQueued: Int = 0,
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
 * Per thread, in one transaction: every message of the thread this device received before the cut ([ChatPruner]; by
 * this device's own clock, never the author's signed `createdAt`, D-M7-52), the cached blobs only those messages
 * named, and the thread's read mark when it is older than the cut. `message_seq` and `thread_keys` stay, so a pulled
 * copy of a removed message is a duplicate (its `seq` is below the expected one) and is never stored again, and newer
 * messages of every epoch still decrypt. Then cache files no index row names are swept ([BlobCache.sweepOrphans]).
 * Idempotent: a second run at the same `now` removes nothing.
 */
class PruneChatUseCase @Inject constructor(
    private val database: SproutDatabase,
    private val cache: BlobCache,
    private val clock: Clock,
) {
    suspend operator fun invoke(now: Instant = clock.now()): ChatPruneOutcome {
        val days = database.householdPolicyDao().get()?.chatRetentionDays
        if (days == null) {
            cache.sweepOrphans()
            return ChatPruneOutcome.KeepAll
        }
        return ChatPruneOutcome.Pruned(
            ChatPruner(database, cache, clock).prune(now - days.days, PRUNE_REASON, dropQueued = false),
        )
    }

    private companion object {
        const val PRUNE_REASON = "retention"
    }
}

/**
 * "Delete chat history now" on this device (M7 §2, D-M7-8): removes every chat message dated before [invoke]'s
 * `before` (at most now), as [PruneChatUseCase] does for the retention, and also this device's own older messages
 * still queued for upload, with their outbox rows and queued blob chunks (D-M7-59): uploaded after the server purge,
 * they would bring deleted history back to the server and every phone. A queued blob a newer message still names is
 * kept. A parent action: refused on a KID session ([IllegalStateException]); the screen asks for parent
 * authorisation and a typed confirmation first, and the app then asks the server to purge the household's copy
 * (`pruneMessages` with `purge = true`). Other phones keep their copies until their own retention removes them.
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
        return ChatPruner(database, cache, clock).prune(cut, PURGE_REASON, dropQueued = true)
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

    /** Removes what is dated before [before]; with [dropQueued] this device's queued older messages too (a purge). */
    suspend fun prune(before: Instant, reason: String, dropQueued: Boolean): ChatPruneResult {
        val cut = before.toEpochMilliseconds()
        var total = ThreadPrune()
        for (threadId in messages.threadIds()) {
            val pruned = database.withTransaction { pruneThread(threadId, cut, dropQueued) }
            // The files go once their index rows are gone for good; a crash here leaves files the sweep below (or
            // the next run's) removes.
            cache.deleteFiles(pruned.files)
            total += pruned
        }
        cache.sweepOrphans()
        val result = ChatPruneResult(
            before = before,
            messages = total.messages,
            blobs = total.files.size,
            readMarks = total.readMarks,
            keptQueued = total.keptQueued,
            droppedQueued = total.droppedQueued,
        )
        if (result.removedAny) audit(result, reason)
        return result
    }

    private suspend fun pruneThread(threadId: String, cut: Long, dropQueued: Boolean): ThreadPrune {
        val outbox = database.outboxDao()
        val queued = if (dropQueued) messages.queuedBefore(threadId, cut) else emptyList()
        for (row in queued) outbox.deleteRef(OutboxKind.MESSAGE, row.messageId)
        val rows = messages.prunable(threadId, cut)
        val ids = rows.map { it.messageId }
        val removed = ids.chunked(MAX_QUERY_IDS).sumOf { messages.deleteIds(it) }
        // A dropped message's blob that nothing left names never uploads either: its queued chunks go too.
        for (blobId in queued.mapNotNull { it.blobId }.distinct()) {
            if (messages.countNaming(blobId) == 0) outbox.deleteBlobChunks(blobId)
        }
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
        return ThreadPrune(removed, files, readMarks, messages.countOlderThan(threadId, cut), queued.size)
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
            put("droppedQueued", result.droppedQueued)
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
        val droppedQueued: Int = 0,
    ) {
        operator fun plus(other: ThreadPrune) = ThreadPrune(
            messages + other.messages,
            files + other.files,
            readMarks + other.readMarks,
            keptQueued + other.keptQueued,
            droppedQueued + other.droppedQueued,
        )
    }

    private companion object {
        /** Ids per `IN (...)` query, well below SQLite's bound on variables. */
        const val MAX_QUERY_IDS = 500
    }
}
