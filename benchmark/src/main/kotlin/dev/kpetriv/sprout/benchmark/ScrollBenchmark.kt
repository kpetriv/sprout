package dev.kpetriv.sprout.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Frame timing while a kid scrolls their wallet, with the Baseline Profile, against the `benchmarkRelease` build
 * (D-M7-3). The first iteration starts the app, unlocks it and enters kid mode outside the measurement; only the
 * scroll is measured. Run with `./gradlew :benchmark:connectedBenchmarkReleaseAndroidTest` (README "Performance").
 */
@RunWith(AndroidJUnit4::class)
class ScrollBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun kidWalletScroll() = rule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
        // The process stays up between iterations (null: no kill); the setup only brings the wallet back.
        startupMode = null,
        iterations = ITERATIONS,
        setupBlock = {
            startActivityAndWait()
            reachKidWallet()
        },
    ) {
        scrollKidWallet()
    }
}
