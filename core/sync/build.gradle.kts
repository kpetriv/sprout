plugins {
    alias(libs.plugins.sprout.jvm.library)
    alias(libs.plugins.kotlin.serialization)
    // FakeSyncTransport, FakeBackend, InMemoryOutboxSource and RecordingApplier are shared with core/database's
    // convergence tests and core/sync-firebase's tests.
    `java-test-fixtures`
}

dependencies {
    // Ids (HouseholdId, DeviceId, UserId, KidId), Role and ChoreSubmission.
    api(projects.core.model)
    // DeviceRegistry, SignatureCheck and CanonicalEncoder: documents are verified against the device registry.
    api(projects.core.domainLedger)
    // SubmissionStatusPrecedence for chore_submission conflicts.
    api(projects.core.domainHousehold)
    // Envelopes, events and cursors travel as JSON trees.
    api(libs.kotlinx.serialization.json)
    // Suspending ports and the engine's change Flow.
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(testFixtures(projects.core.time))
    testImplementation(testFixtures(projects.core.domainLedger))

    testFixturesImplementation(libs.kotlinx.coroutines.core)
}
