package dev.kpetriv.sprout.work

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.kpetriv.sprout.core.database.usecase.PlanScheduledActionsUseCase
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/** Plans the scheduled parent actions up to today. A seam over `PlanScheduledActionsUseCase` for tests. */
fun interface ScheduledActionsPlanner {
    suspend fun planUpToToday()
}

/** [ScheduledActionsPlanner] over [PlanScheduledActionsUseCase] with the household-local today. */
class UseCaseScheduledActionsPlanner @Inject constructor(private val useCase: PlanScheduledActionsUseCase) :
    ScheduledActionsPlanner {
    override suspend fun planUpToToday() {
        useCase()
    }
}

/** Schedules the daily planner. */
fun interface PlanScheduler {
    /** Enqueues the daily [PlanScheduledActionsWorker] unless already scheduled ([ExistingPeriodicWorkPolicy.KEEP]). */
    fun scheduleDaily()
}

/** [PlanScheduler] on [WorkManager]: a 24-hour [androidx.work.PeriodicWorkRequest], unique and kept if present. */
class WorkManagerPlanScheduler @Inject constructor(@ApplicationContext private val context: Context) : PlanScheduler {
    override fun scheduleDaily() {
        val request = PeriodicWorkRequestBuilder<PlanScheduledActionsWorker>(1, TimeUnit.DAYS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PlanScheduledActionsWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }
}
