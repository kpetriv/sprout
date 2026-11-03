package dev.kpetriv.sprout.work

import android.app.Application
import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
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
class PlanScheduledActionsWorkerTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private var plans = 0

    private fun worker(runAttemptCount: Int = 0, plan: suspend () -> Unit): PlanScheduledActionsWorker {
        val planner = ScheduledActionsPlanner {
            plans += 1
            plan()
        }
        return TestListenableWorkerBuilder<PlanScheduledActionsWorker>(context)
            .setRunAttemptCount(runAttemptCount)
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ): ListenableWorker = PlanScheduledActionsWorker(appContext, workerParameters, planner)
                },
            )
            .build()
    }

    @Test
    fun `plans up to today and succeeds`() = runTest {
        assertEquals(ListenableWorker.Result.success(), worker {}.doWork())
        assertEquals(1, plans)
    }

    @Test
    fun `a storage failure retries, and gives up after the last attempt`() = runTest {
        val failing: suspend () -> Unit = { throw IOException("disk") }
        assertEquals(ListenableWorker.Result.retry(), worker(runAttemptCount = 0, failing).doWork())
        assertEquals(
            ListenableWorker.Result.failure(),
            worker(runAttemptCount = PlanScheduledActionsWorker.MAX_ATTEMPTS - 1, failing).doWork(),
        )
    }

    @Test
    fun `a database failure retries`() = runTest {
        assertEquals(
            ListenableWorker.Result.retry(),
            worker { throw android.database.SQLException("locked") }.doWork(),
        )
    }
}
