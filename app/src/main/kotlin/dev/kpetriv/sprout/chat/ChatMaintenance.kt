package dev.kpetriv.sprout.chat

import dagger.Lazy
import dev.kpetriv.sprout.core.database.Session
import dev.kpetriv.sprout.core.database.SessionStore
import dev.kpetriv.sprout.core.database.entity.RegistrationStatus
import dev.kpetriv.sprout.core.database.usecase.ReconcileTimeBankUseCase
import dev.kpetriv.sprout.core.database.usecase.SweepOrphanPhotosUseCase
import dev.kpetriv.sprout.core.database.usecase.ThreadKeyMaintenance
import dev.kpetriv.sprout.core.database.usecase.TimeBankStatusReader
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.di.ApplicationScope
import dev.kpetriv.sprout.sync.SyncController
import dev.kpetriv.sprout.sync.SyncUpkeep
import dev.kpetriv.sprout.timebank.TimeBankNotifier
import dev.kpetriv.sprout.timebank.UseCaseTimeBankControl
import dev.kpetriv.sprout.work.ChorePhotoUploader
import dev.kpetriv.sprout.work.PhotoUploadScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The M4 upkeep around sync (M4 §5.3), each step best effort and unlogged:
 *
 * At start-up ([atStartup], an `AppInitializer` step after the session check): the D-M2-14 orphan photo sweep, the
 * time bank reconcile (D-M4-8), the thread key upkeep on a parent device, the photo upload, and (on the app scope,
 * so a slow network never holds the start) the encryption key registration.
 *
 * After every successful sync ([afterSync], from `SyncReportProcessor`, under the engine's sync mutex, so nothing here
 * syncs): the encryption key registration when the registry mirror shows none for this device (D-M4-13; a key sent
 * now asks for a sync so the mirror catches up), then on a PARENT device the thread key upkeep
 * ([ThreadKeyMaintenance]: first keys, re-wraps for new devices, rotation after a removal, D-M4-7), the time bank
 * reconcile plus removing the notification of sessions that ended elsewhere, and the photo upload when photos wait.
 */
@Singleton
@Suppress("LongParameterList") // One seam per step.
class ChatMaintenance internal constructor(
    private val scope: CoroutineScope,
    private val session: suspend () -> Session?,
    private val registerKey: suspend () -> KeyRegistration,
    private val maintainThreadKeys: suspend () -> Unit,
    private val reconcileTimeBank: suspend () -> List<KidId>,
    private val timeBankEnded: (KidId) -> Unit,
    private val endedElsewhere: suspend () -> List<KidId>,
    private val sweepOrphans: suspend () -> Unit,
    private val photosWaiting: suspend () -> Boolean,
    private val uploadPhotos: () -> Unit,
    private val requestSync: () -> Unit,
) : SyncUpkeep {
    @Inject
    @Suppress("LongParameterList") // One collaborator per step.
    constructor(
        @ApplicationScope scope: CoroutineScope,
        sessions: SessionStore,
        registrar: EncryptionKeyRegistrar,
        threadKeys: ThreadKeyMaintenance,
        reconcile: ReconcileTimeBankUseCase,
        timeBank: UseCaseTimeBankControl,
        notifier: TimeBankNotifier,
        status: TimeBankStatusReader,
        sweep: SweepOrphanPhotosUseCase,
        uploader: ChorePhotoUploader,
        uploads: PhotoUploadScheduler,
        sync: Lazy<SyncController>,
    ) : this(
        scope = scope,
        session = sessions::get,
        registerKey = registrar::ensureRegistered,
        maintainThreadKeys = { threadKeys() },
        reconcileTimeBank = { reconcile() },
        timeBankEnded = timeBank::stopped,
        endedElsewhere = { notifier.activeKids().filter { status.status(it)?.running != true } },
        sweepOrphans = { sweep() },
        photosWaiting = uploader::needed,
        uploadPhotos = uploads::enqueue,
        requestSync = { sync.get().requestSync() },
    )

    /** The start-up steps. Returns once the local ones ran; the key registration continues on the app scope. */
    suspend fun atStartup() {
        bestEffort { sweepOrphans() }
        bestEffort { reconcileTimeBank().forEach(timeBankEnded) }
        val current = session() ?: return
        if (!current.isLocalTrial && current.deviceStatus == RegistrationStatus.ACTIVE) {
            if (current.role == Role.PARENT) bestEffort { maintainThreadKeys() }
            bestEffort { if (photosWaiting()) uploadPhotos() }
            scope.launch { bestEffort { registerKeyAndSync() } }
        }
    }

    /** The steps after a successful sync of a real household's ACTIVE session. */
    override suspend fun afterSync() {
        val current = session() ?: return
        if (current.isLocalTrial || current.deviceStatus != RegistrationStatus.ACTIVE) return
        bestEffort { registerKeyAndSync() }
        if (current.role == Role.PARENT) bestEffort { maintainThreadKeys() }
        bestEffort { reconcileTimeBank().forEach(timeBankEnded) }
        bestEffort { endedElsewhere().forEach(timeBankEnded) }
        bestEffort { if (photosWaiting()) uploadPhotos() }
    }

    private suspend fun registerKeyAndSync() {
        if (registerKey() == KeyRegistration.SENT) requestSync()
    }

    // A failing step never stops the others; nothing is logged (it may carry personal data).
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private suspend fun bestEffort(step: suspend () -> Unit) {
        try {
            step()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            // Retried at the next sync or start.
        }
    }
}
