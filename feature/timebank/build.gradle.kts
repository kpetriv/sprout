plugins {
    alias(libs.plugins.sprout.android.feature)
    // Robolectric, Compose UI test and Roborazzi for the screen tests and screenshot goldens in src/test/screenshots.
    alias(libs.plugins.sprout.android.screenshot)
}

android {
    namespace = "dev.kpetriv.sprout.feature.timebank"
}

dependencies {
    // Clock and HouseholdCalendar behind the default TimeBankClock.
    implementation(projects.core.time)
    // rememberLauncherForActivityResult + RequestPermission: POST_NOTIFICATIONS on the first Start (Android 13+).
    implementation(libs.androidx.activity.compose)
    constraints {
        // activity 1.13 asks for navigationevent 1.0.0; the app resolves 1.1.1 (through navigation3), which is the
        // version in gradle/verification-metadata.xml. Align on it so this module verifies on its own too.
        implementation("androidx.navigationevent:navigationevent:1.1.1")
    }
    // ContextCompat.checkSelfPermission.
    implementation(libs.androidx.core.ktx)
}
