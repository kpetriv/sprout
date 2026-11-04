package dev.kpetriv.sprout.benchmark

import android.app.UiAutomation
import android.os.ParcelFileDescriptor
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry

/** How long a start may take to report itself fully drawn (`ReportDrawnWhen`, D-M7-43) before the journey goes on. */
private const val FULLY_DRAWN_TIMEOUT_MS = 120_000L
private const val FULLY_DRAWN_POLL_MS = 500L

/** The most recent system log lines searched for the fully drawn line (it is logged by the activity manager). */
private const val LOG_LINES = 20

/**
 * Starts the app with accessibility off in it and, when that started its process, waits until the app reports itself
 * fully drawn (D-V1-11).
 *
 * UiAutomator's connection is an accessibility client, so while it is connected the app runs with accessibility on.
 * On the emulator that made the loading screen keep the main thread busy and startup about three times slower (a
 * seeded cold start 31.6 s against 11.4 s; a fresh install's first start 79 s against 9 s), which is neither what most
 * users see nor what the Baseline Profile is about. So the connection is switched to shell-only
 * ([UiAutomation.FLAG_DONT_USE_ACCESSIBILITY]) for the start: the same `UiAutomation` reconnects, so Macrobenchmark's
 * own shell keeps working, and the next UiAutomator call (`device`) reconnects it with accessibility. Until then
 * nothing may touch `device`, so the fully drawn moment is read from the system log, not from the screen.
 */
internal fun MacrobenchmarkScope.startWithoutAccessibility() {
    val automation = InstrumentationRegistry.getInstrumentation()
        .getUiAutomation(UiAutomation.FLAG_DONT_USE_ACCESSIBILITY)
    if (automation.run("pidof $TARGET_PACKAGE").isNotBlank()) {
        // The process is up (a warm journey): nothing to wait for, the activity only comes to the front.
        startActivityAndWait()
        return
    }
    val before = automation.lastFullyDrawn()
    startActivityAndWait()
    val deadline = System.currentTimeMillis() + FULLY_DRAWN_TIMEOUT_MS
    while (automation.lastFullyDrawn() == before && System.currentTimeMillis() < deadline) {
        Thread.sleep(FULLY_DRAWN_POLL_MS)
    }
}

/** The last "Fully drawn" line of the target app in the system log, if any. */
private fun UiAutomation.lastFullyDrawn(): String? =
    run("logcat -t $LOG_LINES -s ActivityTaskManager:I ActivityManager:I")
        .lineSequence()
        .lastOrNull { "Fully drawn $TARGET_PACKAGE/" in it }

/** Runs [command] as the shell user (no shell syntax: no pipes or quotes) and returns its standard output. */
private fun UiAutomation.run(command: String): String =
    ParcelFileDescriptor.AutoCloseInputStream(executeShellCommand(command)).use { it.readBytes().decodeToString() }
