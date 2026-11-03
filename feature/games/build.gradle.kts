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
    // Clock: how long each round took (timed mode and its bonus).
    implementation(projects.core.time)
}
