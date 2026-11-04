plugins {
    alias(libs.plugins.sprout.android.library)
    alias(libs.plugins.sprout.hilt)
    alias(libs.plugins.room)
}

android {
    namespace = "dev.kpetriv.sprout.core.database"

    // MigrationTestHelper reads the exported schemas as assets, on device and under Robolectric.
    sourceSets {
        getByName("androidTest").assets.directories.add("$projectDir/schemas")
        getByName("test").assets.directories.add("$projectDir/schemas")
    }
    testOptions.unitTests.isIncludeAndroidResources = true

    // TestHousehold and its helpers: shared with other modules' tests that need one or two wired households.
    testFixtures {
        enable = true
    }
}

room {
    schemaDirectory("$projectDir/schemas")
    generateKotlin = true
}

dependencies {
    api(projects.core.domainLedger)
    // GameId and Score in PlayGameUseCase's signature, and GameCredit for what a play earns (M5).
    api(projects.core.games)
    // Chores, rewards, allowance, savings and their planners (M2); repositories expose these models.
    api(projects.core.domainHousehold)
    api(projects.core.model)
    // Sync ports (OutboxSource, RemoteApplier), DocumentEnvelope, DocTypes and the document verifier/resolver.
    api(projects.core.sync)
    // DeviceSigner and DeviceKeyStore appear in public constructors (repositories, test fixtures).
    api(projects.core.crypto)
    // The IO dispatcher qualifier for the photo store's file work.
    implementation(projects.core.common)

    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    // Encrypts the database file at rest; see DatabaseFactory.encrypted.
    implementation(libs.sqlcipher.android)

    testFixturesApi(testFixtures(projects.core.crypto))
    testFixturesApi(testFixtures(projects.core.time))
    testFixturesImplementation(libs.androidx.room.runtime)
    testFixturesImplementation(libs.kotlinx.coroutines.core)

    testImplementation(testFixtures(projects.core.domainLedger))
    // FakeBackend, FakeSyncTransport and SoftwareDeviceKey for the two-device convergence tests.
    testImplementation(testFixtures(projects.core.sync))
    // In-memory device key store, key wrapper and blob storage (never shipped in main).
    testImplementation(testFixtures(projects.core.crypto))
    testImplementation(testFixtures(projects.core.time))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.room.testing)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(testFixtures(projects.core.crypto))
}
