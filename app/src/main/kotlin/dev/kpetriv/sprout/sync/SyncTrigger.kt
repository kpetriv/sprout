package dev.kpetriv.sprout.sync

import dev.kpetriv.sprout.core.database.SproutDatabase
import dev.kpetriv.sprout.core.time.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transformLatest
import javax.inject.Inject

/** Asks for a sync whenever something new is waiting to be pushed (M3 §6 "expedited one-off after an insert"). */
fun interface SyncTrigger {
    fun requests(): Flow<Unit>
}

/**
 * [SyncTrigger] over Room's invalidation tracker for the `outbox` table: no hook in `core/database` is needed, every
 * insert (and every ack or failed attempt) invalidates the table. A request is emitted when the head of the outbox is
 * due now, or, when the head waits for its backoff (a transient push failure, or a refused ledger event retried
 * hourly), once its next attempt time arrives (review M6): the loop wakes then instead of waiting for an unrelated
 * trigger. The queue is head-of-line (D-M3-15), so only the head's time matters; the next change to the table
 * replaces a pending wake-up.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OutboxSyncTrigger @Inject constructor(private val database: SproutDatabase, private val clock: Clock) :
    SyncTrigger {
    override fun requests(): Flow<Unit> = database.invalidationTracker.createFlow(OUTBOX_TABLE)
        .transformLatest {
            val head = database.outboxDao().head(1).firstOrNull() ?: return@transformLatest
            val wait = head.nextAttemptAtEpochMs - clock.now().toEpochMilliseconds()
            if (wait > 0) delay(wait)
            emit(Unit)
        }

    private companion object {
        const val OUTBOX_TABLE = "outbox"
    }
}
