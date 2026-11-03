package dev.kpetriv.sprout.work

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.kpetriv.sprout.core.database.Session
import dev.kpetriv.sprout.core.database.SessionStore
import dev.kpetriv.sprout.core.database.entity.RegistrationStatus
import dev.kpetriv.sprout.core.database.usecase.PlanScheduledActionsUseCase
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.sync.ShellPreferences
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/** Plans the scheduled parent actions up to today. A seam over `PlanScheduledActionsUseCase` for tests. */
fun interface ScheduledActionsPlanner {
    suspend fun planUpToToday()
}

/**
 * [ScheduledActionsPlanner] over [PlanScheduledActionsUseCase] with the household-local today, gated on the session
 * (review M5): the trial session plans; a real household plans only on an ACTIVE PARENT device after its first
 * successful pull ([ShellPreferences.firstPullDone]), so it never queues payments another parent device already made;
 * a KID or PENDING device, or no session at all, never plans. The daily worker, startup and sync all go through it.
 */
class UseCaseScheduledActionsPlanner @Inject constructor(
    private val useCase: PlanScheduledActionsUseCase,
    private val sessions: SessionStore,
    private val preferences: ShellPreferences,
) : ScheduledActionsPlanner {
    override suspend fun planUpToToday() {
        if (mayPlan(sessions.get(), preferences)) useCase()
    }

    companion object {
        fun mayPlan(session: Session?, preferences: ShellPreferences): Boolean = when {
            session == null -> false

            session.isLocalTrial -> true

            else ->
                session.role == Role.PARENT && session.deviceStatus == RegistrationStatus.ACTIVE &&
                    preferences.firstPullDone(session.householdId)
        }
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
