package dev.kpetriv.sprout.settings

import dev.kpetriv.sprout.chat.ChatRetention
import dev.kpetriv.sprout.core.database.ChatExportReader
import dev.kpetriv.sprout.core.database.ChatPruneScheduler
import dev.kpetriv.sprout.core.database.ExportedMessage
import dev.kpetriv.sprout.core.database.HouseholdPolicyRepository
import dev.kpetriv.sprout.core.database.MediaSink
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.sync.SyncTransport
import dev.kpetriv.sprout.feature.settings.ChatExportMessage
import dev.kpetriv.sprout.feature.settings.ChatExportSource
import dev.kpetriv.sprout.feature.settings.ChatExportThread
import dev.kpetriv.sprout.feature.settings.ChatHistoryPurger
import dev.kpetriv.sprout.feature.settings.ChatMediaSink
import dev.kpetriv.sprout.feature.settings.ChatMissingMedia
import dev.kpetriv.sprout.feature.settings.ChatPurgeOutcome
import dev.kpetriv.sprout.feature.settings.HouseholdRules
import dev.kpetriv.sprout.feature.settings.HouseholdRulesState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * Settings' [HouseholdRules] over `core/database`'s [HouseholdPolicyRepository] (M7 §1, D-M7-10): parent-only writes
 * to Room and the outbox, like every policy change; the screen has asked for the parent's confirmation first
 * (D-M7-17). A new retention prunes this phone soon ([ChatPruneScheduler.pruneSoon]); the other phones prune when the
 * policy reaches them (after their sync).
 */
class RepositoryHouseholdRules @Inject constructor(
    private val policies: HouseholdPolicyRepository,
    private val prunes: ChatPruneScheduler,
) : HouseholdRules {
    override fun observe(): Flow<HouseholdRulesState> = policies.observe()
        .filterNotNull()
        .map { HouseholdRulesState(it.chatRetentionDays, it.dailyGameCapMinor) }
        .distinctUntilChanged()

    override suspend fun setChatRetentionDays(days: Int?) {
        policies.setChatRetention(days)
        prunes.pruneSoon()
    }

    override suspend fun setDailyGameCap(minorUnits: Long?) {
        policies.setDailyGameCap(minorUnits)
    }
}

/**
 * Settings' [ChatExportSource] over `core/database`'s [ChatExportReader] (M7 §3, D-M7-9): the threads decoded from
 * this phone's database (a thread's id is its kid's id), and the media from the blob cache or, when not cached,
 * fetched from the server's chunks ([SyncTransport.fetchBlob], best effort: a failure lists the file as missing).
 * The reader refuses anything but a PARENT session. Nothing here logs; the content is personal data.
 */
class ReaderChatExportSource @Inject constructor(
    private val reader: ChatExportReader,
    private val transport: SyncTransport,
) : ChatExportSource {
    override suspend fun threads(): List<ChatExportThread> = reader.threads().map { threadId ->
        val thread = reader.read(threadId)
        ChatExportThread(threadId, KidId(threadId.value), thread.messages.map { it.toPort() })
    }

    override suspend fun media(thread: ChatExportThread, sink: ChatMediaSink): List<ChatMissingMedia> =
        reader.exportMedia(
            reader.read(thread.threadId),
            MediaSink { fileName, bytes -> sink.write(fileName, bytes) },
        ) { blobId -> transport.fetchBlob(blobId).getOrThrow() }
            .map { ChatMissingMedia(it.media, it.reason.name) }

    private fun ExportedMessage.toPort() = ChatExportMessage(
        sentAt = sentAt,
        author = authorRole,
        kind = kind,
        text = text,
        presetId = presetId,
        emoji = emoji,
        durationMs = durationMs,
        media = media,
        locked = locked,
    )
}

/** Settings' [ChatHistoryPurger]: "Delete chat history now" through [ChatRetention.purgeAll] (D-M7-8). */
class RetentionChatHistoryPurger @Inject constructor(private val retention: ChatRetention) : ChatHistoryPurger {
    override suspend fun purgeAll(): ChatPurgeOutcome = retention.purgeAll()
}
