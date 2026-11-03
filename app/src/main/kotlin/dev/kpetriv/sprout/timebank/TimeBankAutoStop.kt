package dev.kpetriv.sprout.timebank

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.kpetriv.sprout.core.database.usecase.StopTimeBankUseCase
import dev.kpetriv.sprout.core.database.usecase.TimeBankResult
import dev.kpetriv.sprout.core.database.usecase.TimeBankStatusReader
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.time.Clock
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Schedules and cancels the WorkManager job that stops a kid's time bank session when the balance runs out. */
interface TimeBankAutoStop {
    /** Stops [kidId]'s session started at [startedAt] once [balanceMinutes] have passed. Replaces an earlier job. */
    fun schedule(kidId: KidId, startedAt: Instant, balanceMinutes: Long)

    fun cancel(kidId: KidId)
}

/**
 * [TimeBankAutoStop] as a one-time [TimeBankAutoStopWorker] per kid (unique name [uniqueName], tag [TAG], so a
 * removal cancels them all), due a few seconds after the balance runs out (D-M4-8, D-M4-9; no exact alarms).
 */
class WorkManagerTimeBankAutoStop @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val clock: Clock,
) : TimeBankAutoStop {
    override fun schedule(kidId: KidId, startedAt: Instant, balanceMinutes: Long) {
        val delay = delayUntilEmpty(startedAt, balanceMinutes, clock.now())
        val request = OneTimeWorkRequestBuilder<TimeBankAutoStopWorker>()
            .setInitialDelay(delay.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(TimeBankAutoStopWorker.KID_ID to kidId.value))
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(uniqueName(kidId), ExistingWorkPolicy.REPLACE, request)
    }

    override fun cancel(kidId: KidId) {
        WorkManager.getInstance(context).cancelUniqueWork(uniqueName(kidId))
    }

    companion object {
        /** Tag of every auto-stop job. */
        const val TAG: String = "timebank-auto-stop"

        /** Margin after the last whole minute, so the job never runs before the balance is used up. */
        private val GRACE: Duration = 5.seconds

        fun uniqueName(kidId: KidId): String = "$TAG-${kidId.value}"

        /** How long from [now] until the session started at [startedAt] has used [balanceMinutes] (never negative). */
        fun delayUntilEmpty(startedAt: Instant, balanceMinutes: Long, now: Instant): Duration =
            (startedAt + balanceMinutes.coerceAtLeast(0).minutes + GRACE - now).coerceAtLeast(Duration.ZERO)
    }
}

/**
 * Stops a kid's running time bank session once its balance is used up ([StopTimeBankUseCase] debits
 * `min(elapsed, balance)`, so exactly the balance), then removes the notification. A job that runs early (the clock
 * moved) schedules itself again for the rest; one whose session already ended only removes the notification.
 * Storage failures retry with WorkManager's backoff; the start-up reconcile covers the rest (D-M4-8).
 */
@HiltWorker
@Suppress("LongParameterList") // WorkManager's two arguments plus one collaborator per step.
class TimeBankAutoStopWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val reader: TimeBankStatusReader,
    private val stop: StopTimeBankUseCase,
    private val notifier: TimeBankNotifier,
    private val autoStop: TimeBankAutoStop,
    private val clock: Clock,
) : CoroutineWorker(context, params) {
    @Suppress("TooGenericExceptionCaught", "SwallowedException", "ReturnCount") // Nothing may be logged here.
    override suspend fun doWork(): Result {
        val kidId = inputData.getString(KID_ID)?.let(::KidId) ?: return Result.failure()
        return try {
            val status = reader.status(kidId)
            val startedAt = status?.startedAt
            if (status == null || !status.running || startedAt == null) {
                notifier.cancel(kidId)
                return Result.success()
            }
            val balance = status.balance.minorUnits
            if (status.elapsedMinutes(clock.now()) < balance) {
                autoStop.schedule(kidId, startedAt, balance)
                return Result.success()
            }
            if (stop(kidId) is TimeBankResult.Stopped) notifier.cancel(kidId)
            Result.success()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            if (runAttemptCount + 1 < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val KID_ID: String = "kidId"
        const val MAX_ATTEMPTS: Int = 3
    }
}
