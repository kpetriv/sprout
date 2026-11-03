package dev.kpetriv.sprout.sync

import dev.kpetriv.sprout.core.sync.SyncEngine
import dev.kpetriv.sprout.core.sync.SyncReport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/** The parts of [SyncEngine] the app drives; a seam so [SyncController] and [SyncWorker] are testable. */
interface SyncRunner {
    /** The latest finished sync's report (from the foreground loop, a worker or [syncOnce]). */
    val reports: StateFlow<SyncReport?>

    /** Syncs on remote changes and on [trigger] until the returned job is cancelled. */
    fun run(scope: CoroutineScope, trigger: Flow<Unit>): Job

    /** One pull-then-push pass. */
    suspend fun syncOnce(): SyncReport
}

class EngineSyncRunner @Inject constructor(private val engine: SyncEngine) : SyncRunner {
    override val reports: StateFlow<SyncReport?> get() = engine.reports

    override fun run(scope: CoroutineScope, trigger: Flow<Unit>): Job = engine.run(scope, trigger)

    override suspend fun syncOnce(): SyncReport = engine.syncOnce()
}
