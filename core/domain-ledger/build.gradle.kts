plugins {
    alias(libs.plugins.sprout.jvm.library)
}

dependencies {
    // Ledger events carry kotlin.time.Instant timestamps from an injected Clock (M1).
    api(projects.core.time)

    testImplementation(testFixtures(projects.core.time))
}
