package dev.kpetriv.sprout.chat

import android.content.Context
import android.database.SQLException
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.kpetriv.sprout.core.database.ChatPruneOutcome
import dev.kpetriv.sprout.core.database.ChatPruneResult
import dev.kpetriv.sprout.core.database.ChatPruneScheduler
import dev.kpetriv.sprout.core.database.PruneChatUseCase
import dev.kpetriv.sprout.core.database.PurgeChatUseCase
import dev.kpetriv.sprout.core.database.Session
import dev.kpetriv.sprout.core.database.SessionStore
import dev.kpetriv.sprout.core.database.SproutDatabase
import dev.kpetriv.sprout.core.database.entity.OutboxKind
import dev.kpetriv.sprout.core.database.entity.RegistrationStatus
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.sync.DocTypes
import dev.kpetriv.sprout.core.syncfirebase.FunctionsError
import dev.kpetriv.sprout.core.syncfirebase.FunctionsException
import dev.kpetriv.sprout.core.time.Clock
import dev.kpetriv.sprout.feature.settings.ChatPurgeOutcome
import dev.kpetriv.sprout.household.HouseholdAdminFunctions
import dev.kpetriv.sprout.sync.ShellPreferences
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.security.GeneralSecurityException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * What this device remembers about the server prune (M7 §2): when it last asked the server to apply the retention,
 * and a "Delete chat history now" whose server half has not gone through yet. Each value belongs to one household
 * ([ShellPreferences.digestOf]), so a pending purge never reaches another household after a re-link. Times only.
 */
interface ChatPruneRecord {
    fun lastServerPrune(household: String): Instant?

    fun setLastServerPrune(household: String, at: Instant)

    fun pendingPurge(household: String): Instant?

    fun setPendingPurge(household: String, before: Instant?)
}

class SharedChatPruneRecord @Inject constructor(@param:ApplicationContext private val context: Context) :
    ChatPruneRecord {
    private val preferences by lazy { context.getSharedPreferences(FILE, Context.MODE_PRIVATE) }

    override fun lastServerPrune(household: String): Instant? = read(LAST_SERVER_PRUNE, household)

    override fun setLastServerPrune(household: String, at: Instant) = write(LAST_SERVER_PRUNE, household, at)

    override fun pendingPurge(household: String): Instant? = read(PENDING_PURGE, household)

    override fun setPendingPurge(household: String, before: Instant?) = write(PENDING_PURGE, household, before)

    private fun read(key: String, household: String): Instant? =
        if (preferences.getString("$key.household", null) == household && preferences.contains(key)) {
            Instant.fromEpochMilliseconds(preferences.getLong(key, 0))
        } else {
            null
        }

    private fun write(key: String, household: String, value: Instant?) {
        preferences.edit().apply {
            if (value == null) {
                remove(key).remove("$key.household")
            } else {
                putLong(key, value.toEpochMilliseconds()).putString("$key.household", household)
            }
        }.apply()
    }

    private companion object {
        const val FILE = "sprout_chat_retention"
        const val LAST_SERVER_PRUNE = "lastServerPrune"
        const val PENDING_PURGE = "pendingPurge"
    }
}

/**
 * One server prune: at most `maxCalls` requests, `onCall` before each; success carries whether the server finished.
 */
internal typealias ServerPrune =
    suspend (before: Instant, purge: Boolean, maxCalls: Int, onCall: () -> Unit) -> Result<Boolean>

/**
 * The chat retention as the app runs it (M7 §2, D-M7-8, ADR-0011), on every device:
 *
 * [run] (daily and after every successful sync, [PruneChatWorker]): the local prune ([PruneChatUseCase]); then on an
 * ACTIVE PARENT device of a real household, the server half: a "Delete chat history now" whose server call failed
 * earlier is sent again, and the retention prune (`pruneMessages(result.before, purge = false)`) is asked at most once
 * every [SERVER_PRUNE_INTERVAL], and only while this device holds the newest policy (no unsent `household_policy`
 * write in the outbox: a cut computed from a policy the server has not seen yet would be refused). A household that
 * keeps its messages (`failed-precondition`) counts as done.
 *
 * [purgeAll] ("Delete chat history now", already confirmed by the parent): every local message before now
 * ([PurgeChatUseCase]), then the server with the same instant; when the server cannot be reached (or has more to
 * delete) the purge is kept pending for [run]. Kid-linked devices never call the server.
 *
 * Counts and failures are never logged.
 */
@Singleton
@Suppress("LongParameterList") // One seam per step.
class ChatRetention internal constructor(
    private val session: suspend () -> Session?,
    private val pruneLocal: suspend () -> ChatPruneOutcome,
    private val purgeLocal: suspend () -> ChatPruneResult,
    private val policyUnsent: suspend (Session) -> Boolean,
    /**
     * At most `maxCalls` requests, `onCall` before each; success carries whether the server finished (false: more to
     * delete, the rest next time).
     */
    private val serverPrune: ServerPrune,
    private val householdOf: (Session) -> String,
    private val record: ChatPruneRecord,
    private val clock: Clock,
) {
    @Volatile
    private var lastPurgeRetry: Instant? = null

    /** When this process sent each `pruneMessages` request of the last hour ([CALLS_PER_HOUR]). */
    private val calls = ArrayDeque<Instant>()

    @Inject
    @Suppress("LongParameterList") // One collaborator per step.
    constructor(
        sessions: SessionStore,
        prune: PruneChatUseCase,
        purge: PurgeChatUseCase,
        database: SproutDatabase,
        admin: HouseholdAdminFunctions,
        preferences: ShellPreferences,
        record: ChatPruneRecord,
        clock: Clock,
    ) : this(
        session = sessions::get,
        pruneLocal = { prune() },
        purgeLocal = { purge() },
        policyUnsent = { current ->
            val ref = "${DocTypes.HOUSEHOLD_POLICY}/${current.householdId.value}"
            database.outboxDao().countRef(OutboxKind.DOCUMENT, ref) > 0
        },
        serverPrune = { before, purgeAll, maxCalls, onCall ->
            admin.pruneMessages(before, purgeAll, maxCalls, onCall).map { !it.more }
        },
        householdOf = { preferences.digestOf(it.householdId) },
        record = record,
        clock = clock,
    )

    /** The daily and after-sync retention. Throws when the local prune fails (the worker retries). */
    @Suppress("ReturnCount") // One early return per condition the server call waits for.
    suspend fun run() {
        val outcome = pruneLocal()
        val current = session()?.takeIf { it.isServerPruner() } ?: return
        val household = householdOf(current)
        record.pendingPurge(household)?.let { before -> retryPurge(household, before) }
        if (outcome !is ChatPruneOutcome.Pruned || policyUnsent(current)) return
        val now = clock.now()
        val last = record.lastServerPrune(household)
        if (last != null && now - last < SERVER_PRUNE_INTERVAL && last <= now) return
        val answer = callServer(outcome.result.before, purge = false) ?: return
        if (answer.isSuccess || answer.isKeepsMessages()) record.setLastServerPrune(household, now)
    }

    /** "Delete chat history now" after the typed confirmation and the parent's confirmation. */
    // Any local failure: nothing was deleted. One return per outcome.
    @Suppress("TooGenericExceptionCaught", "SwallowedException", "ReturnCount")
    suspend fun purgeAll(): ChatPurgeOutcome {
        val current = session() ?: return ChatPurgeOutcome.FAILED
        val result = try {
            purgeLocal()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            return ChatPurgeOutcome.FAILED
        }
        // The trial household has no server copy.
        if (current.isLocalTrial) return ChatPurgeOutcome.PURGED
        val household = householdOf(current)
        // The server gets exactly the instant the local purge used (the proof binds it).
        lastPurgeRetry = clock.now()
        val sent = current.isServerPruner() && callServer(result.before, purge = true)?.getOrNull() == true
        record.setPendingPurge(household, if (sent) null else result.before)
        return if (sent) ChatPurgeOutcome.PURGED else ChatPurgeOutcome.PURGED_ON_THIS_PHONE
    }

    /**
     * Sends a pending purge again, at most every [PURGE_RETRY_INTERVAL] in this process (each attempt is up to four
     * calls; the server allows 10 an hour), until the server says it is finished.
     */
    private suspend fun retryPurge(household: String, before: Instant) {
        val now = clock.now()
        val last = lastPurgeRetry
        if (last != null && now - last < PURGE_RETRY_INTERVAL && last <= now) return
        lastPurgeRetry = now
        if (callServer(before, purge = true)?.getOrNull() == true) record.setPendingPurge(household, null)
    }

    /**
     * One [serverPrune] within the hourly budget ([CALLS_PER_HOUR] requests in this process, below the server's 10 an
     * hour per account), or null when the budget is used up (tried again at the next run).
     */
    private suspend fun callServer(before: Instant, purge: Boolean): Result<Boolean>? {
        val left = synchronized(calls) {
            val hourAgo = clock.now() - 1.hours
            while (calls.firstOrNull()?.let { it < hourAgo } == true) calls.removeFirst()
            CALLS_PER_HOUR - calls.size
        }
        if (left <= 0) return null
        return serverPrune(before, purge, minOf(left, MAX_CALLS_PER_RUN)) {
            synchronized(calls) { calls.addLast(clock.now()) }
        }
    }

    private fun Session.isServerPruner(): Boolean =
        !isLocalTrial && role == Role.PARENT && deviceStatus == RegistrationStatus.ACTIVE

    private fun Result<*>.isKeepsMessages(): Boolean =
        (exceptionOrNull() as? FunctionsException)?.error == FunctionsError.FailedPrecondition

    companion object {
        /** At most one retention call to the server a day (a little less, so a daily worker that runs early counts). */
        val SERVER_PRUNE_INTERVAL = 20.hours

        /** How often a pending "Delete chat history now" is sent again. */
        val PURGE_RETRY_INTERVAL = 30.minutes

        /** `pruneMessages` requests this process sends in any hour (the server allows 10 per account). */
        const val CALLS_PER_HOUR = 8

        /** Requests of one run (5,000 documents each); the rest goes at the next run. */
        const val MAX_CALLS_PER_RUN = 4
    }
}

/** [ChatPruneScheduler] over WorkManager: [PruneChatWorker] once a day and once soon. */
class WorkManagerChatPruneScheduler @Inject constructor(@param:ApplicationContext private val context: Context) :
    ChatPruneScheduler {
    override fun scheduleDaily() {
        val request = PeriodicWorkRequestBuilder<PruneChatWorker>(1, TimeUnit.DAYS).build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PruneChatWorker.DAILY_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    override fun pruneSoon() {
        val request = OneTimeWorkRequestBuilder<PruneChatWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(PruneChatWorker.SOON_NAME, ExistingWorkPolicy.KEEP, request)
    }
}

/**
 * Runs [ChatRetention.run] (M7 §2): daily, after each successful sync and after the retention changed. Storage and
 * Keystore failures retry with WorkManager's backoff up to [MAX_ATTEMPTS] runs. Nothing is logged.
 */
@HiltWorker
class PruneChatWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val retention: ChatRetention,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        retention.run()
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (expected: SQLException) {
        retryOrFail()
    } catch (expected: IOException) {
        retryOrFail()
    } catch (expected: GeneralSecurityException) {
        retryOrFail()
    } catch (expected: IllegalStateException) {
        // No session any more (signed out or wiped meanwhile): nothing to prune.
        Result.success()
    }

    private fun retryOrFail(): Result = if (runAttemptCount + 1 < MAX_ATTEMPTS) Result.retry() else Result.failure()

    companion object {
        const val DAILY_NAME = "prune-chat-daily"
        const val SOON_NAME = "prune-chat-soon"
        const val MAX_ATTEMPTS = 3
    }
}
