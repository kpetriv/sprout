plugins {
    alias(libs.plugins.sprout.android.library)
    alias(libs.plugins.sprout.hilt)
}

android {
    namespace = "dev.kpetriv.sprout.core.crypto"
    // In-memory fakes (InMemoryDeviceKeyStore, FakeParentAuthorizer, InMemoryHmacPepper, ...) live in src/testFixtures,
    // so they never ship in the app. Consumers: testImplementation(testFixtures(projects.core.crypto)).
    testFixtures {
        enable = true
    }
}

dependencies {
    api(projects.core.time)
    implementation(projects.core.common)
    api(libs.kotlinx.coroutines.core)
    // Argon2id for the parent PIN on device. JNI only, so JVM tests use the PBKDF2 hasher instead.
    // argon2kt 1.6.0 declares appcompat as a runtime dependency but its classes never reference androidx and the
    // AAR ships no resources, so appcompat is dropped to keep it out of the app (and its attack surface).
    implementation(libs.argon2kt) {
        exclude(group = "androidx.appcompat")
    }
    // BiometricManager.canAuthenticate(BIOMETRIC_STRONG) for ParentAuthAvailability. Its appcompat dependency stays:
    // BiometricPrompt's fingerprint dialog (API 28) uses it, and feature/lock needs BiometricPrompt anyway.
    implementation(libs.androidx.biometric)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(testFixtures(projects.core.time))

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
