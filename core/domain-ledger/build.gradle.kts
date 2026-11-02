plugins {
    alias(libs.plugins.sprout.jvm.library)
    alias(libs.plugins.kotlin.serialization)
    // Fixtures, the in-memory LedgerRepository and its contract test are shared with core/database's tests.
    `java-test-fixtures`
}

dependencies {
    // Ledger events carry kotlin.time.Instant timestamps from an injected Clock (M1).
    api(projects.core.time)
    // Shared ids (KidId, DeviceId, ...), Role and CalendarDate.
    api(projects.core.model)
    // Canonical encoding for hashing and signing, and the storage/wire form of events.
    api(libs.kotlinx.serialization.json)
    // LedgerRepository exposes reads as Flow.
    api(libs.kotlinx.coroutines.core)

    testImplementation(testFixtures(projects.core.time))
    testImplementation(libs.kotlinx.coroutines.test)

    // DeviceWriter takes a FakeClock in its constructor, so it is part of the fixtures' API.
    testFixturesApi(testFixtures(projects.core.time))
    testFixturesImplementation(libs.kotlinx.coroutines.test)
    testFixturesImplementation(libs.kotlin.test.junit)
}
