package dev.kpetriv.sprout.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.database.SQLException
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.kpetriv.sprout.R
import dev.kpetriv.sprout.core.database.SessionStore
import dev.kpetriv.sprout.core.database.entity.RegistrationStatus
import dev.kpetriv.sprout.core.sync.SyncError
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.security.GeneralSecurityException
import javax.inject.Inject

/** What one background sync ended with, as far as WorkManager is concerned. */
enum class BackgroundSyncOutcome {
    /** Synced, or nothing to sync (no real session), or refused for good (retrying cannot help). */
    DONE,

    /** Offline, server trouble or an expired sign-in: try again with WorkManager's backoff. */
    RETRY,
}

/** One background sync; a seam over the session and [SyncRunner] for [SyncWorker]. */
fun interface BackgroundSync {
    suspend fun syncOnce(): BackgroundSyncOutcome
}

/**
 * [BackgroundSync] over the real session: no session, the trial session, a PENDING device (onboarding polls while it
 * waits) or a REVOKED one (it is being wiped) sync nothing. The report itself (audit, removal, planning) is handled
 * by [SyncReportProcessor], which the engine calls for every report, so a removal found here wipes without any UI.
 */
class SessionBackgroundSync @Inject constructor(private val sessions: SessionStore, private val runner: SyncRunner) :
    BackgroundSync {
    override suspend fun syncOnce(): BackgroundSyncOutcome {
        val session = sessions.get()
        if (session == null || session.isLocalTrial || session.deviceStatus != RegistrationStatus.ACTIVE) {
            return BackgroundSyncOutcome.DONE
        }
        return when (runner.syncOnce().error) {
            null, is SyncError.Permanent, SyncError.HouseholdGone -> BackgroundSyncOutcome.DONE
            is SyncError.Transient, SyncError.Unauthenticated -> BackgroundSyncOutcome.RETRY
        }
    }
}

/**
 * Background sync (M3 §6): every 15 minutes with a network ([SyncScheduler.schedulePeriodic]) and once, expedited,
 * after an outbox insert while the app is in the background ([SyncScheduler.requestExpedited]). Runs one
 * [BackgroundSync]; transient failures and storage errors retry with WorkManager's backoff up to [MAX_ATTEMPTS]
 * runs (the periodic run tries again anyway). Nothing is logged.
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val sync: BackgroundSync,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        when (sync.syncOnce()) {
            BackgroundSyncOutcome.DONE -> Result.success()
            BackgroundSyncOutcome.RETRY -> retryOrFail()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (expected: SQLException) {
        retryOrFail()
    } catch (expected: IOException) {
        retryOrFail()
    } catch (expected: GeneralSecurityException) {
        retryOrFail()
    }

    /** Expedited work runs as a foreground service before Android 12; this is its (silent) notification. */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                applicationContext.getString(R.string.sync_channel_name),
                NotificationManager.IMPORTANCE_MIN,
            ),
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(applicationContext.getString(R.string.sync_notification_title))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification)
    }

    private fun retryOrFail(): Result = if (runAttemptCount + 1 < MAX_ATTEMPTS) Result.retry() else Result.failure()

    companion object {
        const val PERIODIC_NAME = "sync-periodic"
        const val ONE_OFF_NAME = "sync-now"

        /** Runs of one request, including the first, before it gives up until the next periodic run. */
        const val MAX_ATTEMPTS = 5

        private const val CHANNEL_ID = "sync"
        private const val NOTIFICATION_ID = 0x5717
    }
}
