package dev.kpetriv.sprout.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/** Schedules [SyncWorker] runs (M3 §6). */
interface SyncScheduler {
    /** Every 15 minutes with a network, unless already scheduled. */
    fun schedulePeriodic()

    /** One expedited run as soon as there is a network (an outbox insert while the app is in the background). */
    fun requestExpedited()
}

/** [SyncScheduler] on [WorkManager], unique work kept when already enqueued. */
class WorkManagerSyncScheduler @Inject constructor(@param:ApplicationContext private val context: Context) :
    SyncScheduler {
    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    override fun schedulePeriodic() {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(PERIOD_MINUTES, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(SyncWorker.PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    override fun requestExpedited() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(SyncWorker.ONE_OFF_NAME, ExistingWorkPolicy.KEEP, request)
    }

    private companion object {
        const val PERIOD_MINUTES = 15L
    }
}
