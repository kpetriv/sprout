plugins {
    alias(libs.plugins.sprout.android.feature)
}

android {
    namespace = "dev.kpetriv.sprout.feature.wallet"

    // Robolectric Compose tests read this module's string resources.
    testOptions.unitTests.isIncludeAndroidResources = true
}

dependencies {
    implementation(projects.core.domainLedger)

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    // Registers the empty ComponentActivity that createComposeRule launches.
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
