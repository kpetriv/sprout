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
}

room {
    schemaDirectory("$projectDir/schemas")
    generateKotlin = true
}

dependencies {
    api(projects.core.domainLedger)
    api(projects.core.model)
    implementation(projects.core.crypto)

    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    // Encrypts the database file at rest; see DatabaseFactory.encrypted.
    implementation(libs.sqlcipher.android)

    testImplementation(testFixtures(projects.core.domainLedger))
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
