// Android test helpers (Android fakes, screenshot infra). Pure-JVM fakes such as FakeClock live in the
// owning module's test fixtures instead, e.g. testImplementation(testFixtures(projects.core.time)).
//
// Everything here is in src/main on purpose: this module is only ever a testImplementation dependency
// (sprout.android.feature and sprout.android.screenshot add it), so its "main" code is test code and its
// test libraries (Robolectric, Roborazzi, Compose UI test) never reach an APK.
plugins {
    alias(libs.plugins.sprout.android.library.compose)
}

android {
    namespace = "dev.kpetriv.sprout.core.testing"
    // The module's own Robolectric tests (ScreenshotRuleTest, AccessibilityRuleTest).
    testOptions.unitTests.isIncludeAndroidResources = true
}

// Nothing here ships, so there is nothing for Dependency-Check to scan; Robolectric's own tooling
// dependencies would otherwise fail the weekly scan on findings that never reach a device.
dependencyCheck {
    skip.set(true)
}

dependencies {
    api(projects.core.common)
    api(projects.core.designsystem)
    // WindowWidthClass / LocalWindowWidthClass for ScreenshotRule.captureWidthClasses.
    api(projects.core.ui)
    api(libs.junit4)
    api(libs.kotlin.test.junit)
    api(libs.kotlinx.coroutines.test)

    // Screenshot infra (dev.kpetriv.sprout.core.testing.screenshot).
    api(libs.robolectric)
    api(libs.roborazzi)
    api(libs.roborazzi.compose)
    api(libs.roborazzi.junit.rule)
    api(libs.androidx.compose.ui.test.junit4)

    // Registers the empty ComponentActivity the module's own tests launch.
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
