package dev.kpetriv.sprout.sync

import android.app.Application
import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import dev.kpetriv.sprout.FakeSessionStore
import dev.kpetriv.sprout.core.database.entity.RegistrationStatus
import dev.kpetriv.sprout.core.sync.SyncError
import dev.kpetriv.sprout.core.sync.SyncReport
import dev.kpetriv.sprout.parentSession
import dev.kpetriv.sprout.trialSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException
import kotlin.test.assertEquals

// A plain Application: the Hilt one would start the real startup work (database, Keystore) in onCreate.
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SyncWorkerTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    private fun worker(runAttemptCount: Int = 0, sync: BackgroundSync): SyncWorker =
        TestListenableWorkerBuilder<SyncWorker>(context)
            .setRunAttemptCount(runAttemptCount)
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ): ListenableWorker = SyncWorker(appContext, workerParameters, sync)
                },
            )
            .build()

    private class Runner(private val report: SyncReport) : SyncRunner {
        var syncs = 0

        override fun run(scope: CoroutineScope, trigger: Flow<Unit>): Job = error("not used")

        override suspend fun syncOnce(): SyncReport {
            syncs += 1
            return report
        }
    }

    @Test
    fun `a good sync, a permanent refusal and a gone household succeed`() = runTest {
        listOf(
            SyncReport(),
            SyncReport(error = SyncError.Permanent("x")),
            SyncReport(householdGone = true, error = SyncError.HouseholdGone),
        )
            .forEach { report ->
                val sync = SessionBackgroundSync(FakeSessionStore(parentSession()), Runner(report))
                assertEquals(ListenableWorker.Result.success(), worker(sync = sync).doWork())
            }
    }

    @Test
    fun `offline or signed out retries, then gives up after the last attempt`() = runTest {
        val offline =
            SessionBackgroundSync(FakeSessionStore(parentSession()), Runner(SyncReport(error = SyncError.Transient())))
        assertEquals(ListenableWorker.Result.retry(), worker(sync = offline).doWork())
        val signedOut =
            SessionBackgroundSync(
                FakeSessionStore(parentSession()),
                Runner(SyncReport(error = SyncError.Unauthenticated)),
            )
        assertEquals(ListenableWorker.Result.retry(), worker(sync = signedOut).doWork())
        assertEquals(
            ListenableWorker.Result.failure(),
            worker(runAttemptCount = SyncWorker.MAX_ATTEMPTS - 1, sync = offline).doWork(),
        )
    }

    @Test
    fun `no session, the trial household, a PENDING or a REVOKED device syncs nothing`() = runTest {
        val runner = Runner(SyncReport())
        listOf(
            null,
            trialSession(),
            parentSession(RegistrationStatus.PENDING),
            parentSession(RegistrationStatus.REVOKED),
        ).forEach { session ->
            assertEquals(
                ListenableWorker.Result.success(),
                worker(sync = SessionBackgroundSync(FakeSessionStore(session), runner)).doWork(),
            )
        }
        assertEquals(0, runner.syncs)
    }

    @Test
    fun `a storage failure retries`() = runTest {
        assertEquals(ListenableWorker.Result.retry(), worker { throw IOException("disk") }.doWork())
        assertEquals(ListenableWorker.Result.retry(), worker { throw android.database.SQLException("locked") }.doWork())
    }

    @Test
    fun `expedited runs before Android 12 get a foreground notification`() = runTest {
        val info = worker { BackgroundSyncOutcome.DONE }.getForegroundInfo()
        assertEquals(0x5717, info.notificationId)
    }
}
