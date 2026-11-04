package dev.kpetriv.sprout.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cold start to the unlocked parent dashboard, without any ahead-of-time compilation and with the Baseline Profile,
 * against the `benchmarkRelease` build (R8, D-M7-3). Run on an emulator or device (README "Performance"):
 * `./gradlew :benchmark:connectedBenchmarkReleaseAndroidTest`.
 *
 * `StartupTimingMetric` reports `timeToInitialDisplayMs` (the shell's first frame, the loading indicator) and
 * `timeToFullDisplayMs` (the app's `ReportDrawnWhen`: startup is over and the PIN screen has content, D-M7-43).
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    /** Whether this test has started the app once, outside the measurement (see [startup]). */
    private var prepared = false

    @Test
    fun startupWithoutCompilation() = startup(CompilationMode.None())

    @Test
    fun startupWithBaselineProfile() = startup(CompilationMode.Partial(BaselineProfileMode.Require))

    private fun startup(compilationMode: CompilationMode) = rule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = compilationMode,
        startupMode = StartupMode.COLD,
        iterations = ITERATIONS,
        // The first start of a fresh install seeds the demo household and asks for a new PIN: done once, unmeasured,
        // so every measured iteration is the same cold start of a set-up app (D-V1-11). The rule kills the process
        // after this block.
        setupBlock = {
            if (!prepared) {
                startToParentDashboard()
                prepared = true
            }
            pressHome()
        },
    ) {
        startToParentDashboard()
    }
}
