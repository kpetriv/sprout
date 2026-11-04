plugins {
    alias(libs.plugins.sprout.android.feature)
    // Robolectric, Compose UI test and Roborazzi for the screen tests and screenshot goldens in src/test/screenshots.
    alias(libs.plugins.sprout.android.screenshot)
}

android {
    namespace = "dev.kpetriv.sprout.feature.games"
}

dependencies {
    // GameSession, rounds, GameScorer and GameCredit: the pure game logic.
    implementation(projects.core.games)
    // Money and Currency in the ports (the app adapts core/database's use case and cap reader to them).
    implementation(projects.core.domainLedger)
    // BackHandler: system back is disabled while a credit is being saved.
    implementation(libs.androidx.activity.compose)
    constraints {
        // activity 1.13 asks for navigationevent 1.0.0; the app resolves 1.1.1 (through navigation3), which is the
        // version in gradle/verification-metadata.xml. Align on it so this module verifies on its own too.
        implementation("androidx.navigationevent:navigationevent:1.1.1")
    }
    // activity 1.13 asks for androidx.core 1.18; align on the catalog's version, which the app resolves.
    implementation(libs.androidx.core.ktx)
}
