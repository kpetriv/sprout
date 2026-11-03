plugins {
    alias(libs.plugins.sprout.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    // Household models (chores, submissions, schedules) and CalendarDate.
    api(projects.core.model)
    // Money, Currency, Account and InterestCalculator; planners produce intents the data layer turns into events.
    api(projects.core.domainLedger)
    // Instant timestamps on chores and submissions come from the injected Clock.
    api(projects.core.time)

    testImplementation(libs.kotlinx.serialization.json)
}
