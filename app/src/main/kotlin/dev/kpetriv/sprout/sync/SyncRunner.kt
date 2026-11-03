package dev.kpetriv.sprout.sync

import dev.kpetriv.sprout.core.sync.SyncEngine
import dev.kpetriv.sprout.core.sync.SyncReport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * The parts of [SyncEngine] the app drives; a seam so [SyncController] and [SyncWorker] are testable. Every report
 * reaches [SyncReportProcessor] through the engine itself.
 */
interface SyncRunner {
    /** Syncs on remote changes and on [trigger] until the returned job is cancelled. */
    fun run(scope: CoroutineScope, trigger: Flow<Unit>): Job

    /** One pull-then-push pass. */
    suspend fun syncOnce(): SyncReport
}

class EngineSyncRunner @Inject constructor(private val engine: SyncEngine) : SyncRunner {
    override fun run(scope: CoroutineScope, trigger: Flow<Unit>): Job = engine.run(scope, trigger)

    override suspend fun syncOnce(): SyncReport = engine.syncOnce()
}
