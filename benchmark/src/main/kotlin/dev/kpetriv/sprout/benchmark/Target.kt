package dev.kpetriv.sprout.benchmark

/** Application id of the app under test. */
internal const val TARGET_PACKAGE = "dev.kpetriv.sprout"

/** Measured iterations per benchmark (README "Performance" reports P50/P90 over these). */
internal const val ITERATIONS = 10

/**
 * The demo household the journeys (`Journeys.kt`: the paths the Baseline Profile covers and the benchmarks measure)
 * drive through UiAutomator in the benchmark build types (`BuildConfig.BENCHMARK`, D-M7-3): the trial household (Mia
 * 6, Leo 9, Zoe 13) is seeded at first start, offline. Screens are found by their visible English text, so the journeys
 * assume an English device.
 */
internal object Demo {
    /**
     * The parent PIN the journeys choose on the first start of a freshly installed benchmark build and enter after
     * that. Only ever used for the seeded demo household of a benchmark build; it passes `PinRules`.
     */
    const val PIN = "258036"

    /**
     * The demo kid the kid-mode journeys use: Zoe, 13, the Sapling tier, whose wallet history is a lazy list the
     * benchmark seed fills (`BenchmarkSeed`) and whose arithmetic rounds take typed answers.
     */
    const val KID = "Zoe"
}
