plugins {
    alias(libs.plugins.sprout.android.feature)
    // Robolectric, Compose UI test and Roborazzi for the screen tests and screenshot goldens in src/test/screenshots.
    alias(libs.plugins.sprout.android.screenshot)
}

android {
    namespace = "dev.kpetriv.sprout.feature.parenthome"
}

dependencies {
    // Kids, accounts, chore submissions, allowance schedules, the pending parent action queue and its drain use case.
    implementation(projects.core.database)
    // AllowancePlanner.nextDueDate and PendingParentAction.
    implementation(projects.core.domainHousehold)
    // LedgerRepository balances and anomalies, Money, Currency, ParentAuthorization.
    implementation(projects.core.domainLedger)
    implementation(projects.core.time)
}
