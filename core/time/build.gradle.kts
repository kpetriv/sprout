plugins {
    alias(libs.plugins.sprout.jvm.library)
    // FakeClock lives in testFixtures so JVM and Android modules can share it in tests.
    `java-test-fixtures`
}

dependencies {
    // HouseholdCalendar answers in CalendarDate and publishes the date as a Flow.
    api(projects.core.model)
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlinx.coroutines.test)
}
