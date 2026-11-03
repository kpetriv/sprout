plugins {
    alias(libs.plugins.sprout.android.feature)
    // Robolectric, Compose UI test and Roborazzi for the screen tests and screenshot goldens in src/test/screenshots.
    alias(libs.plugins.sprout.android.screenshot)
}

android {
    namespace = "dev.kpetriv.sprout.feature.wallet"
}

dependencies {
    implementation(projects.core.domainLedger)
    // Rewards, allowance and savings goals (repositories and the purchase / manual adjustment use cases).
    implementation(projects.core.database)
    // ParentAuthUnavailableException: a device without a strong biometric (D-M2-1 fallback).
    implementation(projects.core.crypto)
}
