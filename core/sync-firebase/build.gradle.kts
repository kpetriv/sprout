plugins {
    alias(libs.plugins.sprout.android.library)
    alias(libs.plugins.sprout.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.kpetriv.sprout.core.syncfirebase"
    // FirebaseApp.initializeApp reads the (absent) google-services string resources; Robolectric needs merged resources.
    testOptions.unitTests.isIncludeAndroidResources = true
}

// The opt-in suite against a running Firebase Emulator Suite (FirebaseEmulatorTransportTest) runs only with
// -Psprout.firebaseEmulator=true; without it the suite is skipped (assumeTrue), so the normal build needs no emulator.
val firebaseEmulator = providers.gradleProperty("sprout.firebaseEmulator").orElse("false")
tasks.withType<Test>().configureEach {
    systemProperty("sprout.firebaseEmulator", firebaseEmulator.get())
    // A running emulator is outside Gradle's view: never serve the opt-in suite's result from the build cache.
    if (firebaseEmulator.get() == "true") outputs.upToDateWhen { false }
}

dependencies {
    // SyncTransport, DocumentEnvelope, cursors and errors are part of this module's API.
    api(projects.core.sync)
    implementation(projects.core.common)

    // The only module that imports Firebase (ADR-0001). No google-services plugin here: app owns the configuration.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.functions)
    implementation(libs.firebase.appcheck.playintegrity)
    // The debug App Check provider never ships in release builds (src/debug vs src/release, D-M3-6).
    debugImplementation(libs.firebase.appcheck.debug)
    implementation(libs.kotlinx.coroutines.play.services)

    // Parent sign-in with Google through Credential Manager (release builds, D-M3-2).
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
}
