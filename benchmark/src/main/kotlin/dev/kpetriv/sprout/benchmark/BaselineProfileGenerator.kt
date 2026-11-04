package dev.kpetriv.sprout.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates the app's Baseline Profile and startup profile from the journeys in `Journeys.kt`, against the
 * `nonMinifiedRelease` benchmark build (D-M7-3). Run on an emulator or device (README "Performance"):
 * `./gradlew :app:generateBaselineProfile`; the result lands in `app/src/main/generated/baselineProfiles`.
 *
 * [startup] alone feeds the startup profile (`startup-prof.txt`, the dex layout of the startup path); both feed the
 * Baseline Profile (`baseline-prof.txt`). The kid-mode journeys are not perfectly repeatable (the arithmetic rounds are
 * random), so their rules never become fully stable; [MAX_ITERATIONS] bounds the collection.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    /** Cold start → PIN → parent dashboard (the PIN is chosen on the first run of a fresh install). */
    @Test
    fun startup() = rule.collect(packageName = TARGET_PACKAGE, includeInStartupProfile = true) {
        pressHome()
        startToParentDashboard()
    }

    /** Kid mode: wallet scroll, today's chores, games hub → an arithmetic game, then back to the parent. */
    @Test
    fun kidJourneys() = rule.collect(packageName = TARGET_PACKAGE, maxIterations = MAX_ITERATIONS) {
        pressHome()
        startToParentDashboard()
        enterKidMode()
        scrollKidWallet()
        openChoresToday()
        playArithmeticRound()
        leaveKidMode()
    }

    private companion object {
        const val MAX_ITERATIONS = 6
    }
}
