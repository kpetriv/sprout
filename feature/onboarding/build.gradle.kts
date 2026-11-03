plugins {
    alias(libs.plugins.sprout.android.feature)
    // Robolectric, Compose UI test and Roborazzi for the screenshot goldens in src/test/screenshots.
    alias(libs.plugins.sprout.android.screenshot)
}

android {
    namespace = "dev.kpetriv.sprout.feature.onboarding"
}

dependencies {
    // Session, SessionStore and RegistrationStatus: onboarding saves the session it creates.
    implementation(projects.core.database)
    // DeviceKeyStore.regenerateForRegistration: a fresh, attested device key for every registration (M3 §1).
    implementation(projects.core.crypto)
    // Currency.Fiat for the household currency picker.
    implementation(projects.core.domainLedger)
    implementation(projects.core.time)

    testImplementation(testFixtures(projects.core.crypto))
}
