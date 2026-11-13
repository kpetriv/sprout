package dev.kpetriv.sprout.feature.lock

import android.app.Application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.time.Instant

/** The recovery marks survive a process death and are emptied by [RecoveryPinFlag.clear] (ADR-0013 §1). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SharedPreferencesRecoveryPinFlagTest {
    private val context = RuntimeEnvironment.getApplication()

    private fun store() = SharedPreferencesRecoveryPinFlag(context, Dispatchers.Unconfined)

    @Test
    fun `a new store instance reads the marks the last one wrote`() = runTest {
        val marks = RecoveryPinMarks(
            registered = null,
            keptOther = RecoveryRegistration(Instant.fromEpochMilliseconds(1_000), attemptsLeft = 7),
            newPinPending = true,
        )
        store().setMarks(marks)

        val afterRestart = store()
        assertEquals(marks, afterRestart.marks())
        assertEquals(marks, afterRestart.marks.first())

        afterRestart.setMarks(RecoveryPinMarks(registered = RecoveryRegistration(Instant.fromEpochMilliseconds(5), 10)))
        assertEquals(RecoveryRegistration(Instant.fromEpochMilliseconds(5), 10), store().marks().registered)
        assertEquals(false, store().marks().newPinPending)
    }

    @Test
    fun `clear empties the file the wipe lists`() = runTest {
        store().setMarks(RecoveryPinMarks(newPinPending = true))
        store().clear()
        assertEquals(RecoveryPinMarks(), store().marks())
        val file = File(context.dataDir, "shared_prefs/${SharedPreferencesRecoveryPinFlag.FILE_NAME}.xml")
        assertEquals(true, file.exists().not() || !file.readText().contains("new_pin_pending"))
    }
}
