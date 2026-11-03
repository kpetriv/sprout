package dev.kpetriv.sprout.work

import android.content.Context
import android.database.SQLException
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.security.GeneralSecurityException

/**
 * Plans every kid's allowance and savings interest up to today into `pending_parent_actions` (through
 * [ScheduledActionsPlanner], i.e. `PlanScheduledActionsUseCase`); the parent signs them from the dashboard at the next
 * unlock (ADR-0004). Runs daily ([PlanScheduler]) and once at startup.
 *
 * Planning is idempotent, so a retry never queues a payment twice. Storage and Keystore failures retry with
 * WorkManager's backoff up to [MAX_ATTEMPTS] runs, then the run fails and the next daily run tries again. Nothing is
 * logged: the queue holds amounts for named kids.
 */
@HiltWorker
class PlanScheduledActionsWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val planner: ScheduledActionsPlanner,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        planner.planUpToToday()
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (expected: SQLException) {
        retryOrFail()
    } catch (expected: IOException) {
        retryOrFail()
    } catch (expected: GeneralSecurityException) {
        retryOrFail()
    }

    private fun retryOrFail(): Result = if (runAttemptCount + 1 < MAX_ATTEMPTS) Result.retry() else Result.failure()

    companion object {
        /** Unique name of the daily periodic work. */
        const val UNIQUE_NAME = "plan-scheduled-actions"

        /** Runs of one day's work, including the first, before it gives up until the next day. */
        const val MAX_ATTEMPTS = 3
    }
}
