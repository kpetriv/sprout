plugins {
    alias(libs.plugins.sprout.android.feature)
    // Robolectric, Compose UI test and Roborazzi for the screen tests and screenshot goldens in src/test/screenshots.
    alias(libs.plugins.sprout.android.screenshot)
}

android {
    namespace = "dev.kpetriv.sprout.feature.settings"
}

dependencies {
    // Kid, allowance, savings goal, interest and household policy repositories; every write audits and outboxes.
    implementation(projects.core.database)
    // AllowanceSchedule, SavingsGoal and AllowancePlanner.nextDueDate.
    implementation(projects.core.domainHousehold)
    // Money, Currency, Account and InterestCalculator.
    implementation(projects.core.domainLedger)
    // ParentAuthorizer.availability() for the fingerprint signing status, LockoutDecision for PIN change feedback.
    implementation(projects.core.crypto)
    implementation(projects.core.time)

    testImplementation(testFixtures(projects.core.crypto))
}
