plugins {
    alias(libs.plugins.sprout.android.feature)
    // Robolectric, Compose UI test and Roborazzi for the screen tests and screenshot goldens in src/test/screenshots.
    alias(libs.plugins.sprout.android.screenshot)
}

android {
    namespace = "dev.kpetriv.sprout.feature.lock"
}

dependencies {
    // PinHasher, PinLockoutPolicy, ParentAuthorizer and ParentSigner appear in this module's public API.
    api(projects.core.crypto)
    // ParentAuthorizationRequester hands out the ledger's ParentAuthorization.
    api(projects.core.domainLedger)

    // BiometricPrompt (BIOMETRIC_STRONG only) for the biometric unlock and per-event parent authorisation.
    implementation(libs.androidx.biometric)
    // ProcessLifecycleOwner: auto-lock when the whole app goes to the background.
    implementation(libs.androidx.lifecycle.process)

    testImplementation(testFixtures(projects.core.crypto))
}
