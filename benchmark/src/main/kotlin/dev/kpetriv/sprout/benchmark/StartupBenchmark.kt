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
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

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
        setupBlock = { pressHome() },
    ) {
        startToParentDashboard()
    }
}
