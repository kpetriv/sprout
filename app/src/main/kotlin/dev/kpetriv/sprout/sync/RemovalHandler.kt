package dev.kpetriv.sprout.sync

import android.content.Context
import androidx.work.WorkManager
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.kpetriv.sprout.core.common.Dispatcher
import dev.kpetriv.sprout.core.common.SproutDispatchers
import dev.kpetriv.sprout.core.crypto.BlobStorage
import dev.kpetriv.sprout.core.crypto.EncryptedLockoutStateStore
import dev.kpetriv.sprout.core.crypto.FilesDirBlobStorage
import dev.kpetriv.sprout.core.crypto.ParentAuthorizer
import dev.kpetriv.sprout.core.database.LocalDataFiles
import dev.kpetriv.sprout.core.database.SproutDatabase
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.di.ApplicationScope
import dev.kpetriv.sprout.feature.lock.EncryptedParentPinStore
import dev.kpetriv.sprout.feature.lock.ParentSession
import dev.kpetriv.sprout.timebank.TimeBankNotifier
import dev.kpetriv.sprout.timebank.WorkManagerTimeBankAutoStop
import dev.kpetriv.sprout.work.PlanScheduledActionsWorker
import dev.kpetriv.sprout.work.UploadChorePhotosWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** Where a removal is reported: the sync loop, the worker, the session check, the gate, sign-out. */
fun interface HouseholdRemovals {
    fun remove(reason: RemovalReason)
}

/**
 * The WorkManager work a removal cancels (sync, the daily planner, the chore photo upload and every time bank
 * auto-stop), and the time bank notifications it removes.
 */
fun interface BackgroundWork {
    fun cancelAll()
}

class WorkManagerBackgroundWork @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val timeBankNotifications: TimeBankNotifier,
) : BackgroundWork {
    override fun cancelAll() {
        val work = WorkManager.getInstance(context)
        work.cancelUniqueWork(SyncWorker.PERIODIC_NAME)
        work.cancelUniqueWork(SyncWorker.ONE_OFF_NAME)
        work.cancelUniqueWork(PlanScheduledActionsWorker.UNIQUE_NAME)
        work.cancelUniqueWork(UploadChorePhotosWorker.UNIQUE_NAME)
        work.cancelAllWorkByTag(WorkManagerTimeBankAutoStop.TAG)
        timeBankNotifications.cancelAll()
    }
}

/**
 * The one owner of the wipe (audit H1, review H5). A removal is stored durably ([ShellPreferences.setRemoval]) the
 * moment it is reported, whoever detects it (the sync loop, a background [SyncWorker] with no UI, the session check
 * at start, the "Sign in again" gate, sign-out); then, on the app scope, it stops [SyncController], cancels the
 * unique sync and planner work, erases this phone's household data ([LocalDataEraser]) and marks the removal wiped,
 * but only once the eraser confirms that the database and the secrets are gone (audit L4): otherwise the removal stays
 * stored unwiped, [failed] is set, and the wipe runs again at the next start ([resume]). The first reason reported
 * wins. A process that dies half-way resumes the wipe at the next start too.
 *
 * The database is closed after the wipe, so the process must not go on using it: in the foreground the shell shows
 * the removed screen, whose "Continue" [acknowledge]s and restarts; in the background the process exits (the next
 * start shows the removed screen). Nothing is logged.
 */
@Singleton
@Suppress("LongParameterList") // Seams for each step of the wipe; the injected constructor is shorter.
class RemovalHandler internal constructor(
    private val scope: CoroutineScope,
    private val preferences: ShellPreferences,
    private val stopSync: () -> Unit,
    private val work: BackgroundWork,
    private val eraser: LocalDataEraser,
    private val inForeground: () -> Boolean,
    private val exitProcess: () -> Unit,
) : HouseholdRemovals {
    @Inject
    @Suppress("LongParameterList") // One collaborator per step of the wipe.
    constructor(
        @ApplicationScope scope: CoroutineScope,
        preferences: ShellPreferences,
        sync: Lazy<SyncController>,
        work: BackgroundWork,
        eraser: LocalDataEraser,
        foreground: ForegroundState,
    ) : this(
        scope = scope,
        preferences = preferences,
        stopSync = { sync.get().stop() },
        work = work,
        eraser = eraser,
        inForeground = foreground::isForeground,
        exitProcess = { Runtime.getRuntime().exit(0) },
    )

    private val mutableState = MutableStateFlow(preferences.removal())
    private val mutableFailed = MutableStateFlow(false)
    private val wiping = AtomicBoolean(false)

    /** The removal under way or wiped, or null. */
    val state: StateFlow<Removal?> = mutableState.asStateFlow()

    /** True when this process's wipe could not erase everything; the next start tries again. */
    val failed: StateFlow<Boolean> = mutableFailed.asStateFlow()

    /** True once a removal was reported (wiped or not). */
    val isRemoving: Boolean get() = mutableState.value != null

    /** Stores [reason] (unless a removal is already stored) and starts the wipe. Returns at once. */
    override fun remove(reason: RemovalReason) {
        val removal = synchronized(this) {
            mutableState.value ?: Removal(reason).also {
                preferences.setRemoval(it)
                mutableState.value = it
            }
        }
        startWipe(removal)
    }

    /** At process start: resumes a wipe a process death interrupted. True when a removal is stored. */
    fun resume(): Boolean {
        val removal = mutableState.value ?: return false
        startWipe(removal)
        return true
    }

    /** "Continue" after the wipe: forgets the removal, so the restart lands on onboarding. */
    fun acknowledge() {
        if (mutableState.value?.wiped == true) preferences.setRemoval(null)
    }

    private fun startWipe(removal: Removal) {
        if (removal.wiped || !wiping.compareAndSet(false, true)) return
        scope.launch { wipe(removal) }
    }

    private suspend fun wipe(removal: Removal) {
        bestEffort { stopSync() }
        bestEffort { work.cancelAll() }
        if (bestEffort { eraser.erase() } == true) {
            bestEffort { preferences.wipe() }
            val wiped = removal.copy(wiped = true)
            bestEffort { preferences.setRemoval(wiped) }
            mutableState.value = wiped
        } else {
            // Something is left behind: the removal stays stored unwiped and the next start erases again.
            mutableFailed.value = true
            wiping.set(false)
        }
        if (!inForeground()) exitProcess()
    }

    // Each step runs whatever the previous one did; a failure is dropped unlogged (it may carry personal data).
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private suspend fun <T> bestEffort(step: suspend () -> T): T? = try {
        step()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Exception) {
        null
    }
}

/**
 * Clears every table (chat messages and thread keys included), the photos and the cached chat blobs in place,
 * without closing the database (D-M3-47): used when a session of another account or household is about to be saved
 * over rows a wipe did not remove. The previous parent's secrets go too ([ParentSecrets]: the PIN hash, its lockout
 * state and the parent signing key, audit L10), so their PIN never guards the new account. The database, photo and
 * blob cache keys stay, and so does this device's chat encryption key (like its device key, it belongs to the
 * device; a new household gets it registered again with a new binding).
 */
class LocalHouseholdRows @Inject constructor(
    private val database: SproutDatabase,
    private val files: LocalDataFiles,
    private val parentSecrets: ParentSecrets,
    @param:Dispatcher(SproutDispatchers.IO) private val dispatcher: CoroutineDispatcher,
) {
    suspend fun clear() {
        withContext(dispatcher) {
            database.clearAllTables()
            files.photoDirectory.listFiles()?.forEach { it.deleteRecursively() }
            files.blobDirectory.listFiles()?.forEach { it.deleteRecursively() }
        }
        parentSecrets.clear()
    }
}

/** The previous parent's local secrets, for [LocalHouseholdRows.clear]. */
fun interface ParentSecrets {
    suspend fun clear()
}

/**
 * [ParentSecrets] over the `SecretStore` blobs of `feature/lock` (the PIN hash and the lockout state, each with its
 * wrapped key), the parent signing key in the Keystore ([ParentAuthorizer.resetParentKey]; its registration lives in
 * the tables just cleared) and [ParentSession.reload], so the parent lock asks for a new PIN.
 */
class StoredParentSecrets internal constructor(
    private val storage: BlobStorage,
    private val resetParentKey: suspend () -> Unit,
    private val reloadParentLock: suspend () -> Unit,
) : ParentSecrets {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        @Dispatcher(SproutDispatchers.IO) dispatcher: CoroutineDispatcher,
        authorizer: ParentAuthorizer,
        parentSession: ParentSession,
    ) : this(
        storage = FilesDirBlobStorage(context, dispatcher),
        resetParentKey = authorizer::resetParentKey,
        reloadParentLock = parentSession::reload,
    )

    override suspend fun clear() {
        PARENT_BLOBS.forEach { storage.delete(it) }
        resetParentKey()
        reloadParentLock()
    }

    companion object {
        /** The parent's blobs in `FilesDirBlobStorage` (each data blob and the wrapped key it is encrypted under). */
        val PARENT_BLOBS: List<String> = listOf(
            EncryptedParentPinStore.SECRET_NAME,
            EncryptedParentPinStore.HASH_BLOB,
            EncryptedLockoutStateStore.SECRET_NAME,
            EncryptedLockoutStateStore.STATE_BLOB,
        )
    }
}

/**
 * Whether the parent's PIN and signing step of onboarding has started and not finished (review M4), stored as a
 * digest of the household ([ShellPreferences.parentSetupPending]) so a process death on that step returns to it.
 */
@Singleton
class ParentSetupTracker @Inject constructor(private val preferences: ShellPreferences) {
    private val mutablePending = MutableStateFlow(preferences.parentSetupPending())

    /** The digest of the household whose setup is pending ([ShellPreferences.digestOf]), or null. */
    val pending: StateFlow<String?> = mutablePending.asStateFlow()

    fun start(householdId: HouseholdId) = set(preferences.digestOf(householdId))

    fun finish() = set(null)

    fun isPending(householdId: HouseholdId): Boolean = mutablePending.value == preferences.digestOf(householdId)

    /** The digest [pending] holds for [householdId]. */
    fun digestOf(householdId: HouseholdId): String = preferences.digestOf(householdId)

    private fun set(digest: String?) {
        preferences.setParentSetupPending(digest)
        mutablePending.value = digest
    }
}
