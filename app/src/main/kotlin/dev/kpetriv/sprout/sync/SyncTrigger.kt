package dev.kpetriv.sprout.sync

import dev.kpetriv.sprout.core.database.SproutDatabase
import dev.kpetriv.sprout.core.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/** Asks for a sync whenever something new is waiting to be pushed (M3 §6 "expedited one-off after an insert"). */
fun interface SyncTrigger {
    fun requests(): Flow<Unit>
}

/**
 * [SyncTrigger] over Room's invalidation tracker for the `outbox` table: no hook in `core/database` is needed, every
 * insert (and every ack or failed attempt) invalidates the table. A request is emitted only when the head of the
 * outbox is due now, so the acks of a sync and the backoff marks of a failed push do not ask for another sync (the
 * queue is head-of-line, D-M3-15: a head waiting for its backoff holds back everything behind it anyway).
 */
class OutboxSyncTrigger @Inject constructor(private val database: SproutDatabase, private val clock: Clock) :
    SyncTrigger {
    override fun requests(): Flow<Unit> = database.invalidationTracker.createFlow(OUTBOX_TABLE)
        .map { headIsDue() }
        .filter { it }
        .map { }

    private suspend fun headIsDue(): Boolean {
        val head = database.outboxDao().head(1).firstOrNull() ?: return false
        return head.nextAttemptAtEpochMs <= clock.now().toEpochMilliseconds()
    }

    private companion object {
        const val OUTBOX_TABLE = "outbox"
    }
}
